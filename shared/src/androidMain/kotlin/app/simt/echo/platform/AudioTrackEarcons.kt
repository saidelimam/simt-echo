package app.simt.echo.platform

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioDeviceInfo
import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioTrack
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeoutOrNull

/**
 * Plays an [EarconPcm] clip through an [AudioTrack] (plan W13-09).
 *
 * One short static track per cue: written, played, then released in a
 * `finally` — on completion, timeout or cancellation of [scope] alike — so
 * no native audio resource outlives the cue. Clips are serialised, so two
 * cues in quick succession never talk over each other. Media usage, so the
 * volume follows the media stream, as the freeze §8 rule asks.
 */
internal class AudioTrackEarcons(private val scope: CoroutineScope) {

    private val serial = Mutex()

    fun play(pcm: EarconPcm) {
        val frames = pcm.left.size
        if (frames == 0) return
        scope.launch {
            serial.withLock {
                val data = pcm.interleaved()
                val track = buildTrack(pcm.sampleRateHz, data.size) ?: return@withLock
                try {
                    track.write(data, 0, data.size)
                    track.play()
                    val clipMs = frames * 1000L / pcm.sampleRateHz
                    withTimeoutOrNull(clipMs + TAIL_MS) {
                        while (isActive && track.playbackHeadPosition < frames) delay(POLL_MS)
                    }
                } finally {
                    track.release()
                }
            }
        }
    }

    private fun buildTrack(sampleRateHz: Int, samples: Int): AudioTrack? = try {
        AudioTrack.Builder()
            .setAudioAttributes(
                AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_MEDIA)
                    .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                    .build(),
            )
            .setAudioFormat(
                AudioFormat.Builder()
                    .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                    .setSampleRate(sampleRateHz)
                    .setChannelMask(AudioFormat.CHANNEL_OUT_STEREO)
                    .build(),
            )
            .setBufferSizeInBytes(samples * Short.SIZE_BYTES)
            .setTransferMode(AudioTrack.MODE_STATIC)
            .build()
    } catch (_: UnsupportedOperationException) {
        null
    } catch (_: IllegalArgumentException) {
        null
    }

    private companion object {
        /** Slack after the clip's nominal length before the track is released anyway. */
        const val TAIL_MS = 250L
        const val POLL_MS = 10L
    }
}

private val HEADPHONE_TYPES = setOf(
    AudioDeviceInfo.TYPE_WIRED_HEADSET,
    AudioDeviceInfo.TYPE_WIRED_HEADPHONES,
    AudioDeviceInfo.TYPE_BLUETOOTH_A2DP,
    AudioDeviceInfo.TYPE_USB_HEADSET,
)

/** True when a headset or headphones are an active audio output (input to [EarconGate]). */
internal fun Context.headphonesConnected(): Boolean {
    val manager = getSystemService(Context.AUDIO_SERVICE) as? AudioManager ?: return false
    return manager.getDevices(AudioManager.GET_DEVICES_OUTPUTS).any { it.type in HEADPHONE_TYPES }
}
