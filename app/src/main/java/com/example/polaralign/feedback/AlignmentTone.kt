package com.example.polaralign.feedback

import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioTrack
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.pow
import kotlin.math.sin

/** Soft beeps that rise in pitch as the error shrinks, and a short chime when alignment locks. */
class AlignmentTone {
  private val lock = Any()
  private var track: AudioTrack? = null

  @Volatile
  var enabled: Boolean = false
    private set

  private val chime: ShortArray by lazy { renderChime() }

  fun setEnabled(enabled: Boolean) {
    this.enabled = enabled
    if (!enabled) {
      synchronized(lock) { pauseLocked() }
    }
  }

  fun beep(totalErrorDegrees: Double) {
    if (!enabled) return
    play(renderTone(frequencyHz(totalErrorDegrees), BEEP_MS, BEEP_AMPLITUDE))
  }

  fun lockOn() {
    if (!enabled) return
    play(chime)
  }

  fun release() {
    enabled = false
    synchronized(lock) { releaseLocked() }
  }

  private fun play(pcm: ShortArray) {
    synchronized(lock) {
      if (!enabled) return
      val audio = ensureTrack() ?: return
      try {
        if (audio.playState != AudioTrack.PLAYSTATE_PLAYING) audio.play()
        var offset = 0
        while (offset < pcm.size && enabled) {
          val count = minOf(CHUNK_SAMPLES, pcm.size - offset)
          val wrote = audio.write(pcm, offset, count, AudioTrack.WRITE_BLOCKING)
          if (wrote < 0) {
            releaseLocked()
            return
          }
          if (wrote == 0) break
          offset += wrote
        }
      } catch (_: IllegalStateException) {
        releaseLocked()
      }
    }
  }

  private fun ensureTrack(): AudioTrack? {
    track?.let { return it }
    val minBytes =
      AudioTrack.getMinBufferSize(SAMPLE_RATE, AudioFormat.CHANNEL_OUT_MONO, AudioFormat.ENCODING_PCM_16BIT)
    if (minBytes <= 0) return null
    return try {
      AudioTrack.Builder()
        .setAudioAttributes(
          AudioAttributes.Builder()
            .setUsage(AudioAttributes.USAGE_MEDIA)
            .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
            .build()
        )
        .setAudioFormat(
          AudioFormat.Builder()
            .setSampleRate(SAMPLE_RATE)
            .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
            .setChannelMask(AudioFormat.CHANNEL_OUT_MONO)
            .build()
        )
        .setBufferSizeInBytes(maxOf(minBytes, SAMPLE_RATE / 10 * 2))
        .setTransferMode(AudioTrack.MODE_STREAM)
        .build()
        .also { created ->
          track = created
          created.setVolume(TRACK_VOLUME)
        }
    } catch (_: IllegalArgumentException) {
      null
    } catch (_: UnsupportedOperationException) {
      null
    }
  }

  private fun pauseLocked() {
    val audio = track ?: return
    try {
      if (audio.playState == AudioTrack.PLAYSTATE_PLAYING) {
        audio.pause()
        audio.flush()
      }
    } catch (_: IllegalStateException) {
      releaseLocked()
    }
  }

  private fun releaseLocked() {
    val audio = track ?: return
    track = null
    try {
      when (audio.playState) {
        AudioTrack.PLAYSTATE_PLAYING -> {
          audio.pause()
          audio.flush()
        }
        AudioTrack.PLAYSTATE_PAUSED -> audio.flush()
      }
    } catch (_: IllegalStateException) {
      // Already stopped.
    }
    audio.release()
  }

  private fun renderChime(): ShortArray {
    val gap = ShortArray(SAMPLE_RATE * 40 / 1000)
    val notes = doubleArrayOf(523.25, 659.25, 783.99)
    val parts = ArrayList<ShortArray>(notes.size * 2 - 1)
    notes.forEachIndexed { index, hz ->
      parts += renderTone(hz, 120, CHIME_AMPLITUDE)
      if (index != notes.lastIndex) parts += gap
    }
    return concat(parts)
  }

  companion object {
    private const val SAMPLE_RATE = 48_000
    private const val BEEP_MS = 70
    private const val BEEP_AMPLITUDE = 0.4
    private const val CHIME_AMPLITUDE = 0.55
    private const val TRACK_VOLUME = 0.55f
    private const val FADE_MS = 10
    private val CHUNK_SAMPLES = SAMPLE_RATE / 50

    /** 300 Hz at 10° of error, 900 Hz at 1°, on a log curve so each step sounds even. */
    fun frequencyHz(totalErrorDegrees: Double): Double {
      val far = ProximityFeedback.SILENT_BEYOND_DEGREES
      val closeness = ((far - totalErrorDegrees) / (far - 1.0)).coerceIn(0.0, 1.0)
      return 300.0 * 3.0.pow(closeness)
    }

    private fun renderTone(frequencyHz: Double, durationMs: Int, amplitude: Double): ShortArray {
      val count = SAMPLE_RATE * durationMs / 1000
      val fade = (SAMPLE_RATE * FADE_MS / 1000).coerceAtLeast(1)
      val fadeDen = (fade - 1).coerceAtLeast(1)
      val pcm = ShortArray(count)
      for (i in 0 until count) {
        val envelope =
          when {
            i < fade -> 0.5 - 0.5 * cos(PI * i / fadeDen)
            i >= count - fade -> 0.5 - 0.5 * cos(PI * (count - 1 - i) / fadeDen)
            else -> 1.0
          }
        val sample = sin(2.0 * PI * frequencyHz * i / SAMPLE_RATE) * envelope * amplitude
        pcm[i] = (sample * Short.MAX_VALUE).toInt().coerceIn(Short.MIN_VALUE.toInt(), Short.MAX_VALUE.toInt()).toShort()
      }
      return pcm
    }

    private fun concat(parts: List<ShortArray>): ShortArray {
      val out = ShortArray(parts.sumOf { it.size })
      var offset = 0
      for (part in parts) {
        part.copyInto(out, offset)
        offset += part.size
      }
      return out
    }
  }
}
