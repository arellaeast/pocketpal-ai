package com.pocketpal

import android.content.Context
import kotlin.math.sqrt

/**
 * Teaches a custom wake phrase from spoken takes.
 *
 * Each take is run through the embedding pipeline, trimmed to its speech
 * by frame energy, and resampled to the fixed template length. Takes that
 * are too quiet, too short or long, or that sound unlike the takes before
 * them are rejected with a reason the UI can show. The finished template
 * is the per-frame mean of the accepted takes; the take audio itself is
 * discarded as soon as it has been analyzed.
 */
object WakeWordTeacher {

  enum class RejectReason {
    TOO_QUIET,
    TOO_SHORT,
    TOO_LONG,
    OUTLIER,
  }

  sealed interface TakeResult {
    data class Accepted(val count: Int) : TakeResult
    data class Rejected(val reason: RejectReason, val count: Int) : TakeResult
  }

  const val TARGET_TAKES = 15
  const val MIN_TAKES = 10

  private const val PAD_SAMPLES = 8000 // 0.5 s of silence before each take
  private const val MIN_SPEECH_FRAMES = 4
  private const val MAX_SPEECH_FRAMES = 40
  private const val MIN_PEAK_RMS = 1200.0 // int16 scale; a near-whisper fails
  private const val ACTIVE_FRACTION = 0.18
  private const val OUTLIER_MIN_SIMILARITY = 0.80f

  private var pipeline: WakeWordPipeline? = null
  private val takeTemplates = ArrayList<List<FloatArray>>()

  val acceptedCount: Int
    get() = takeTemplates.size

  @Synchronized
  fun begin(context: Context) {
    cancel()
    pipeline = WakeWordPipeline(context.applicationContext)
  }

  @Synchronized
  fun cancel() {
    pipeline?.close()
    pipeline = null
    takeTemplates.clear()
  }

  @Synchronized
  fun analyzeTake(context: Context, pcm: ShortArray): TakeResult {
    val activePipeline =
        pipeline ?: WakeWordPipeline(context.applicationContext).also { pipeline = it }

    // Pad with silence so the pipeline is warm when the speech starts.
    val padded = ShortArray(PAD_SAMPLES + pcm.size)
    pcm.copyInto(padded, PAD_SAMPLES)

    val embeddings = ArrayList<FloatArray>()
    val frameRms = ArrayList<Double>()
    var offset = 0
    while (offset + WakeWordPipeline.FRAME_SAMPLES <= padded.size) {
      val frame = padded.copyOfRange(offset, offset + WakeWordPipeline.FRAME_SAMPLES)
      offset += WakeWordPipeline.FRAME_SAMPLES
      embeddings.add(activePipeline.feed(frame))
      var sumSquares = 0.0
      for (sample in frame) {
        sumSquares += sample.toDouble() * sample.toDouble()
      }
      frameRms.add(sqrt(sumSquares / frame.size))
    }

    val peak = frameRms.maxOrNull() ?: 0.0
    if (peak < MIN_PEAK_RMS) {
      return TakeResult.Rejected(RejectReason.TOO_QUIET, takeTemplates.size)
    }
    val activeIndices = frameRms.indices.filter { frameRms[it] > ACTIVE_FRACTION * peak }
    if (activeIndices.isEmpty()) {
      return TakeResult.Rejected(RejectReason.TOO_SHORT, takeTemplates.size)
    }
    val from = maxOf(0, activeIndices.first() - 1)
    val to = minOf(embeddings.size, activeIndices.last() + 2)
    val speechFrames = to - from
    if (speechFrames < MIN_SPEECH_FRAMES) {
      return TakeResult.Rejected(RejectReason.TOO_SHORT, takeTemplates.size)
    }
    if (speechFrames > MAX_SPEECH_FRAMES) {
      return TakeResult.Rejected(RejectReason.TOO_LONG, takeTemplates.size)
    }

    val takeTemplate =
        resampleEmbeddings(embeddings.subList(from, to), CustomWakeWordTemplate.TEMPLATE_FRAMES)

    if (takeTemplates.isNotEmpty()) {
      val runningMean = meanOf(takeTemplates)
      if (sequenceSimilarity(takeTemplate, runningMean) < OUTLIER_MIN_SIMILARITY) {
        return TakeResult.Rejected(RejectReason.OUTLIER, takeTemplates.size)
      }
    }

    takeTemplates.add(takeTemplate)
    return TakeResult.Accepted(takeTemplates.size)
  }

  /** Build the template from the accepted takes and persist it. */
  @Synchronized
  fun finish(context: Context, name: String): Boolean {
    if (takeTemplates.size < MIN_TAKES) {
      return false
    }
    val template = CustomWakeWordTemplate(name, meanOf(takeTemplates))
    template.save(context.applicationContext)
    cancel()
    return true
  }

  private fun meanOf(templates: List<List<FloatArray>>): List<FloatArray> {
    val frameCount = templates[0].size
    val dim = WakeWordPipeline.EMBED_DIM
    return List(frameCount) { frameIndex ->
      val row = FloatArray(dim)
      for (template in templates) {
        val source = template[frameIndex]
        for (j in 0 until dim) {
          row[j] += source[j]
        }
      }
      for (j in 0 until dim) {
        row[j] /= templates.size
      }
      row
    }
  }
}
