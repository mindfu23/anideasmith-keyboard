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
            SpeechRecognizer.ERROR_CLIENT,
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

    // --- backoff -------------------------------------------------------------------------------

    @Test fun restartBacksOffAsFailuresAccumulate() {
        assertEquals(0L, VoiceSessionPolicy.restartDelayMs(0))
        assertEquals(VoiceSessionPolicy.RESTART_BASE_DELAY_MS.toLong(), VoiceSessionPolicy.restartDelayMs(1))
        assertTrue(VoiceSessionPolicy.restartDelayMs(2) > VoiceSessionPolicy.restartDelayMs(1))
        assertEquals(0L, VoiceSessionPolicy.restartDelayMs(-1)) // never a negative delay
    }
}
