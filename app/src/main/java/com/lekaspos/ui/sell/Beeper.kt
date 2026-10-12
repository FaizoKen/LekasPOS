package com.lekaspos.ui.sell

import android.annotation.TargetApi
import android.content.Context
import android.media.AudioAttributes
import android.media.AudioDeviceInfo
import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioTrack
import android.os.Build
import android.os.Handler
import android.os.HandlerThread
import com.lekaspos.data.settings.DeviceSettings
import kotlin.math.PI
import kotlin.math.min
import kotlin.math.sin
import kotlinx.coroutines.flow.StateFlow

/**
 * Short scanner-style beeps: one for "added", another for "not found". They play on a thread of
 * their own: the audio service on some phones takes tens of milliseconds per beep — on the main
 * thread every tile tap waited for its beep (D-063).
 *
 * Where they play is the till's choice (Settings → Beep sound, D-076): with a customer screen cast by
 * Miracast or on HDMI, Android sends every sound to that screen — often a TV without speakers, so the
 * cashier heard no beep. "This till's speaker" asks for the built-in speaker; "Customer screen" leaves it
 * to Android; "Off" is silent. A ToneGenerator cannot be sent to a speaker, so the beeps are made here,
 * the same tones it played (400 + 1200 Hz for 35 ms; 400 Hz for 200 ms) at the same loudness. Choosing
 * the speaker needs Android 6: on Android 5 the beep plays where Android sends it.
 */
class Beeper private constructor(
    private val context: Context,
    private val device: StateFlow<DeviceSettings>,
    private val thread: HandlerThread,
) {

    private val handler = Handler(thread.looper)

    /** Beeper thread only. Null when the device refuses an audio track (some OEM audio stacks do). */
    private var okTrack: AudioTrack? = null
    private var errorTrack: AudioTrack? = null

    /** Beeper thread only: the built-in speaker (an AudioDeviceInfo, Android 6+), once looked up. */
    private var speaker: Any? = null
    private var speakerLooked = false

    init {
        handler.post {
            okTrack = track(tone(OK_MS, 400, 1200))
            errorTrack = track(tone(ERROR_MS, 400))
        }
    }

    fun ok() {
        handler.post { play(okTrack) }
    }

    fun error() {
        handler.post { play(errorTrack) }
    }

    fun release() {
        handler.post {
            okTrack?.release()
            errorTrack?.release()
            okTrack = null
            errorTrack = null
        }
        thread.quitSafely() // after the release above
    }

    private fun play(t: AudioTrack?) {
        val where = device.value.beep
        if (t == null || where == DeviceSettings.BEEP_OFF) return
        try {
            if (Build.VERSION.SDK_INT >= 23) route(t, where == DeviceSettings.BEEP_TILL)
            t.stop() // from the start again, also when the last one is still playing
            t.reloadStaticData()
            t.play()
        } catch (e: IllegalStateException) {
            Unit // the audio service lost the track (rare): this beep is skipped
        }
    }

    @TargetApi(23)
    private fun route(t: AudioTrack, tillSpeaker: Boolean) {
        val want = if (tillSpeaker) builtInSpeaker() else null
        if (t.preferredDevice?.id != want?.id) t.setPreferredDevice(want)
    }

    @TargetApi(23)
    private fun builtInSpeaker(): AudioDeviceInfo? {
        if (!speakerLooked) {
            speakerLooked = true
            val audio = context.getSystemService(Context.AUDIO_SERVICE) as AudioManager
            speaker = audio.getDevices(AudioManager.GET_DEVICES_OUTPUTS).firstOrNull { it.type == AudioDeviceInfo.TYPE_BUILTIN_SPEAKER }
        }
        return speaker as? AudioDeviceInfo
    }

    private fun track(pcm: ShortArray): AudioTrack? = try {
        val attributes = AudioAttributes.Builder()
            .setUsage(AudioAttributes.USAGE_MEDIA) // the media volume, as the tone generator's music stream
            .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
            .build()
        val format = AudioFormat.Builder()
            .setSampleRate(RATE)
            .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
            .setChannelMask(AudioFormat.CHANNEL_OUT_MONO)
            .build()
        val t = AudioTrack(attributes, format, pcm.size * 2, AudioTrack.MODE_STATIC, AudioManager.AUDIO_SESSION_ID_GENERATE)
        if (t.write(pcm, 0, pcm.size) == pcm.size && t.state == AudioTrack.STATE_INITIALIZED) {
            t
        } else {
            t.release()
            null
        }
    } catch (e: RuntimeException) {
        null
    }

    companion object {
        private const val RATE = 44100
        private const val OK_MS = 35
        private const val ERROR_MS = 200

        /** The tone generator's loudness at 60 % (−20 dB) with its 0.9 gain shared by the frequencies. */
        private const val LOUDNESS = 0.09

        /** [ms] of the [freqs] together, faded in and out over 3 ms so the beep does not click. */
        private fun tone(ms: Int, vararg freqs: Int): ShortArray {
            val n = RATE * ms / 1000
            val fade = RATE * 3 / 1000
            return ShortArray(n) { i ->
                var v = 0.0
                for (f in freqs) v += sin(2 * PI * f * i / RATE)
                val edge = min(1.0, min(i, n - 1 - i).toDouble() / fade)
                (v / freqs.size * LOUDNESS * edge * Short.MAX_VALUE).toInt().toShort()
            }
        }

        fun create(context: Context, device: StateFlow<DeviceSettings>): Beeper =
            Beeper(context.applicationContext, device, HandlerThread("beeper").apply { start() })
    }
}
