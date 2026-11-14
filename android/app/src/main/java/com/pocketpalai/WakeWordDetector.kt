package com.pocketpal

import android.content.Context
import org.tensorflow.lite.Interpreter
import java.io.FileInputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.MappedByteBuffer
import java.nio.channels.FileChannel

/** A built-in wake phrase, backed by a trained classifier model. */
data class WakePhrase(val key: String, val label: String, val classifierAsset: String)

object WakePhrases {
  const val CUSTOM_KEY = "custom"

  val ALL: List<WakePhrase> =
      listOf(
          WakePhrase("hey_jarvis", "Hey Jarvis", "wakeword/hey_jarvis_v0.1.tflite"),
          WakePhrase("alexa", "Alexa", "wakeword/alexa_v0.1.tflite"),
          WakePhrase("hey_mycroft", "Hey Mycroft", "wakeword/hey_mycroft_v0.1.tflite"),
      )

  fun byKey(key: String): WakePhrase = ALL.firstOrNull { it.key == key } ?: ALL[0]
}

/**
 * Wake-word detection for the built-in phrases: a [WakeWordPipeline]
 * front end plus the phrase's classifier model (openWakeWord family,
 * Apache-2.0), which scores the newest 16 embeddings once per frame.
 */
class WakeWordDetector(context: Context, phrase: WakePhrase) : WakeScorer {

  companion object {
    private const val FEATURE_MAX_ROWS = 120
    private const val CLASSIFIER_FRAMES = 16
  }

  private val pipeline = WakeWordPipeline(context)
  private val classifierInterpreter: Interpreter
  private val featureRows = ArrayDeque<FloatArray>()
  private val classifierInput: ByteBuffer =
      ByteBuffer.allocateDirect(CLASSIFIER_FRAMES * WakeWordPipeline.EMBED_DIM * 4)
          .order(ByteOrder.nativeOrder())
  private val classifierOutput: ByteBuffer =
      ByteBuffer.allocateDirect(4).order(ByteOrder.nativeOrder())

  init {
    val appContext = context.applicationContext
    classifierInterpreter =
        Interpreter(
            loadModel(appContext, phrase.classifierAsset),
            Interpreter.Options().setNumThreads(2),
        )
    classifierInterpreter.allocateTensors()
  }

  override fun feed(frame: ShortArray): Float {
    val embedding = pipeline.feed(frame)
    featureRows.addLast(embedding)
    while (featureRows.size > FEATURE_MAX_ROWS) {
      featureRows.removeFirst()
    }
    if (featureRows.size < CLASSIFIER_FRAMES) {
      return 0f
    }
    val featureList = featureRows.toList()
    classifierInput.clear()
    for (i in featureList.size - CLASSIFIER_FRAMES until featureList.size) {
      val row = featureList[i]
      for (j in row.indices) {
        classifierInput.putFloat(row[j])
      }
    }
    classifierInput.rewind()
    classifierOutput.clear()
    classifierInterpreter.run(classifierInput, classifierOutput)
    classifierOutput.rewind()
    return classifierOutput.float
  }

  override fun close() {
    pipeline.close()
    classifierInterpreter.close()
  }

  private fun loadModel(context: Context, assetPath: String): MappedByteBuffer {
    val descriptor = context.assets.openFd(assetPath)
    return descriptor.use {
      FileInputStream(it.fileDescriptor)
          .channel
          .map(FileChannel.MapMode.READ_ONLY, it.startOffset, it.declaredLength)
    }
  }
}
