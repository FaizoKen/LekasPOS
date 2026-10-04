package com.lekaspos.ui.sell

import android.media.AudioManager
import android.media.ToneGenerator
import android.os.Handler
import android.os.HandlerThread

/**
 * Short scanner-style beeps: one for "added", another for "not found". They play on a thread of
 * their own: the tone generator talks to the phone's audio service, which on some phones takes tens
 * of milliseconds per beep — on the main thread every tile tap waited for its beep (D-063).
 */
class Beeper private constructor(private val thread: HandlerThread) {

    private val handler = Handler(thread.looper)

    /** Beeper thread only. Null when the device refuses a tone generator (some OEM audio stacks do). */
    private var tone: ToneGenerator? = null

    init {
        handler.post {
            tone = try {
                ToneGenerator(AudioManager.STREAM_MUSIC, 60)
            } catch (e: RuntimeException) {
                null
            }
        }
    }

    fun ok() {
        handler.post { tone?.startTone(ToneGenerator.TONE_PROP_BEEP, 80) }
    }

    fun error() {
        handler.post { tone?.startTone(ToneGenerator.TONE_PROP_NACK, 200) }
    }

    fun release() {
        handler.post {
            tone?.release()
            tone = null
        }
        thread.quitSafely() // after the release above
    }

    companion object {
        fun create(): Beeper = Beeper(HandlerThread("beeper").apply { start() })
    }
}
