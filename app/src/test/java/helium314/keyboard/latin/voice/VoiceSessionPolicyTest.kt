// SPDX-License-Identifier: GPL-3.0-only
package helium314.keyboard.latin.voice

import android.speech.SpeechRecognizer
import helium314.keyboard.latin.voice.VoiceSessionPolicy.ErrorAction
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Regression tests for the dictation session's decisions. Each case here corresponds to something
 * that was found by hand on a phone, which is a slow and unreliable way to find them again.
 */
@RunWith(RobolectricTestRunner::class)
class VoiceSessionPolicyTest {

    private fun onError(
        error: Int,
        cancelling: Boolean = false,
        isActive: Boolean = true,
        consecutiveErrors: Int = 0,
        usingOnDevice: Boolean = false,
        retriedOnline: Boolean = false
    ) = VoiceSessionPolicy.onError(error, cancelling, isActive, consecutiveErrors, usingOnDevice, retriedOnline)

    // --- restart loop, the basis of continuous dictation -------------------------------------

    @Test fun emptyUtterancesRestartRatherThanEndingDictation() {
        assertEquals(ErrorAction.RESTART, onError(SpeechRecognizer.ERROR_NO_MATCH))
        assertEquals(ErrorAction.RESTART, onError(SpeechRecognizer.ERROR_SPEECH_TIMEOUT))
    }

    @Test fun silenceEventuallyReleasesTheMicrophone() {
        // walking away from the phone must not leave it listening forever
        for (n in 0 until VoiceSessionPolicy.MAX_CONSECUTIVE_ERRORS)
            assertEquals(ErrorAction.RESTART, onError(SpeechRecognizer.ERROR_NO_MATCH, consecutiveErrors = n))
        assertEquals(
            ErrorAction.TERMINAL,
            onError(SpeechRecognizer.ERROR_NO_MATCH, consecutiveErrors = VoiceSessionPolicy.MAX_CONSECUTIVE_ERRORS)
        )
    }

    @Test fun stoppedSessionDoesNotRestart() {
        // stop() clears isActive; the in-flight utterance still reports but nothing may resume
        assertEquals(ErrorAction.TERMINAL, onError(SpeechRecognizer.ERROR_NO_MATCH, isActive = false))
    }

    @Test fun deliberateTeardownReportsNothing() {
        assertEquals(ErrorAction.IGNORE, onError(SpeechRecognizer.ERROR_CLIENT, cancelling = true))
        assertEquals(ErrorAction.IGNORE, onError(SpeechRecognizer.ERROR_NO_MATCH, cancelling = true))
    }

    @Test fun fatalErrorsEndTheSession() {
        listOf(
            SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS,
            SpeechRecognizer.ERROR_RECOGNIZER_BUSY,
            SpeechRecognizer.ERROR_NETWORK,
            SpeechRecognizer.ERROR_SERVER
        ).forEach { assertEquals(ErrorAction.TERMINAL, onError(it), "error $it should be terminal") }
    }

    // --- the on-device fallback ---------------------------------------------------------------

    @Test fun onDeviceWithNoModelFallsBackOnceOnly() {
        assertEquals(
            ErrorAction.RETRY_ONLINE,
            onError(SpeechRecognizer.ERROR_LANGUAGE_UNAVAILABLE, usingOnDevice = true)
        )
        // the retry is spent; a second failure must not loop
        assertEquals(
            ErrorAction.TERMINAL,
            onError(SpeechRecognizer.ERROR_LANGUAGE_UNAVAILABLE, usingOnDevice = true, retriedOnline = true)
        )
    }

    @Test fun generalRecognizerDoesNotFallBackToItself() {
        assertEquals(
            ErrorAction.TERMINAL,
            onError(SpeechRecognizer.ERROR_LANGUAGE_UNAVAILABLE, usingOnDevice = false)
        )
    }

    // --- the double-final race, i.e. the spoken "period" bug ----------------------------------

    @Test fun onlyOneRestartMayBeInFlight() {
        // the engine can report two finals for one utterance; restarting for both raced two
        // startListening calls and the loser got ERROR_RECOGNIZER_BUSY, ending dictation
        assertTrue(VoiceSessionPolicy.shouldRestartAfterResult(isActive = true, cancelling = false, restartPending = false))
        assertFalse(VoiceSessionPolicy.shouldRestartAfterResult(isActive = true, cancelling = false, restartPending = true))
    }

    @Test fun resultsAfterStopOrCancelDoNotRestart() {
        assertFalse(VoiceSessionPolicy.shouldRestartAfterResult(isActive = false, cancelling = false, restartPending = false))
        assertFalse(VoiceSessionPolicy.shouldRestartAfterResult(isActive = true, cancelling = true, restartPending = false))
    }

    // --- restarting too fast after a result, i.e. the fast-talking stop ------------------------

    @Test fun restartIsNeverImmediate() {
        // a result resets the failure count, so without a floor the next startListening is posted
        // with no delay and lands while the engine is still tearing down the last utterance,
        // which it answers with ERROR_CLIENT
        assertEquals(VoiceSessionPolicy.MIN_RESTART_DELAY_MS, VoiceSessionPolicy.restartDelayMs(0))
        assertTrue(VoiceSessionPolicy.restartDelayMs(0) > 0)
    }

