package com.pocketpal

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import android.os.Build
import android.os.IBinder
import android.os.SystemClock
import android.util.Log

/**
 * Always-on wake-word listener. Runs as a microphone foreground service
 * (Android requires the persistent notification), captures 16 kHz PCM on a
 * background thread and scores it with [WakeWordDetector]. Audio never
 * leaves the thread and is never stored.
 *
 * On detection the service records a pending wake (read by the JS side via
 * the module), notifies the JS listener if the bridge is alive, and brings
 * the app to the foreground so the voice conversation can start.
 */
class WakeWordService : Service() {

  companion object {
    private const val TAG = "WakeWordService"
    private const val NOTIFICATION_ID = 4711
    private const val CHANNEL_ID = "wake_word_listening"
    private const val REFRACTORY_MS = 2500L
    private const val PENDING_WAKE_MAX_AGE_MS = 120_000L

    const val EXTRA_PHRASE = "phraseKey"
    const val EXTRA_THRESHOLD = "threshold"

    private const val PREFS_NAME = "wake_word"
    private const val PREF_PENDING_WAKE_TS = "pending_wake_ts"

    @Volatile var isRunning: Boolean = false
      private set

    /** Set by WakeWordModule while the React bridge is alive. */
    @Volatile var detectionListener: (() -> Unit)? = null

    fun start(context: Context, phraseKey: String, threshold: Double) {
      val intent =
          Intent(context, WakeWordService::class.java).apply {
            putExtra(EXTRA_PHRASE, phraseKey)
            putExtra(EXTRA_THRESHOLD, threshold)
          }
      if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
        context.startForegroundService(intent)
      } else {
        context.startService(intent)
      }
    }

    fun stop(context: Context) {
      context.stopService(Intent(context, WakeWordService::class.java))
    }

    fun consumePendingWake(context: Context): Boolean {
      val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
      val ts = prefs.getLong(PREF_PENDING_WAKE_TS, 0L)
      if (ts == 0L) {
        return false
      }
      prefs.edit().remove(PREF_PENDING_WAKE_TS).apply()
      return System.currentTimeMillis() - ts <= PENDING_WAKE_MAX_AGE_MS
    }

    private fun markPendingWake(context: Context) {
      context
          .getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
          .edit()
          .putLong(PREF_PENDING_WAKE_TS, System.currentTimeMillis())
          .apply()
    }
  }

  @Volatile private var capturing = false
  private var captureThread: Thread? = null

  override fun onBind(intent: Intent?): IBinder? = null

  override fun onCreate() {
    super.onCreate()
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
      val channel =
          NotificationChannel(
              CHANNEL_ID,
              "Wake word listening",
              NotificationManager.IMPORTANCE_LOW,
          )
      val manager = getSystemService(NotificationManager::class.java)
      manager?.createNotificationChannel(channel)
    }
  }

  override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
    val phrase = WakePhrases.byKey(intent?.getStringExtra(EXTRA_PHRASE) ?: WakePhrases.ALL[0].key)
    val threshold = intent?.getDoubleExtra(EXTRA_THRESHOLD, 0.5) ?: 0.5

    val notification = buildNotification(phrase.label)
    if (Build.VERSION.SDK_INT >= 34) {
      startForeground(
          NOTIFICATION_ID,
          notification,
          ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE,
      )
    } else {
      startForeground(NOTIFICATION_ID, notification)
    }
    isRunning = true

    stopCapture()
    startCapture(phrase, threshold)
    return START_STICKY
  }

  override fun onDestroy() {
    stopCapture()
    isRunning = false
    super.onDestroy()
  }

  private fun buildNotification(phraseLabel: String): Notification {
    val launchIntent = packageManager.getLaunchIntentForPackage(packageName)
    val pendingIntent =
        launchIntent?.let {
          PendingIntent.getActivity(
              this,
              0,
              it,
              PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
          )
        }
    @Suppress("DEPRECATION")
    val builder =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
          Notification.Builder(this, CHANNEL_ID)
        } else {
          Notification.Builder(this)
        }
    return builder
        .setContentTitle("PocketPal is listening")
        .setContentText("Say \u201C$phraseLabel\u201D to talk hands-free")
        .setSmallIcon(android.R.drawable.ic_btn_speak_now)
        .setContentIntent(pendingIntent)
        .setOngoing(true)
        .build()
  }

  private fun startCapture(phrase: WakePhrase, threshold: Double) {
    capturing = true
    captureThread =
        Thread {
              var detector: WakeWordDetector? = null
              var record: AudioRecord? = null
              try {
                detector = WakeWordDetector(this, phrase)
                val minBuffer =
                    AudioRecord.getMinBufferSize(
                        WakeWordDetector.SAMPLE_RATE,
                        AudioFormat.CHANNEL_IN_MONO,
                        AudioFormat.ENCODING_PCM_16BIT,
                    )
                record =
                    AudioRecord(
                        MediaRecorder.AudioSource.MIC,
                        WakeWordDetector.SAMPLE_RATE,
                        AudioFormat.CHANNEL_IN_MONO,
                        AudioFormat.ENCODING_PCM_16BIT,
                        maxOf(minBuffer, WakeWordDetector.FRAME_SAMPLES * 4),
                    )
                if (record.state != AudioRecord.STATE_INITIALIZED) {
                  Log.w(TAG, "AudioRecord failed to initialize")
                  stopSelf()
                  return@Thread
                }
                record.startRecording()

                val frame = ShortArray(WakeWordDetector.FRAME_SAMPLES)
                var lastTriggerMs = 0L
                while (capturing) {
                  var filled = 0
                  while (filled < frame.size && capturing) {
                    val read = record.read(frame, filled, frame.size - filled)
                    if (read <= 0) {
                      break
                    }
                    filled += read
                  }
                  if (filled < frame.size) {
                    continue
                  }
                  val score = detector.feed(frame)
                  val now = SystemClock.elapsedRealtime()
                  if (score >= threshold && now - lastTriggerMs > REFRACTORY_MS) {
                    lastTriggerMs = now
                    onDetected()
                  }
                }
              } catch (t: Throwable) {
                Log.e(TAG, "Wake word capture failed", t)
                stopSelf()
              } finally {
                try {
                  record?.stop()
                } catch (t: Throwable) {
                  // Already stopped; nothing to do.
                }
                record?.release()
                detector?.close()
              }
            }
            .apply {
              name = "wake-word-capture"
              start()
            }
  }

  private fun stopCapture() {
    capturing = false
    captureThread?.interrupt()
    captureThread = null
  }

  private fun onDetected() {
    markPendingWake(this)
    detectionListener?.invoke()
    val launchIntent = packageManager.getLaunchIntentForPackage(packageName)
    if (launchIntent != null) {
      launchIntent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP)
      try {
        startActivity(launchIntent)
      } catch (t: Throwable) {
        Log.w(TAG, "Could not bring app to foreground on wake", t)
      }
    }
  }
}
