package com.lekaspos.ui.sell

import android.media.AudioManager
import android.media.ToneGenerator

/** Short scanner-style beeps: one for "added", another for "not found". */
class Beeper private constructor(private val tone: ToneGenerator) {

    fun ok() {
        tone.startTone(ToneGenerator.TONE_PROP_BEEP, 80)
    }

    fun error() {
        tone.startTone(ToneGenerator.TONE_PROP_NACK, 200)
    }

    fun release() = tone.release()

    companion object {
        /** Null when the device refuses a tone generator (some OEM audio stacks do). */
        fun create(): Beeper? = try {
            Beeper(ToneGenerator(AudioManager.STREAM_MUSIC, 60))
        } catch (e: RuntimeException) {
            null
        }
    }
}
