// SPDX-License-Identifier: GPL-3.0-only
package helium314.keyboard.latin.voice

import android.speech.SpeechRecognizer
import android.text.InputType
import helium314.keyboard.keyboard.internal.keyboard_parser.floris.KeyCode
import helium314.keyboard.latin.voice.VoiceSessionPolicy.ErrorAction
import helium314.keyboard.latin.voice.VoiceSessionPolicy.ShortFormKeyGroup
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
    ) = VoiceSessionPolicy.onError(
        error = error, cancelling = cancelling, isActive = isActive,
        consecutiveErrors = consecutiveErrors, usingOnDevice = usingOnDevice, retriedOnline = retriedOnline
    )

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
            VoiceSessionPolicy.onError(
                error = SpeechRecognizer.ERROR_CLIENT, cancelling = false, isActive = true,
                consecutiveErrors = 0, usingOnDevice = false, retriedOnline = false,
                clientErrors = VoiceSessionPolicy.MAX_CLIENT_ERRORS - 1))
    }

    @Test fun aBrokenEngineStillGivesUp() {
        // long-form lifts the silence cap, so client errors need their own ceiling or a dead
        // engine would be retried forever
        assertEquals(ErrorAction.TERMINAL,
            VoiceSessionPolicy.onError(
                error = SpeechRecognizer.ERROR_CLIENT, cancelling = false, isActive = true,
                consecutiveErrors = 0, usingOnDevice = false, retriedOnline = false,
                maxConsecutiveErrors = VoiceSessionPolicy.NO_ERROR_CAP,
                clientErrors = VoiceSessionPolicy.MAX_CLIENT_ERRORS))
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
            VoiceSessionPolicy.onError(
                error = SpeechRecognizer.ERROR_NO_MATCH, cancelling = false, isActive = true,
                consecutiveErrors = 0, usingOnDevice = false, retriedOnline = false,
                maxConsecutiveErrors = VoiceSessionPolicy.NO_ERROR_CAP,
                msSinceLastResult = VoiceSessionPolicy.LONG_FORM_IDLE_TIMEOUT_MS))
    }

    @Test fun longFormKeepsGoingWhileItIsStillProducingText() {
        // measured from the last result, so a session in use never expires
        assertEquals(ErrorAction.RESTART,
            VoiceSessionPolicy.onError(
                error = SpeechRecognizer.ERROR_NO_MATCH, cancelling = false, isActive = true,
                consecutiveErrors = 0, usingOnDevice = false, retriedOnline = false,
                maxConsecutiveErrors = VoiceSessionPolicy.NO_ERROR_CAP,
                msSinceLastResult = VoiceSessionPolicy.LONG_FORM_IDLE_TIMEOUT_MS - 1))
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
            VoiceSessionPolicy.onError(
                error = SpeechRecognizer.ERROR_NO_MATCH, cancelling = false, isActive = true,
                consecutiveErrors = 500, usingOnDevice = false, retriedOnline = false,
                maxConsecutiveErrors = VoiceSessionPolicy.NO_ERROR_CAP)
        )
    }

    @Test fun backoffIsCappedSoLongFormStaysResponsive() {
        assertEquals(VoiceSessionPolicy.MAX_RESTART_DELAY_MS, VoiceSessionPolicy.restartDelayMs(1000))
    }

    // --- the engine that answers without listening --------------------------------------------

    private fun onWedge(instantErrors: Int, recoveries: Int = 0) = VoiceSessionPolicy.onError(
        error = SpeechRecognizer.ERROR_NO_MATCH, cancelling = false, isActive = true,
        consecutiveErrors = 0, usingOnDevice = false, retriedOnline = false,
        maxConsecutiveErrors = VoiceSessionPolicy.NO_ERROR_CAP,
        instantErrors = instantErrors, recoveries = recoveries
    )

    @Test fun anErrorTooFastToHaveListenedIsWedged() {
        // measured on a real session: a healthy silence timeout answers in about 5s, a wedged
        // engine in about 100ms, and a refused startListening in about 2ms
        assertTrue(VoiceSessionPolicy.isWedgedError(SpeechRecognizer.ERROR_NO_MATCH, 100))
        assertTrue(VoiceSessionPolicy.isWedgedError(SpeechRecognizer.ERROR_CLIENT, 2))
        assertFalse(VoiceSessionPolicy.isWedgedError(SpeechRecognizer.ERROR_NO_MATCH, 5100))
    }

    @Test fun aFatalErrorIsFatalHoweverFastItArrives() {
        // rebuilding the engine cannot grant a permission or find a network
        assertFalse(VoiceSessionPolicy.isWedgedError(SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS, 1))
        assertFalse(VoiceSessionPolicy.isWedgedError(SpeechRecognizer.ERROR_NETWORK, 1))
    }

    @Test fun oneFastErrorIsNotYetAWedgedEngine() {
        assertEquals(ErrorAction.RESTART, onWedge(instantErrors = 1))
        assertEquals(ErrorAction.RESTART, onWedge(instantErrors = VoiceSessionPolicy.WEDGE_ERROR_COUNT - 1))
    }

    @Test fun aWedgedEngineIsRebuiltRatherThanRestarted() {
        // restarting in place just repeats the same instant failure, as it did for ~20 rounds
        // before the session died
        assertEquals(ErrorAction.RECOVER, onWedge(instantErrors = VoiceSessionPolicy.WEDGE_ERROR_COUNT))
    }

    @Test fun rebuildingIsGivenUpOnEventually() {
        // the engine degraded across a whole morning and no rebuild fixed it; retrying forever
        // would hold the microphone open against a service that answers in 2ms
        assertEquals(
            ErrorAction.TERMINAL,
            onWedge(instantErrors = VoiceSessionPolicy.WEDGE_ERROR_COUNT, recoveries = VoiceSessionPolicy.MAX_RECOVERIES)
        )
    }

    @Test fun aWedgedEngineDoesNotOverrideStoppingOrIdleness() {
        // a deliberate teardown still says nothing, and a forgotten session still expires
        assertEquals(
            ErrorAction.IGNORE,
            VoiceSessionPolicy.onError(
                error = SpeechRecognizer.ERROR_NO_MATCH, cancelling = true, isActive = true,
                consecutiveErrors = 0, usingOnDevice = false, retriedOnline = false,
                instantErrors = VoiceSessionPolicy.WEDGE_ERROR_COUNT)
        )
        assertEquals(
            ErrorAction.TERMINAL,
            VoiceSessionPolicy.onError(
                error = SpeechRecognizer.ERROR_NO_MATCH, cancelling = false, isActive = true,
                consecutiveErrors = 0, usingOnDevice = false, retriedOnline = false,
                maxConsecutiveErrors = VoiceSessionPolicy.NO_ERROR_CAP,
                msSinceLastResult = VoiceSessionPolicy.LONG_FORM_IDLE_TIMEOUT_MS,
                instantErrors = VoiceSessionPolicy.WEDGE_ERROR_COUNT)
        )
    }

    @Test fun coolOffIsLongerThanAnyOrdinaryRestart() {
        // the point of the pause is to be unlike the retries that already failed
        assertTrue(VoiceSessionPolicy.RECOVERY_COOL_OFF_MS > VoiceSessionPolicy.MAX_RESTART_DELAY_MS / 2)
        assertTrue(VoiceSessionPolicy.WEDGED_ERROR_MS < VoiceSessionPolicy.MIN_RESTART_DELAY_MS * 2)
    }

    // --- backoff -------------------------------------------------------------------------------

    @Test fun restartBacksOffAsFailuresAccumulate() {
        assertEquals(VoiceSessionPolicy.MIN_RESTART_DELAY_MS, VoiceSessionPolicy.restartDelayMs(0))
        assertTrue(VoiceSessionPolicy.restartDelayMs(2) > VoiceSessionPolicy.restartDelayMs(1))
        assertEquals(VoiceSessionPolicy.MIN_RESTART_DELAY_MS, VoiceSessionPolicy.restartDelayMs(-1)) // never negative
    }

    // --- streaming into the field ------------------------------------------------------------
    // The engine extends rather than revises: 239 partial-to-partial transitions in one measured
    // session were pure extensions, none rewrote an earlier word. Punctuation is the exception, and
    // only when an utterance is finalised. Fixtures are real pairs captured on the S24.

    private fun agreed(written: String, latest: String) =
        VoiceSessionPolicy.agreedPrefixLength(written, latest)

    @Test fun anExtensionAddsOnlyTheNewWords() {
        val written = " Oh, I get it. This is only being"
        val latest = " Oh, I get it. This is only being written afterwards"
        assertEquals(written.length, agreed(written, latest))
        assertEquals(" written afterwards", latest.substring(agreed(written, latest)))
    }

    @Test fun nothingToDoWhenTheEngineRepeatsItself() {
        val text = " More text."
        assertEquals(text.length, agreed(text, text))
        assertEquals("", text.substring(agreed(text, text)))
    }

    @Test fun aPunctuationRevisionRewritesOnlyTheTail() {
        // the spoken word "period" became "." when the utterance was finalised
        val written = " Okay, that pause happened period now."
        val latest = " Okay, that pause happened."
        val keep = agreed(written, latest)
        assertEquals(" Okay, that pause happened", written.substring(0, keep))
        assertEquals(" period now.", written.substring(keep))
        assertEquals(".", latest.substring(keep))
    }

    @Test fun anOvershootingPartialAgreesUpToTheFinal() {
        // the partial had run into the next sentence; the final covers only the first
        val written = " It would take me a few sentences. This is the short"
        val latest = " It would take me a few sentences."
        assertEquals(latest.length, agreed(written, latest))
        assertEquals(" This is the short", written.substring(latest.length))
    }

    @Test fun freshTextAgreesWithNothing() {
        assertEquals(0, agreed("", "Okay, comma."))
        assertEquals(0, agreed("Something else entirely.", "Okay, comma."))
    }

    @Test fun emptyTextIsSafe() {
        assertEquals(0, agreed("", ""))
        assertEquals(0, agreed("written", ""))
        assertEquals(0, agreed("", "latest"))
    }

    // --- how much to delete, read from the field --------------------------------------------

    private fun inField(seen: String, keep: String, stale: String) =
        VoiceSessionPolicy.staleInField(seen, keep, stale, stale.length + 8)

    @Test fun aDroppedFirstWordIsDeletedWithTheRest() {
        // captured 07:07:36 on the S24: " Don't put." became " Put.", so only the leading space
        // agreed, and searching for the nearest space deleted "put." alone and left "Don't" behind
        val seen = "big black paints in there? Don't put."
        assertEquals(" Don't put.".length - 1, inField(seen, " ", "Don't put."))
    }

    @Test fun theRecordWinsWhenTheFieldAgreesWithIt() {
        val seen = "earlier words. It would take period"
        assertEquals(" period".length, inField(seen, "earlier words. It would take", " period"))
    }

    @Test fun aMarkTheAppRemovedIsStillFound() {
        // the app took the trailing "." itself, so the record is one character long
        val seen = "Okay, that pause happened period now"
        assertEquals(" period now".length, inField(seen, "Okay, that pause happened", " period now."))
    }

    @Test fun textThatIsNotOursIsLeftAlone() {
        assertEquals(-1, inField("something the user typed", "Okay, that pause", " period"))
    }

    // --- the overshoot that was deleted and retyped, i.e. the repeated-text bug ----------------

    private fun stale(written: String, latest: String, finalised: Boolean) =
        VoiceSessionPolicy.staleLength(written, latest, finalised)

    @Test fun aFinalDoesNotTakeBackTheNextUtterance() {
        // captured at 16:26:10 on the S24: the final covered one sentence, the partials had
        // already streamed the next one, and 98 correct characters were deleted and reissued 19ms
        // later. Every such round trip is a chance for the field and our record to disagree.
        val written = "Trade desks are physically located in NYC. They're literally paying billions."
        val latest = "Trade desks are physically located in NYC."
        assertEquals(0, stale(written, latest, finalised = true))
        assertEquals(" They're literally paying billions.", written.substring(latest.length))
    }

    @Test fun aFinalStillRewritesWhatTheEngineRevised() {
        // agreement stops inside the final, so this is a revision and the tail really is stale
        val written = " Okay, that pause happened period now."
        val latest = " Okay, that pause happened."
        assertEquals(written.length - agreed(written, latest), stale(written, latest, finalised = true))
        assertEquals(" period now.", written.substring(agreed(written, latest)))
    }

    @Test fun aPartialStillCorrectsWhatItShortened() {
        // only a final marks the end of an utterance, so mid-utterance a shorter text is a revision
        assertEquals(" and.".length, stale(" It is and.", " It is", finalised = false))
        assertEquals(0, stale(" It is and.", " It is", finalised = true))
    }

    @Test fun anEmptyFinalDeletesNothing() {
        // a final with no text is not an instruction to remove what is already on screen
        assertEquals(0, stale(" Something already written.", "", finalised = true))
    }

    @Test fun nothingIsStaleWhenTheEngineRepeatsItself() {
        val text = " More text."
        assertEquals(0, stale(text, text, finalised = true))
        assertEquals(0, stale(text, text, finalised = false))
    }

    // --- the session that ended while the user was still thinking -----------------------------

    @Test fun shortFormStopsWhenTheEngineSaysTheThoughtIsOver() {
        // one thought at a time is what short form is for, so the engine deciding it has heard the
        // end of one is the same judgement the user would have made
        assertFalse(VoiceSessionPolicy.shouldReopenSegmentedSession(
            isActive = true, longForm = false, msSinceLastResult = 0))
        assertFalse(VoiceSessionPolicy.shouldReopenSegmentedSession(
            isActive = true, longForm = false, msSinceLastResult = 1000))
    }

    @Test fun longFormOutlastsThePausesInProse() {
        // the engine gives up after 6-17s whatever the intent asks for, and prose pauses for longer
        assertTrue(VoiceSessionPolicy.shouldReopenSegmentedSession(
            isActive = true, longForm = true, msSinceLastResult = 0))
        assertTrue(VoiceSessionPolicy.shouldReopenSegmentedSession(
            isActive = true, longForm = true,
            msSinceLastResult = VoiceSessionPolicy.LONG_FORM_IDLE_TIMEOUT_MS - 1))
    }

    @Test fun aForgottenLongFormSessionStillReleasesTheMicrophone() {
        // counting empty sessions could not end this: any recognised word resets the count, and a
        // room with a television in it produces words. Time since real text is the only ceiling.
        assertFalse(VoiceSessionPolicy.shouldReopenSegmentedSession(
            isActive = true, longForm = true,
            msSinceLastResult = VoiceSessionPolicy.LONG_FORM_IDLE_TIMEOUT_MS))
    }

    @Test fun stoppingEndsTheSessionRatherThanReopeningIt() {
        assertFalse(VoiceSessionPolicy.shouldReopenSegmentedSession(
            isActive = false, longForm = true, msSinceLastResult = 0))
    }

    private fun ignores(code: Int, keepOnTab: Boolean = true, groups: Set<ShortFormKeyGroup> = emptySet()) =
        VoiceSessionPolicy.shortFormIgnoresKey(code, keepOnTab, groups)

    @Test fun tabAndShiftTabLeaveShortFormRunningOnlyWhileTheSwitchIsOn() {
        assertTrue(ignores(KeyCode.TAB))
        assertTrue(ignores(KeyCode.TAB_BACK))
        assertFalse(ignores(KeyCode.TAB, keepOnTab = false))
        assertFalse(ignores(KeyCode.TAB_BACK, keepOnTab = false))
    }

    @Test fun anyOtherKeyStillStopsShortFormByDefault() {
        for (code in listOf('a'.code, ' '.code, '\n'.code, '.'.code, KeyCode.DELETE, KeyCode.SHIFT, KeyCode.ARROW_LEFT))
            assertFalse(ignores(code), "code $code")
    }

    @Test fun eachChosenGroupSparesItsKeysAndNoOthers() {
        assertTrue(ignores(' '.code, groups = setOf(ShortFormKeyGroup.SPACE)))
        assertTrue(ignores('\n'.code, groups = setOf(ShortFormKeyGroup.ENTER)))
        assertTrue(ignores(KeyCode.DELETE, groups = setOf(ShortFormKeyGroup.DELETE)))
        assertTrue(ignores(KeyCode.CAPS_LOCK, groups = setOf(ShortFormKeyGroup.SHIFT)))
        assertTrue(ignores(KeyCode.SYMBOL_ALPHA, groups = setOf(ShortFormKeyGroup.LAYOUT_SWITCH)))
        assertTrue(ignores(KeyCode.WORD_RIGHT, groups = setOf(ShortFormKeyGroup.CURSOR)))
        assertTrue(ignores(KeyCode.UNDO, groups = setOf(ShortFormKeyGroup.UNDO_REDO)))
        assertTrue(ignores('?'.code, groups = setOf(ShortFormKeyGroup.PUNCTUATION)))
        // punctuation is not a licence for letters, digits or the space
        for (code in listOf('a'.code, '7'.code, ' '.code, 'é'.code))
            assertFalse(ignores(code, groups = setOf(ShortFormKeyGroup.PUNCTUATION)), "code $code")
    }

    @Test fun theStoredKeyListSurvivesARoundTripAndForgetsUnknownNames() {
        val groups = setOf(ShortFormKeyGroup.CURSOR, ShortFormKeyGroup.SPACE)
        assertEquals(groups, ShortFormKeyGroup.parse(ShortFormKeyGroup.serialize(groups)))
        assertEquals(setOf(ShortFormKeyGroup.ENTER), ShortFormKeyGroup.parse("ENTER;RENAMED_LATER"))
        assertEquals(emptySet(), ShortFormKeyGroup.parse(""))
        assertEquals(emptySet(), ShortFormKeyGroup.parse(null))
    }

    @Test fun onlyAFieldWithNoInputTypeCountsAsLeavingIt() {
        // measured 2026-08-16: a Tab out of a form field restarts input with TYPE_NULL...
        assertTrue(VoiceSessionPolicy.leftTheField(InputType.TYPE_NULL))
        // ...while the same app restarting input on the field it is in keeps its real type
        assertFalse(VoiceSessionPolicy.leftTheField(0x0002C0A1))
        assertFalse(VoiceSessionPolicy.leftTheField(InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_MULTI_LINE))
    }

    // Frozen text against the engine's, with payloads captured in Obsidian on 2026-09-29.

    private fun resumed(frozen: String, engine: String) =
        engine.substring(VoiceSessionPolicy.frozenCover(frozen, engine).engineCovered)

    @Test fun aMarkRevisedBehindTheFreezeDoesNotRewriteTheWordsAfterIt() {
        val frozen = " While I was doing long form dictation, so I don't know if that's in the list of things" +
                " to look at, but that could be. something useful."
        val final = " While I was doing long form dictation, so I don't know if that's in the list of things" +
                " to look at, but that could be something useful."
        assertEquals("", resumed(frozen, final))
    }

    @Test fun wordsAfterTheFrozenTextAreWrittenWithTheirSpace() {
        val frozen = " While I was doing long form dictation, so I don't know if that's in the list of things" +
                " to look at, but that could be."
        val partial = " While I was doing long form dictation, so I don't know if that's in the list of things" +
                " to look at, but that could be something useful."
        assertEquals(" something useful.", resumed(frozen, partial))
    }

    @Test fun aFinalEndingInsideTheFrozenTextCarriesTheRestToTheNextSegment() {
        val frozen = "Starting a test and seeing what happens when I do this. And then I hit tab."
        val final = "Starting a test and seeing what happens when I do this."
        val cover = VoiceSessionPolicy.frozenCover(frozen, final)
        assertEquals("", final.substring(cover.engineCovered))
        val carry = VoiceSessionPolicy.frozenCarryAfterFinal(frozen, cover)
        // the next segment resends the words its predecessor's partials ran into
        assertEquals("", resumed(carry, " And then I hit tab."))
        assertEquals("", resumed(carry, " And then I"))
        assertEquals(" Talking some more.", resumed(carry, " And then I hit tab. Talking some more."))
    }

    @Test fun aSegmentFinalThatMatchesEverythingCarriesNothing() {
        val frozen = "Starting a test."
        assertEquals("", VoiceSessionPolicy.frozenCarryAfterFinal(frozen, VoiceSessionPolicy.frozenCover(frozen, "Starting a test.")))
    }

    @Test fun theUsualCasesStillResumeWhereTheyDid() {
        // plain extension
        assertEquals(" now.", resumed(" This seems to be working.", " This seems to be working now."))
        // a leading space the final gained over its partials
        assertEquals("", resumed("This seems to be working.", " This seems to be working."))
        // a comma's pause replacing the provisional full stop keeps its space (cfe3d20c's case)
        assertEquals(" now.", resumed(" This seems to be working.", " This seems to be working, now."))
        // capitals decided afresh
        assertEquals(" more.", resumed(" something Useful.", " something useful more."))
    }

    @Test fun aRevisedWordBehindTheFreezeResumesAfterTheLastAgreeingWord() {
        val cover = VoiceSessionPolicy.frozenCover(" I want to by it.", " I want to buy it.")
        assertFalse(cover.agrees)
        assertEquals(" buy it.", " I want to buy it.".substring(cover.engineCovered))
        assertEquals("", VoiceSessionPolicy.frozenCarryAfterFinal(" I want to by it.", cover))
    }

    // Option B, chosen 2026-09-29: a freeze for user input drops the partial's provisional mark.

    @Test fun onlyATrailingMarkCountsAsProvisional() {
        assertTrue(VoiceSessionPolicy.endsWithProvisionalMark(" which does some simple calcul."))
        assertTrue(VoiceSessionPolicy.endsWithProvisionalMark(" in this?"))
        assertFalse(VoiceSessionPolicy.endsWithProvisionalMark(" we often, "))
        assertFalse(VoiceSessionPolicy.endsWithProvisionalMark(""))
    }

    /** What is written after a freeze whose provisional mark was dropped, as streamVoiceText does it. */
    private fun resumedAfterDrop(frozenWithoutMark: String, engine: String): String {
        val cover = VoiceSessionPolicy.frozenCover(frozenWithoutMark, engine)
        val text = engine.substring(cover.engineCovered)
        return if (cover.agrees && cover.frozenUsed == frozenWithoutMark.length) VoiceSessionPolicy.dropLeadingMarks(text) else text
    }

    @Test fun aSentenceThatGoesOnAfterTheKeyHasNoStrayMark() {
        // captured: "calcul." frozen by a typed letter, then "ations based on."
        assertEquals("ations based on.", resumedAfterDrop(" which does some simple calcul", " which does some simple calculations based on."))
        assertEquals(" discussed it.", resumedAfterDrop(" we often", " we often discussed it."))
        assertEquals("'s methods.", resumedAfterDrop(" each other", " each other's methods."))
    }

    @Test fun aSentenceThatEndedAtTheKeyLosesItsMarkRatherThanPuttingItAfterTheKey() {
        assertEquals("", resumedAfterDrop(" I finished", " I finished."))
        assertEquals(" Next one.", resumedAfterDrop(" I finished", " I finished. Next one."))
        assertEquals(" and more.", resumedAfterDrop(" I finished", " I finished, and more."))
    }
}
