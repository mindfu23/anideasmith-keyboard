// SPDX-License-Identifier: GPL-3.0-only
package helium314.keyboard.latin.voice

import android.speech.SpeechRecognizer

/**
 * The decisions a dictation session makes, kept apart from the machinery that carries them out so
 * they can be unit tested. Nothing here touches a recognizer, a handler or a view.
 */
internal object VoiceSessionPolicy {

    /** Utterances that produce no speech before dictation gives up and releases the microphone. */
    const val MAX_CONSECUTIVE_ERRORS = 3

    /** Long-form runs until stopped: long pauses are normal when composing prose. */
    const val NO_ERROR_CAP = Int.MAX_VALUE

    /** Backstop for a forgotten session. From the last result, so one in use never expires. */
    const val LONG_FORM_IDLE_TIMEOUT_MS = 5 * 60 * 1000L

    /** Multiplied by the consecutive error count, so a failing engine backs off. */
    const val RESTART_BASE_DELAY_MS = 250

    /** Ceiling for that backoff, so long-form dictation stays responsive after a long silence. */
    const val MAX_RESTART_DELAY_MS = 2000L

    /**
     * Floor for the backoff. A result resets the failure count, so without this the restart is
     * posted with no delay and lands while the engine is still tearing down — answered with
     * ERROR_CLIENT.
     */
    const val MIN_RESTART_DELAY_MS = 120L

    /** ERROR_CLIENT is usually transient, but needs its own cap since long-form lifts the other. */
    const val MAX_CLIENT_ERRORS = 3

    /**
     * Added per client error. Needed because client errors do not raise the silence count, so the
     * backoff would otherwise stay at its floor and spend every retry in under half a second.
     */
    const val CLIENT_RETRY_DELAY_MS = 400L

    /**
     * How long after our own write a selection update may still be an echo of it. Updates arrive
     * asynchronously and several writes can land in one frame, so a lagging one no longer matches
     * the connection's expected position and looks like the user moving the caret.
     */
    const val WRITE_SETTLE_MS = 500L

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
     * The chosen engine cannot serve this language. Reachable on a healthy device, because
     * [SpeechRecognizer.isOnDeviceRecognitionAvailable] is true whenever the on-device engine
     * exists, downloaded model or not.
     */
    fun isLanguageUnavailable(error: Int) =
        error == SpeechRecognizer.ERROR_LANGUAGE_UNAVAILABLE || error == SpeechRecognizer.ERROR_LANGUAGE_NOT_SUPPORTED

    @JvmOverloads
    fun restartDelayMs(consecutiveErrors: Int, clientErrors: Int = 0): Long =
        (RESTART_BASE_DELAY_MS.toLong() * consecutiveErrors + CLIENT_RETRY_DELAY_MS * clientErrors)
            .coerceIn(MIN_RESTART_DELAY_MS, MAX_RESTART_DELAY_MS)

    /** @param isActive the user still wants dictation, not merely that the recognizer is listening */
    fun onError(
        error: Int,
        cancelling: Boolean,
        isActive: Boolean,
        consecutiveErrors: Int,
        usingOnDevice: Boolean,
        retriedOnline: Boolean,
        maxConsecutiveErrors: Int = MAX_CONSECUTIVE_ERRORS,
        clientErrors: Int = 0,
        msSinceLastResult: Long = 0
    ): ErrorAction = when {
        cancelling -> ErrorAction.IGNORE
        // nothing recognised for a long time: the session has been forgotten, not paused
        isActive && msSinceLastResult >= LONG_FORM_IDLE_TIMEOUT_MS -> ErrorAction.TERMINAL
        isActive && isRestartable(error) && consecutiveErrors < maxConsecutiveErrors -> ErrorAction.RESTART
        // transient: the engine was asked to listen again too soon, not a broken session
        isActive && error == SpeechRecognizer.ERROR_CLIENT && clientErrors < MAX_CLIENT_ERRORS -> ErrorAction.RESTART
        isActive && usingOnDevice && !retriedOnline && isLanguageUnavailable(error) -> ErrorAction.RETRY_ONLINE
        else -> ErrorAction.TERMINAL
    }

    /**
     * The engine can report more than one final per utterance (a spoken "period" is enough), and
     * restarting for each races two startListening calls — the loser gets ERROR_RECOGNIZER_BUSY.
     */
    fun shouldRestartAfterResult(isActive: Boolean, cancelling: Boolean, restartPending: Boolean) =
        isActive && !cancelling && !restartPending

    /** A cursor move we caused is not a reason to stop; one the user made is. */
    fun cursorMoveEndsDictation(msSinceOwnWrite: Long) = msSinceOwnWrite >= WRITE_SETTLE_MS
}
