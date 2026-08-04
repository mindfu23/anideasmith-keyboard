// SPDX-License-Identifier: GPL-3.0-only
package helium314.keyboard.latin.voice

import android.speech.SpeechRecognizer

/**
 * The decisions a dictation session makes, separated from the machinery that carries them out.
 *
 * Every bug this feature has had was a decision bug rather than a wiring bug — restarting twice
 * for one utterance, not stopping when a gesture began, cancelling on a cursor move we caused
 * ourselves — and each one could only be found by talking to a phone. Pure functions can be
 * asserted in the unit test suite instead.
 *
 * Nothing here touches a recognizer, a handler or a view.
 */
internal object VoiceSessionPolicy {

    /** Utterances that produce no speech before dictation gives up and releases the microphone. */
    const val MAX_CONSECUTIVE_ERRORS = 3

    /** Multiplied by the consecutive error count, so a failing engine backs off. */
    const val RESTART_BASE_DELAY_MS = 250

    /** What to do when the recognizer reports an error. */
    enum class ErrorAction {
        /** The utterance was empty; listen again. */
        RESTART,
        /** On-device cannot serve this language; rebuild once against the general recognizer. */
        RETRY_ONLINE,
        /** The session is over; report it. */
        TERMINAL,
        /** We are tearing down deliberately; say nothing. */
        IGNORE
    }

    /** Errors meaning "this utterance had nothing in it", not "dictation is over". */
    fun isRestartable(error: Int) =
        error == SpeechRecognizer.ERROR_NO_MATCH || error == SpeechRecognizer.ERROR_SPEECH_TIMEOUT

    /**
     * Errors saying the chosen engine cannot serve this language.
     * [SpeechRecognizer.isOnDeviceRecognitionAvailable] reports true whenever the on-device engine
     * exists, even with no downloaded model, so an on-device session can hit these on a device
     * that is otherwise perfectly capable.
     */
    fun isLanguageUnavailable(error: Int) =
        error == SpeechRecognizer.ERROR_LANGUAGE_UNAVAILABLE || error == SpeechRecognizer.ERROR_LANGUAGE_NOT_SUPPORTED

    fun restartDelayMs(consecutiveErrors: Int): Long =
        (RESTART_BASE_DELAY_MS.toLong() * consecutiveErrors).coerceAtLeast(0L)

    /**
     * @param cancelling a deliberate teardown is in progress
     * @param isActive the user still wants dictation
     * @param consecutiveErrors utterances so far that produced nothing
     * @param usingOnDevice the failing session was built by the on-device factory
     * @param retriedOnline the one automatic fallback has already been spent
     */
    fun onError(
        error: Int,
        cancelling: Boolean,
        isActive: Boolean,
        consecutiveErrors: Int,
        usingOnDevice: Boolean,
        retriedOnline: Boolean
    ): ErrorAction = when {
        cancelling -> ErrorAction.IGNORE
        isActive && isRestartable(error) && consecutiveErrors < MAX_CONSECUTIVE_ERRORS -> ErrorAction.RESTART
        isActive && usingOnDevice && !retriedOnline && isLanguageUnavailable(error) -> ErrorAction.RETRY_ONLINE
        else -> ErrorAction.TERMINAL
    }

    /**
     * The engine can report more than one final for a single utterance — a spoken "period" is
     * enough. Restarting for each races two startListening calls and the loser gets
     * ERROR_RECOGNIZER_BUSY, which is terminal.
     *
     * @param restartPending a restart is already scheduled or issued but listening has not resumed
     */
    fun shouldRestartAfterResult(isActive: Boolean, cancelling: Boolean, restartPending: Boolean) =
        isActive && !cancelling && !restartPending
}
