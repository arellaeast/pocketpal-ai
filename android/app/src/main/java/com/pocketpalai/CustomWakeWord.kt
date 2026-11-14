package com.pocketpal

import android.content.Context
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.File
import kotlin.math.sqrt

/**
 * A user-taught wake phrase: a fixed-length template of speech embeddings
 * (see [WakeWordTeacher]) plus the phrase text the user gave it. Stored as
 * a small binary file in app-private storage; the audio it was taught from
 * is never kept.
 */
class CustomWakeWordTemplate(val name: String, val frames: List<FloatArray>) {

  companion object {
    const val TEMPLATE_FRAMES = 12
    private const val MAGIC = 0x5757544D // "WWTM"
    private const val VERSION = 1
    private const val FILE_NAME = "custom_template.bin"
    private const val DIR_NAME = "wakeword"

    private fun file(context: Context): File =
        File(File(context.filesDir, DIR_NAME), FILE_NAME)

    fun exists(context: Context): Boolean = file(context).exists()

    fun load(context: Context): CustomWakeWordTemplate? {
      val source = file(context)
      if (!source.exists()) {
        return null
      }
      return try {
        DataInputStream(source.inputStream().buffered()).use { input ->
          if (input.readInt() != MAGIC || input.readInt() != VERSION) {
            return null
          }
          val nameLength = input.readInt()
          val nameBytes = ByteArray(nameLength)
          input.readFully(nameBytes)
          val frameCount = input.readInt()
          val dim = input.readInt()
          if (frameCount <= 0 || frameCount > 64 || dim != WakeWordPipeline.EMBED_DIM) {
            return null
          }
          val frames =
              List(frameCount) {
                val row = FloatArray(dim)
                for (j in 0 until dim) {
                  row[j] = input.readFloat()
                }
                row
              }
          CustomWakeWordTemplate(String(nameBytes, Charsets.UTF_8), frames)
        }
      } catch (t: Throwable) {
        null
      }
    }

    fun delete(context: Context) {
      file(context).delete()
    }
  }

  fun save(context: Context) {
    val target = file(context)
    target.parentFile?.mkdirs()
    DataOutputStream(target.outputStream().buffered()).use { output ->
      output.writeInt(MAGIC)
      output.writeInt(VERSION)
      val nameBytes = name.toByteArray(Charsets.UTF_8)
      output.writeInt(nameBytes.size)
      output.write(nameBytes)
      output.writeInt(frames.size)
      output.writeInt(WakeWordPipeline.EMBED_DIM)
      for (row in frames) {
        for (value in row) {
          output.writeFloat(value)
        }
      }
    }
  }
}

/** Linear-interpolation resample of an embedding sequence to [target] rows. */
fun resampleEmbeddings(sequence: List<FloatArray>, target: Int): List<FloatArray> {
  if (sequence.isEmpty()) {
    return List(target) { FloatArray(WakeWordPipeline.EMBED_DIM) }
  }
  if (sequence.size == 1) {
    return List(target) { sequence[0].copyOf() }
  }
  val dim = sequence[0].size
  val result = ArrayList<FloatArray>(target)
  for (i in 0 until target) {
    val position = i.toDouble() / (target - 1) * (sequence.size - 1)
    val lower = position.toInt()
    val upper = minOf(lower + 1, sequence.size - 1)
    val fraction = (position - lower).toFloat()
    val row = FloatArray(dim)
    for (j in 0 until dim) {
      row[j] = sequence[lower][j] * (1 - fraction) + sequence[upper][j] * fraction
    }
    result.add(row)
  }
  return result
}

fun cosineSimilarity(a: FloatArray, b: FloatArray): Float {
  var dot = 0.0
  var normA = 0.0
  var normB = 0.0
  for (i in a.indices) {
    dot += a[i] * b[i]
    normA += a[i] * a[i]
    normB += b[i] * b[i]
  }
  val denominator = sqrt(normA) * sqrt(normB)
  return if (denominator == 0.0) 0f else (dot / denominator).toFloat()
}

/** Mean per-frame cosine similarity between two same-length sequences. */
fun sequenceSimilarity(a: List<FloatArray>, b: List<FloatArray>): Float {
  if (a.size != b.size || a.isEmpty()) {
    return 0f
  }
  var total = 0f
  for (i in a.indices) {
    total += cosineSimilarity(a[i], b[i])
  }
  return total / a.size
}

/**
 * Wake-word detection for a taught phrase. Each frame, the newest
 * embeddings are compared against the template at a few window lengths
 * (speaking speed varies) and the best mean cosine similarity is the
 * score. Similarities for unrelated speech sit well below taught-phrase
 * matches in testing, so thresholds around 0.85 separate them.
 */
class CustomWakeWordDetector(context: Context, private val template: CustomWakeWordTemplate) :
    WakeScorer {

  companion object {
    private val WINDOW_LENGTHS = intArrayOf(10, 12, 14, 16)
    private const val MAX_WINDOW = 16
  }

  private val pipeline = WakeWordPipeline(context)
  private val recentEmbeddings = ArrayDeque<FloatArray>()

  override fun feed(frame: ShortArray): Float {
    val embedding = pipeline.feed(frame)
    recentEmbeddings.addLast(embedding)
    while (recentEmbeddings.size > MAX_WINDOW) {
      recentEmbeddings.removeFirst()
    }
    if (recentEmbeddings.size < WINDOW_LENGTHS[0]) {
      return 0f
    }
    val all = recentEmbeddings.toList()
    var best = 0f
    for (length in WINDOW_LENGTHS) {
      if (all.size < length) {
        continue
      }
      val window = resampleEmbeddings(all.subList(all.size - length, all.size), template.frames.size)
      val score = sequenceSimilarity(window, template.frames)
      if (score > best) {
        best = score
      }
    }
    return best
  }

  override fun close() {
    pipeline.close()
  }
}
