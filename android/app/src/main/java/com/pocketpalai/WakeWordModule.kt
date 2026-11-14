package com.pocketpal

import android.util.Base64
import com.facebook.react.bridge.Promise
import com.facebook.react.bridge.ReactApplicationContext
import com.facebook.react.module.annotations.ReactModule
import com.facebook.react.modules.core.DeviceEventManagerModule
import com.pocketpal.specs.NativeWakeWordSpec
import org.json.JSONObject

/**
 * JS bridge for [WakeWordService]. Starting and stopping the listener goes
 * through here; detections come back as a `WakeWordDetected` device event
 * while the bridge is alive, and as a persisted pending wake (see
 * [consumePendingWake]) when the service had to relaunch the app.
 */
@ReactModule(name = NativeWakeWordSpec.NAME)
class WakeWordModule(reactContext: ReactApplicationContext) :
    NativeWakeWordSpec(reactContext) {

  init {
    WakeWordService.detectionListener = {
      try {
        reactApplicationContext
            .getJSModule(DeviceEventManagerModule.RCTDeviceEventEmitter::class.java)
            .emit(EVENT_NAME, null)
      } catch (t: Throwable) {
        // Bridge not ready; the pending-wake flag still carries the event.
      }
    }
  }

  override fun invalidate() {
    WakeWordService.detectionListener = null
    super.invalidate()
  }

  override fun startListening(phraseKey: String, threshold: Double) {
    WakeWordService.start(reactApplicationContext, phraseKey, threshold)
  }

  override fun stopListening() {
    WakeWordService.stop(reactApplicationContext)
  }

  override fun isListening(promise: Promise) {
    promise.resolve(WakeWordService.isRunning)
  }

  override fun consumePendingWake(promise: Promise) {
    promise.resolve(WakeWordService.consumePendingWake(reactApplicationContext))
  }

  override fun beginTeaching() {
    WakeWordTeacher.begin(reactApplicationContext)
  }

  override fun addTeachingTake(pcmBase64: String, promise: Promise) {
    try {
      val bytes = Base64.decode(pcmBase64, Base64.DEFAULT)
      val pcm = ShortArray(bytes.size / 2)
      for (i in pcm.indices) {
        val lo = bytes[i * 2].toInt() and 0xFF
        val hi = bytes[i * 2 + 1].toInt()
        pcm[i] = (hi shl 8 or lo).toShort()
      }
      val result = WakeWordTeacher.analyzeTake(reactApplicationContext, pcm)
      val json = JSONObject()
      when (result) {
        is WakeWordTeacher.TakeResult.Accepted -> {
          json.put("accepted", true)
          json.put("count", result.count)
        }
        is WakeWordTeacher.TakeResult.Rejected -> {
          json.put("accepted", false)
          json.put("reason", result.reason.name.lowercase())
          json.put("count", result.count)
        }
      }
      promise.resolve(json.toString())
    } catch (t: Throwable) {
      promise.reject("teaching_take_failed", t.message, t)
    }
  }

  override fun finishTeaching(name: String, promise: Promise) {
    promise.resolve(WakeWordTeacher.finish(reactApplicationContext, name))
  }

  override fun cancelTeaching() {
    WakeWordTeacher.cancel()
  }

  override fun customWakeWordInfo(promise: Promise) {
    val template = CustomWakeWordTemplate.load(reactApplicationContext)
    val json = JSONObject()
    json.put("exists", template != null)
    json.put("name", template?.name ?: "")
    promise.resolve(json.toString())
  }

  override fun deleteCustomWakeWord() {
    CustomWakeWordTemplate.delete(reactApplicationContext)
  }

  companion object {
    const val EVENT_NAME = "WakeWordDetected"
  }
}
