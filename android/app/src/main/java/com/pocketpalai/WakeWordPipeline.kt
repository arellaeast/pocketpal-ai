package com.pocketpal

import android.content.Context
import org.tensorflow.lite.Interpreter
import java.io.FileInputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.MappedByteBuffer
import java.nio.channels.FileChannel

/** Anything that scores one 1280-sample audio frame in [0, 1]. */
interface WakeScorer {
  fun feed(frame: ShortArray): Float
  fun close()
}

/**
 * The shared front end of the openWakeWord model family: a TFLite
 * melspectrogram model and Google's speech-embedding model (Apache-2.0).
 *
 * Audio is consumed one 1280-sample frame (80 ms of 16 kHz mono PCM) at a
 * time. Each frame runs the melspectrogram over the newest frame plus 480
 * samples of history, appends the resulting mel frames (scaled by /10 + 2)
 * to a rolling buffer, and produces one 96-dim embedding from the newest
 * 76 mel frames. Every frame yields an embedding; the first frames of a
 * stream are warm-up (the mel buffer starts as ones, like the reference
 * preprocessor) and should not be trusted for decisions.
 */
class WakeWordPipeline(context: Context) {

  companion object {
    const val SAMPLE_RATE = 16000
    const val FRAME_SAMPLES = 1280
    const val EMBED_DIM = 96

    private const val HISTORY_SAMPLES = 480
    private const val MEL_BINS = 32
    private const val MEL_WINDOW = 76
    private const val MEL_MAX_ROWS = 970

    private const val MEL_ASSET = "wakeword/melspectrogram.tflite"
    private const val EMBED_ASSET = "wakeword/embedding_model.tflite"
  }

  private val melInterpreter: Interpreter
  private val embedInterpreter: Interpreter
  private val melOutputFloats: Int

  /** Rolling raw audio history as floats (int16 values), newest last. */
  private val rawHistory = ArrayDeque<Float>()

  /** Mel rows of [MEL_BINS] floats, newest last; warm-started with ones. */
  private val melRows = ArrayDeque<FloatArray>()

  private val melInput: ByteBuffer
  private val melOutput: ByteBuffer
  private val embedInput: ByteBuffer = floatBuffer(MEL_WINDOW * MEL_BINS)
  private val embedOutput: ByteBuffer = floatBuffer(EMBED_DIM)

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

    repeat(MEL_WINDOW) { melRows.addLast(FloatArray(MEL_BINS) { 1.0f }) }
  }

  /** Feed one frame; returns the newest 96-dim embedding. */
  @Synchronized
  fun feed(frame: ShortArray): FloatArray {
    for (sample in frame) {
      rawHistory.addLast(sample.toFloat())
    }
    while (rawHistory.size > FRAME_SAMPLES + HISTORY_SAMPLES) {
      rawHistory.removeFirst()
    }

    melInput.clear()
    if (rawHistory.size < FRAME_SAMPLES + HISTORY_SAMPLES) {
      // Start of stream: front-pad with silence so every frame emits.
      repeat(FRAME_SAMPLES + HISTORY_SAMPLES - rawHistory.size) { melInput.putFloat(0f) }
    }
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

    val melList = melRows.toList()
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
    return embedding
  }

  fun close() {
    melInterpreter.close()
    embedInterpreter.close()
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
