package com.benwrk.jabjotjai.monitor

import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioTrack
import android.os.SystemClock
import android.util.Log
import kotlin.math.PI
import kotlin.math.min
import kotlin.math.sin

/**
 * A short tone, played once per beat. It follows the media volume, and must neither interrupt
 * what else is playing nor show up as media playing anywhere, so:
 *
 * - its usage is `USAGE_MEDIA`, which the platform plays on the music stream, the one the media
 *   volume sets (`AudioAttributes`, usage-to-stream mapping, SDK 37 source, read 2026-11-27); its
 *   content type is `CONTENT_TYPE_SONIFICATION`, "a beep or sound effect";
 * - it never requests audio focus. Another app is faded out or ducked only when an app requests
 *   focus; otherwise "the system mixes everything together"
 *   (developer.android.com/media/optimize/audio-focus, read 2026-11-27);
 * - it has no media session. The system's media controls are built from a `MediaSession` and its
 *   `MediaStyle` notification (developer.android.com/media/implement/surfaces/mobile, read
 *   2026-11-27).
 *
 * Its calls block while the audio server answers: on the main thread they held up a frame at every
 * beat (one frame of about 69 ms per beat, 2026-11-27), so it is used from a background thread.
 *
 * @throws UnsupportedOperationException from the constructor if the phone cannot open the track
 */
class BeatBeeper : AutoCloseable {

    private val track: AudioTrack = AudioTrack.Builder()
        .setAudioAttributes(
            AudioAttributes.Builder()
                .setUsage(AudioAttributes.USAGE_MEDIA)
                .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                .build(),
        )
        .setAudioFormat(
            AudioFormat.Builder()
                .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                .setSampleRate(SAMPLE_RATE_HZ)
                .setChannelMask(AudioFormat.CHANNEL_OUT_MONO)
                .build(),
        )
        // A static track holds the whole tone, so each beep only rewinds and replays it.
        .setTransferMode(AudioTrack.MODE_STATIC)
        .setBufferSizeInBytes(TONE.size * Short.SIZE_BYTES)
        .build()
        .also { it.write(TONE, 0, TONE.size) }

    private var reportedSlow = false

    /**
     * Plays the tone from its start, cutting off the previous one if it is still sounding. The first
     * beep that is slow to start is logged; on the test phone every one took 72 to 86 ms
     * (2026-11-27), so logging each would fill the log all night.
     */
    fun beep() {
        val startNs = SystemClock.elapsedRealtimeNanos()
        if (track.playState != AudioTrack.PLAYSTATE_STOPPED) track.stop()
        track.reloadStaticData()
        track.play()
        val tookMs = (SystemClock.elapsedRealtimeNanos() - startNs) / 1_000_000
        if (tookMs > SLOW_BEEP_MS && !reportedSlow) {
            reportedSlow = true
            Log.w(TAG, "starting a beep took $tookMs ms, so it sounded at least that late; later slow beeps are not logged")
        }
    }

    override fun close() {
        track.release()
    }

    private companion object {
        const val TAG = "BeatBeeper"
        const val SLOW_BEEP_MS = 20L
        const val SAMPLE_RATE_HZ = 48_000

        // The owner's chosen recipe for a monitor beep (2026-11-27): a sine at 880 Hz, held at full
        // level, with a short attack so it does not click and a slightly longer release so its
        // tail stays clean. By ear on the test phone, the same pitch as a 60 ms tone with 5 ms
        // fades sounded like a "bloop", and 1 kHz and 1.5 kHz with an added third harmonic too
        // high.
        const val TONE_HZ = 880.0
        const val TONE_MS = 80
        const val ATTACK_MS = 5
        const val RELEASE_MS = 15

        /** The tone's peak, as a share of full scale. */
        const val PEAK = 0.5

        val TONE: ShortArray = run {
            val samples = SAMPLE_RATE_HZ * TONE_MS / 1_000
            val attack = SAMPLE_RATE_HZ * ATTACK_MS / 1_000
            val release = SAMPLE_RATE_HZ * RELEASE_MS / 1_000
            ShortArray(samples) { i ->
                val envelope = min(1.0, min(i.toDouble() / attack, (samples - 1 - i).toDouble() / release))
                (sin(2 * PI * TONE_HZ * i / SAMPLE_RATE_HZ) * envelope * PEAK * Short.MAX_VALUE).toInt().toShort()
            }
        }
    }
}
