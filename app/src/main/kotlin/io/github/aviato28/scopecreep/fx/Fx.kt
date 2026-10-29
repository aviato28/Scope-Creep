package io.github.aviato28.scopecreep.fx

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioTrack
import android.os.Build
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager
import kotlin.math.PI
import kotlin.math.exp
import kotlin.math.sin

/**
 * Small original sounds synthesised in code (no assets) and haptics. Both are off until [init] runs (tests and screenshots never touch
 * the audio or vibrator services) and each has its own switch. Sounds play on the media stream, so the system volume applies, and
 * request no audio focus (they are short transient effects that mix with whatever else is playing).
 */
object Fx {
    @Volatile var sound = true
    @Volatile var haptics = true
    private var ready = false
    private var squelch: AudioTrack? = null
    private var alert: AudioTrack? = null
    private var strikeTone: AudioTrack? = null
    private var vib: Vibrator? = null
    private const val RATE = 22050

    /** Builds the three effects once. Never throws: a device without audio just stays silent. */
    fun init(context: Context) {
        if (ready) return
        ready = true
        try { squelch = track(squelchPcm()); alert = track(alertPcm()); strikeTone = track(strikePcm()) } catch (_: Throwable) {}
        try {
            vib = if (Build.VERSION.SDK_INT >= 31) (context.getSystemService(Context.VIBRATOR_MANAGER_SERVICE) as? VibratorManager)?.defaultVibrator
            else @Suppress("DEPRECATION") context.getSystemService(Context.VIBRATOR_SERVICE) as? Vibrator
        } catch (_: Throwable) {}
    }

    fun release() { for (t in listOf(squelch, alert, strikeTone)) try { t?.release() } catch (_: Throwable) {}; squelch = null; alert = null; strikeTone = null; ready = false }

    /** Radio squelch click on each ATC / pilot transmission. */
    fun radio() = play(squelch)
    /** Soft two-tone alert for a newly predicted conflict. */
    fun conflict() = play(alert)
    /** Low tone and a stronger buzz on a strike. */
    fun strike() { play(strikeTone); buzz(90, 200) }
    /** Light tick on a chip tap. */
    fun tick() = buzz(12, 60)

    private fun play(t: AudioTrack?) {
        if (!sound || t == null) return
        try { t.stop(); t.setPlaybackHeadPosition(0); t.play() } catch (_: Throwable) {}
    }
    private fun buzz(ms: Long, amp: Int) {
        if (!haptics) return
        try { vib?.vibrate(VibrationEffect.createOneShot(ms, amp)) } catch (_: Throwable) {}
    }

    private fun track(pcm: ShortArray): AudioTrack {
        val t = AudioTrack.Builder()
            .setAudioAttributes(AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_GAME).setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION).build())
            .setAudioFormat(AudioFormat.Builder().setEncoding(AudioFormat.ENCODING_PCM_16BIT).setSampleRate(RATE).setChannelMask(AudioFormat.CHANNEL_OUT_MONO).build())
            .setTransferMode(AudioTrack.MODE_STATIC).setBufferSizeInBytes(pcm.size * 2).build()
        t.write(pcm, 0, pcm.size)
        return t
    }

    /** 55 ms: a click, then a burst of band-limited noise with a fast decay (a transmitter keying up). */
    internal fun squelchPcm(): ShortArray {
        val n = RATE * 55 / 1000; val out = ShortArray(n)
        var seed = 12345; var lp = 0.0
        for (i in 0 until n) {
            seed = seed * 1103515245 + 12345
            val noise = ((seed shr 16 and 0x7FFF) / 16384.0 - 1.0)
            lp += 0.55 * (noise - lp)                       // gentle low-pass keeps it from hissing
            val env = exp(-i / (RATE * 0.014))
            val click = if (i < 40) sin(2 * PI * 1800.0 * i / RATE) * (1.0 - i / 40.0) else 0.0
            out[i] = ((lp * env * 0.55 + click * 0.35) * 0.45 * 32767).toInt().toShort()
        }
        return out
    }

    /** Two soft sine tones, 660 Hz then 880 Hz, 90 ms each, with raised-cosine fades. */
    internal fun alertPcm(): ShortArray {
        val seg = RATE * 90 / 1000; val gap = RATE * 25 / 1000; val out = ShortArray(seg * 2 + gap)
        for ((k, f) in listOf(660.0, 880.0).withIndex()) for (i in 0 until seg) {
            val fade = 0.5 - 0.5 * kotlin.math.cos(2 * PI * i / seg)
            out[k * (seg + gap) + i] = (sin(2 * PI * f * i / RATE) * fade * 0.3 * 32767).toInt().toShort()
        }
        return out
    }

    /** Low falling tone, 150 Hz to 105 Hz over 330 ms, decaying. */
    internal fun strikePcm(): ShortArray {
        val n = RATE * 330 / 1000; val out = ShortArray(n); var ph = 0.0
        for (i in 0 until n) {
            val f = 150.0 - 45.0 * i / n
            ph += 2 * PI * f / RATE
            val env = (1.0 - i.toDouble() / n) * (if (i < 200) i / 200.0 else 1.0)
            out[i] = ((sin(ph) + 0.35 * sin(2 * ph)) * env * 0.35 * 32767).toInt().toShort()
        }
        return out
    }
}
