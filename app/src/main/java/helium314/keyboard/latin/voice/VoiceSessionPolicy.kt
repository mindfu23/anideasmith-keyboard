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

    /**
     * Long-form dictation never gives up on silence — it runs until the user stops it. Thinking for
     * a minute mid-sentence is normal when composing prose, and the ordinary cap exists to stop a
     * forgotten keyboard holding the microphone, which a deliberate mode does not need.
     */
    const val NO_ERROR_CAP = Int.MAX_VALUE

    /** Multiplied by the consecutive error count, so a failing engine backs off. */
    const val RESTART_BASE_DELAY_MS = 250

    /** Ceiling for that backoff, so long-form dictation stays responsive after a long silence. */
    const val MAX_RESTART_DELAY_MS = 2000L

    /**
     * Floor for the backoff. A result resets the failure count, so without this the next
     * startListening is posted with no delay at all and can land while the engine is still
     * tearing down the utterance that just finished — which it answers with ERROR_CLIENT. Dense
     * speech makes that likely, because short utterances land every few hundred milliseconds.
     */
    const val MIN_RESTART_DELAY_MS = 120L

    /**
     * ERROR_CLIENT retries allowed before giving up. Mid-session it is usually the engine being
     * restarted too soon rather than anything fatal, so it is worth retrying — but a genuinely
     * broken engine must not loop forever, and lifting the silence cap in long-form would
     * otherwise let it.
     */
    const val MAX_CLIENT_ERRORS = 3

    /**
     * How long after our own write a cursor update may still be an echo of it.
     *
     * Selection updates arrive asynchronously and we write fast — a partial, its replacement, the
     * finished span and a trailing space can all land inside one frame. A lagging update then no
     * longer matches the connection's expected position and looks like the user moving the caret.
     * Saying "exclamation point", which is a 17-character partial resolving to a 1-character
     * final, was enough to trigger it and end dictation.
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
     * Errors saying the chosen engine cannot serve this language.
     * [SpeechRecognizer.isOnDeviceRecognitionAvailable] reports true whenever the on-device engine
     * exists, even with no downloaded model, so an on-device session can hit these on a device
     * that is otherwise perfectly capable.
     */
    fun isLanguageUnavailable(error: Int) =
        error == SpeechRecognizer.ERROR_LANGUAGE_UNAVAILABLE || error == SpeechRecognizer.ERROR_LANGUAGE_NOT_SUPPORTED

    fun restartDelayMs(consecutiveErrors: Int): Long =
        (RESTART_BASE_DELAY_MS.toLong() * consecutiveErrors).coerceIn(MIN_RESTART_DELAY_MS, MAX_RESTART_DELAY_MS)

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
        retriedOnline: Boolean,
        maxConsecutiveErrors: Int = MAX_CONSECUTIVE_ERRORS,
        clientErrors: Int = 0
    ): ErrorAction = when {
        cancelling -> ErrorAction.IGNORE
        isActive && isRestartable(error) && consecutiveErrors < maxConsecutiveErrors -> ErrorAction.RESTART
        // transient: the engine was asked to listen again too soon, not a broken session
        isActive && error == SpeechRecognizer.ERROR_CLIENT && clientErrors < MAX_CLIENT_ERRORS -> ErrorAction.RESTART
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

    /**
     * Whether a cursor move should end dictation, given how long ago we last wrote into the field.
     * A move we caused is not a reason to stop; a move the user made is.
     */
    fun cursorMoveEndsDictation(msSinceOwnWrite: Long) = msSinceOwnWrite >= WRITE_SETTLE_MS
}
