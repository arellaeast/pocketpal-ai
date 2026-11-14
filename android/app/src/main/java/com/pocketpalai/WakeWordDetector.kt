package com.pocketpal

import android.content.Context
import org.tensorflow.lite.Interpreter
import java.io.FileInputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.MappedByteBuffer
import java.nio.channels.FileChannel

/** A wake phrase the detector can listen for, backed by a classifier model. */
data class WakePhrase(val key: String, val label: String, val classifierAsset: String)

object WakePhrases {
  val ALL: List<WakePhrase> =
      listOf(
          WakePhrase("hey_jarvis", "Hey Jarvis", "wakeword/hey_jarvis_v0.1.tflite"),
          WakePhrase("alexa", "Alexa", "wakeword/alexa_v0.1.tflite"),
          WakePhrase("hey_mycroft", "Hey Mycroft", "wakeword/hey_mycroft_v0.1.tflite"),
      )

  fun byKey(key: String): WakePhrase = ALL.firstOrNull { it.key == key } ?: ALL[0]
}

/**
 * Streaming wake-word detection over the openWakeWord model family
 * (melspectrogram + speech embedding + per-phrase classifier, Apache-2.0),
 * running on TensorFlow Lite.
 *
 * Audio is consumed one 1280-sample frame (80 ms of 16 kHz mono PCM) at a
 * time. Each frame is scored against the newest 480 samples of history:
 * the melspectrogram model turns the last 1760 samples into 8 new mel
 * frames, the embedding model turns the newest 76 mel frames into one
 * 96-dim embedding, and the classifier scores the newest 16 embeddings.
 */
class WakeWordDetector(context: Context, phrase: WakePhrase) {

  companion object {
    const val SAMPLE_RATE = 16000
    const val FRAME_SAMPLES = 1280

    private const val HISTORY_SAMPLES = 480
    private const val MEL_BINS = 32
    private const val MEL_WINDOW = 76
    private const val MEL_MAX_ROWS = 970
    private const val EMBED_DIM = 96
    private const val FEATURE_MAX_ROWS = 120
    private const val CLASSIFIER_FRAMES = 16

    private const val MEL_ASSET = "wakeword/melspectrogram.tflite"
    private const val EMBED_ASSET = "wakeword/embedding_model.tflite"
  }

  private val melInterpreter: Interpreter
  private val embedInterpreter: Interpreter
  private val classifierInterpreter: Interpreter

  private val melOutputFloats: Int

  /** Rolling raw audio history as floats (int16 values), newest last. */
  private val rawHistory = ArrayDeque<Float>()

  /** Mel rows of [MEL_BINS] floats, newest last; warm-started with ones. */
  private val melRows = ArrayDeque<FloatArray>()

  /** Embedding rows of [EMBED_DIM] floats, newest last. */
  private val featureRows = ArrayDeque<FloatArray>()

  private val melInput: ByteBuffer
  private val melOutput: ByteBuffer
  private val embedInput: ByteBuffer = floatBuffer(MEL_WINDOW * MEL_BINS)
  private val embedOutput: ByteBuffer = floatBuffer(EMBED_DIM)
  private val classifierInput: ByteBuffer = floatBuffer(CLASSIFIER_FRAMES * EMBED_DIM)
  private val classifierOutput: ByteBuffer = floatBuffer(1)

  init {
    val appContext = context.applicationContext
    val options = Interpreter.Options().setNumThreads(2)

    melInterpreter = Interpreter(loadModel(appContext, MEL_ASSET), options)
    // The mel model takes a variable number of samples; fix it to one
    // frame plus history before allocating (also keeps XNNPack happy).
    melInterpreter.resizeInput(0, intArrayOf(1, FRAME_SAMPLES + HISTORY_SAMPLES))
    melInterpreter.allocateTensors()
    melOutputFloats = melInterpreter.getOutputTensor(0).numElements()
    melInput = floatBuffer(FRAME_SAMPLES + HISTORY_SAMPLES)
    melOutput = floatBuffer(melOutputFloats)

    embedInterpreter = Interpreter(loadModel(appContext, EMBED_ASSET), options)
    embedInterpreter.allocateTensors()

    classifierInterpreter = Interpreter(loadModel(appContext, phrase.classifierAsset), options)
    classifierInterpreter.allocateTensors()

    repeat(MEL_WINDOW) { melRows.addLast(FloatArray(MEL_BINS) { 1.0f }) }
  }

  /** Feed one 1280-sample frame; returns the phrase score in [0, 1]. */
  @Synchronized
  fun feed(frame: ShortArray): Float {
    for (sample in frame) {
      rawHistory.addLast(sample.toFloat())
    }
    while (rawHistory.size > FRAME_SAMPLES + HISTORY_SAMPLES) {
      rawHistory.removeFirst()
    }
    if (rawHistory.size < FRAME_SAMPLES + HISTORY_SAMPLES) {
      return 0f
    }

    // Melspectrogram over the newest frame plus its history.
    melInput.clear()
    for (value in rawHistory) {
      melInput.putFloat(value)
    }
    melInput.rewind()
    melOutput.clear()
    melInterpreter.run(melInput, melOutput)
    melOutput.rewind()
    val newRows = melOutputFloats / MEL_BINS
    for (row in 0 until newRows) {
      val melRow = FloatArray(MEL_BINS)
      for (bin in 0 until MEL_BINS) {
        melRow[bin] = melOutput.float / 10f + 2f
      }
      melRows.addLast(melRow)
    }
    while (melRows.size > MEL_MAX_ROWS) {
      melRows.removeFirst()
    }

    // One embedding from the newest MEL_WINDOW mel rows.
    val melList = melRows.toList()
    if (melList.size < MEL_WINDOW) {
      return 0f
    }
    embedInput.clear()
    for (i in melList.size - MEL_WINDOW until melList.size) {
      val row = melList[i]
      for (bin in 0 until MEL_BINS) {
        embedInput.putFloat(row[bin])
      }
    }
    embedInput.rewind()
    embedOutput.clear()
    embedInterpreter.run(embedInput, embedOutput)
    embedOutput.rewind()
    val embedding = FloatArray(EMBED_DIM)
    for (i in 0 until EMBED_DIM) {
      embedding[i] = embedOutput.float
    }
    featureRows.addLast(embedding)
    while (featureRows.size > FEATURE_MAX_ROWS) {
      featureRows.removeFirst()
    }
    if (featureRows.size < CLASSIFIER_FRAMES) {
      return 0f
    }

    // Classifier over the newest CLASSIFIER_FRAMES embeddings.
    val featureList = featureRows.toList()
    classifierInput.clear()
    for (i in featureList.size - CLASSIFIER_FRAMES until featureList.size) {
      val row = featureList[i]
      for (j in 0 until EMBED_DIM) {
        classifierInput.putFloat(row[j])
      }
    }
    classifierInput.rewind()
    classifierOutput.clear()
    classifierInterpreter.run(classifierInput, classifierOutput)
    classifierOutput.rewind()
    return classifierOutput.float
  }

  fun close() {
    melInterpreter.close()
    embedInterpreter.close()
    classifierInterpreter.close()
  }

  private fun floatBuffer(floats: Int): ByteBuffer =
      ByteBuffer.allocateDirect(floats * 4).order(ByteOrder.nativeOrder())

  private fun loadModel(context: Context, assetPath: String): MappedByteBuffer {
    val descriptor = context.assets.openFd(assetPath)
    return descriptor.use {
      FileInputStream(it.fileDescriptor)
          .channel
          .map(FileChannel.MapMode.READ_ONLY, it.startOffset, it.declaredLength)
    }
  }
}