    @Test fun clientErrorIsTransientAndRetried() {
        assertEquals(ErrorAction.RESTART, onError(SpeechRecognizer.ERROR_CLIENT))
        assertEquals(ErrorAction.RESTART,
            VoiceSessionPolicy.onError(SpeechRecognizer.ERROR_CLIENT, false, true, 0, false, false,
                VoiceSessionPolicy.MAX_CONSECUTIVE_ERRORS, VoiceSessionPolicy.MAX_CLIENT_ERRORS - 1))
    }

    @Test fun aBrokenEngineStillGivesUp() {
        // long-form lifts the silence cap, so client errors need their own ceiling or a dead
        // engine would be retried forever
        assertEquals(ErrorAction.TERMINAL,
            VoiceSessionPolicy.onError(SpeechRecognizer.ERROR_CLIENT, false, true, 0, false, false,
                VoiceSessionPolicy.NO_ERROR_CAP, VoiceSessionPolicy.MAX_CLIENT_ERRORS))
    }

    @Test fun clientErrorRetriesBackOffEvenWhenSilenceCountIsZero() {
        // a result resets consecutiveErrors, so a delay derived from it alone left the three
        // client retries spent in under half a second, hammering an engine that said "not ready"
        val floorOnly = VoiceSessionPolicy.restartDelayMs(0, 0)
        assertTrue(VoiceSessionPolicy.restartDelayMs(0, 1) > floorOnly)
        assertTrue(VoiceSessionPolicy.restartDelayMs(0, 2) > VoiceSessionPolicy.restartDelayMs(0, 1))
    }

    // --- long-form still stops eventually ------------------------------------------------------

    @Test fun longFormGivesUpAfterLongIdleness() {
        // a forgotten session must not hold the microphone open indefinitely
        assertEquals(ErrorAction.TERMINAL,
            VoiceSessionPolicy.onError(SpeechRecognizer.ERROR_NO_MATCH, false, true, 0, false, false,
                VoiceSessionPolicy.NO_ERROR_CAP, 0, VoiceSessionPolicy.LONG_FORM_IDLE_TIMEOUT_MS))
    }

    @Test fun longFormKeepsGoingWhileItIsStillProducingText() {
        // measured from the last result, so a session in use never expires
        assertEquals(ErrorAction.RESTART,
            VoiceSessionPolicy.onError(SpeechRecognizer.ERROR_NO_MATCH, false, true, 0, false, false,
                VoiceSessionPolicy.NO_ERROR_CAP, 0, VoiceSessionPolicy.LONG_FORM_IDLE_TIMEOUT_MS - 1))
    }

    // --- our own writes must not read as the user moving the caret -------------------------

    @Test fun echoesOfOurOwnWritesDoNotEndDictation() {
        // saying "exclamation point" is a 17-char partial resolving to a 1-char final; the
        // selection updates lag behind and the late one looked like the user moving the caret
        assertFalse(VoiceSessionPolicy.cursorMoveEndsDictation(0))
        assertFalse(VoiceSessionPolicy.cursorMoveEndsDictation(VoiceSessionPolicy.WRITE_SETTLE_MS - 1))
    }

    @Test fun aLaterCursorMoveStillEndsDictation() {
        // tapping elsewhere in the text is a real reason to stop
        assertTrue(VoiceSessionPolicy.cursorMoveEndsDictation(VoiceSessionPolicy.WRITE_SETTLE_MS))
        assertTrue(VoiceSessionPolicy.cursorMoveEndsDictation(10_000))
    }

    // --- long-form lifts the cap --------------------------------------------------------------

    @Test fun longFormNeverGivesUpOnSilence() {
        assertEquals(
            ErrorAction.RESTART,
            onError(SpeechRecognizer.ERROR_NO_MATCH, consecutiveErrors = 500) // long silence
                .let { VoiceSessionPolicy.onError(SpeechRecognizer.ERROR_NO_MATCH, false, true, 500, false, false, VoiceSessionPolicy.NO_ERROR_CAP) }
        )
    }

    @Test fun backoffIsCappedSoLongFormStaysResponsive() {
        assertEquals(VoiceSessionPolicy.MAX_RESTART_DELAY_MS, VoiceSessionPolicy.restartDelayMs(1000))
    }

    // --- backoff -------------------------------------------------------------------------------

    @Test fun restartBacksOffAsFailuresAccumulate() {
        assertEquals(VoiceSessionPolicy.MIN_RESTART_DELAY_MS, VoiceSessionPolicy.restartDelayMs(0))
        assertTrue(VoiceSessionPolicy.restartDelayMs(2) > VoiceSessionPolicy.restartDelayMs(1))
        assertEquals(VoiceSessionPolicy.MIN_RESTART_DELAY_MS, VoiceSessionPolicy.restartDelayMs(-1)) // never negative
    }
}
