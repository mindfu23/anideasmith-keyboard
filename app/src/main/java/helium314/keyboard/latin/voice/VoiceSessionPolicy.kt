// SPDX-License-Identifier: GPL-3.0-only
package helium314.keyboard.latin.voice

import android.os.Build
import android.speech.SpeechRecognizer
import android.text.InputType
import helium314.keyboard.keyboard.internal.keyboard_parser.floris.KeyCode
import helium314.keyboard.latin.common.Constants

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

    /**
     * Below this, an error came back sooner than the engine could have opened the microphone, so
     * it never listened. Measured: a healthy silence timeout answers in about five seconds, a
     * wedged engine in under 150ms and a refused startListening in about two.
     */
    const val WEDGED_ERROR_MS = 150L

    /** Instant errors in a row before the engine is treated as wedged rather than unlucky. */
    const val WEDGE_ERROR_COUNT = 3

    /** Pause before rebuilding, to let the recognition service drop the state it is stuck in. */
    const val RECOVERY_COOL_OFF_MS = 1500L

    /**
     * Silence that ends a segmented session, and so also the value the session is keyed on. Long,
     * because within one session the engine segments at every pause by itself: this is the pause
     * that means "finished", not the pause between two sentences.
     */
    const val SEGMENTED_SILENCE_MS = 30_000

    /** Rebuilds per session before dictation gives up, so a dead engine cannot be retried forever. */
    const val MAX_RECOVERIES = 2

    /** What to do when the recognizer reports an error. */
    enum class ErrorAction {
        /** The utterance was empty; listen again. */
        RESTART,
        /** On-device cannot serve this language; rebuild once against the general recognizer. */
        RETRY_ONLINE,
        /** The engine has stopped listening; destroy it, pause, and build a new one. */
        RECOVER,
        /** The session is over; report it. */
        TERMINAL,
        /** We are tearing down deliberately; say nothing. */
        IGNORE
    }

    /**
     * Whether to ask the engine for one continuous session instead of driving the restart loop.
     *
     * Every `startListening` makes the recognition service play a start earcon, and every
     * end-of-utterance an end one, both on the notification stream — so the restart loop costs the
     * user two audible pings per utterance. Measured over 38 minutes of dictation: 421 restarts,
     * one every 4.7s, a ping every 2.8s. A segmented session listens once and reports each
     * utterance through `onSegmentResults`, which makes it two pings per *session*.
     *
     * Engines may ignore the request ("Depending on the recognizer implementation, this value may
     * have no effect"), so the restart loop stays as the fallback and takes over by itself: it is
     * driven by `onResults`, which only arrives when segmentation is not happening.
     */
    fun useSegmentedSession(sdkInt: Int) = sdkInt >= Build.VERSION_CODES.TIRAMISU

    /**
     * How much of [written] the engine's [latest] text still agrees with.
     *
     * Dictation is streamed into the field a few words at a time, which is only safe because the
     * engine does not take words back: measured over one session, 239 partial-to-partial
     * transitions were pure extensions and none revised an earlier word. What it does revise is
     * punctuation, and only on finalising an utterance — a spoken "period" becomes "." in 6 of 29
     * finals. So what is on screen usually needs nothing done to it, and when it does, only the
     * tail past this point has to be rewritten.
     */
    fun agreedPrefixLength(written: String, latest: String): Int {
        val max = minOf(written.length, latest.length)
        var i = 0
        while (i < max && written[i] == latest[i]) i++
        return i
    }

    /**
     * How much of [written] the engine has actually replaced, given its [latest] text.
     *
     * Not simply "everything past the agreed prefix". A partial can outrun the segment that
     * finalises it, so when [finalised] is true [written] usually ends with words belonging to the
     * *next* utterance — measured on one session, 8 finals in 34. Those words are on screen,
     * correct, and about to be extended by the partials that follow. Deleting them and letting the
     * next partial type them again is a round trip through the input connection that changes
     * nothing, and every round trip is a chance for the field and our record to drift: one such
     * delete removed 98 characters and reissued the same 98 characters 19ms later.
     *
     * The two cases are told apart by where agreement stops. If [latest] is a prefix of [written],
     * nothing was revised and the remainder is overshoot. If agreement stops *inside* [latest], the
     * engine rewrote the tail — a spoken "period" becoming "." — and the tail has to go.
     */
    fun staleLength(written: String, latest: String, finalised: Boolean): Int {
        val agreed = agreedPrefixLength(written, latest)
        // past the end of a finalised utterance is the next utterance, not staleness
        if (finalised && agreed >= latest.length) return 0
        return written.length - agreed
    }

    /**
     * Whether the end of a segmented session should open another one rather than end dictation.
     *
     * Long form only. The engine ends its own session after whatever silence it considers final,
     * measured on an S24 at six to seventeen seconds however long a silence the intent asks for, and
     * the two modes want opposite things from that. Long-form prose is full of pauses longer than
     * that and the session has to survive them. Short form is one thought at a time, so the engine
     * deciding it has heard the end of one is exactly the signal to stop, and reopening only holds
     * the microphone past the point the user stopped talking.
     *
     * Reopening does not carry on forever. The count of empty sessions cannot end it — any recognised
     * word resets that, and a room with a television in it produces words — so the ceiling is time
     * since the last recognised text, the same backstop [onError] uses. Without one, a long-form
     * session put down mid-sentence would reopen every ten seconds until the battery went.
     *
     * @param msSinceLastResult time since the engine last recognised anything, not since the start.
     */
    fun shouldReopenSegmentedSession(isActive: Boolean, longForm: Boolean, msSinceLastResult: Long) =
        isActive && longForm && msSinceLastResult < LONG_FORM_IDLE_TIMEOUT_MS

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

    /**
     * Whether this error means the engine answered without listening. Restricted to the errors a
     * working engine also produces, so a genuinely fatal one (no permission, no network) is still
     * read as fatal however fast it arrives.
     *
     * @param msSinceListenStart time from startListening to the error, not from the session start.
     */
    fun isWedgedError(error: Int, msSinceListenStart: Long) =
        msSinceListenStart < WEDGED_ERROR_MS &&
                (isRestartable(error) || error == SpeechRecognizer.ERROR_CLIENT)

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
        msSinceLastResult: Long = 0,
        instantErrors: Int = 0,
        recoveries: Int = 0
    ): ErrorAction = when {
        cancelling -> ErrorAction.IGNORE
        // nothing recognised for a long time: the session has been forgotten, not paused
        isActive && msSinceLastResult >= LONG_FORM_IDLE_TIMEOUT_MS -> ErrorAction.TERMINAL
        // The engine is answering faster than it could have listened. Restarting it in place only
        // repeats the same instant failure, so rebuild it — and once rebuilding has been tried
        // enough times, accept that nothing here will fix it.
        isActive && instantErrors >= WEDGE_ERROR_COUNT && recoveries < MAX_RECOVERIES -> ErrorAction.RECOVER
        isActive && instantErrors >= WEDGE_ERROR_COUNT -> ErrorAction.TERMINAL
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

    /**
     * How the frozen text lines up with the engine's latest text.
     *
     * @property engineCovered how much of the engine's text is already on screen behind the freeze,
     *   so writing resumes there
     * @property frozenUsed how much of the frozen text the engine's text accounts for
     * @property agrees false when a word behind the freeze was revised, which is worth a log line
     */
    data class FrozenCover(val engineCovered: Int, val frozenUsed: Int, val agrees: Boolean)

    /**
     * Which part of the engine's [engine] text is already on screen as [frozen] text.
     *
     * Compared on letters and digits only. Comparing every character let a revised mark behind the
     * freeze break agreement, and writing resumed from the mark with the rest of the utterance —
     * already on screen — written a second time. Measured 2026-09-29 in Obsidian, two Tabs in one
     * utterance: the frozen text read "could be. something useful." after the engine had first ended
     * a partial with "be.", its final said "could be something useful.", and agreement stopped at the
     * full stop — "could be. something useful. something useful." Marks cannot be taken back once
     * frozen, so the useful question is only whether the *words* agree.
     *
     * When the frozen text runs past the end of [engine], [FrozenCover.frozenUsed] says where: that
     * remainder is words the partials streamed ahead of a segment's end, which the next segment will
     * send again. See [frozenCarryAfterFinal].
     *
     * Case is ignored too, since capitalisation is decided afresh on every payload.
     */
    fun frozenCover(frozen: String, engine: String): FrozenCover {
        var i = 0
        var j = 0
        var lastI = 0
        var lastJ = 0
        while (true) {
            while (i < frozen.length && !frozen[i].isLetterOrDigit()) i++
            while (j < engine.length && !engine[j].isLetterOrDigit()) j++
            if (i >= frozen.length || j >= engine.length) break
            if (frozen[i].lowercaseChar() != engine[j].lowercaseChar()) break
            i++; j++
            lastI = i
            lastJ = j
        }
        if (i >= frozen.length) {
            // Every frozen word is in the engine's text. The frozen text's trailing mark is on screen
            // too, so the engine's mark in the same place is not written again -- but its space is,
            // or the next word would be glued to the mark.
            val frozenTail = frozen.substring(lastI)
            var k = lastJ
            if (frozenTail.isNotEmpty()) {
                while (k < engine.length && !engine[k].isLetterOrDigit() && !engine[k].isWhitespace()) k++
                if (frozenTail.last().isWhitespace())
                    while (k < engine.length && engine[k].isWhitespace()) k++
            }
            return FrozenCover(k, frozen.length, true)
        }
        // the engine's text ends inside the frozen text: all of it is already on screen
        if (j >= engine.length) return FrozenCover(engine.length, lastI, true)
        // A word behind the freeze was revised. What is frozen stays, so resume at the start of the
        // revised word, with its space: the revised tail appears once more rather than being lost,
        // and never as the back half of a word.
        var wordStart = lastJ
        while (wordStart > 0 && engine[wordStart - 1].isLetterOrDigit()) wordStart--
        if (wordStart > 0 && engine[wordStart - 1].isWhitespace()) wordStart--
        return FrozenCover(wordStart, lastI, false)
    }

    /**
     * What of [frozen] is still ahead of the next segment once a final has arrived, or "" if nothing.
     *
     * A partial can run past the end of the segment its final closes, and the words it ran into
     * belong to the next segment, which sends them again. When a Tab froze them first, they were
     * forgotten with the rest of the frozen text and the next segment wrote them a second time.
     * Measured 2026-09-29: frozen "…when I do this. And then I hit tab.", final "…when I do this.",
     * then " And then I hit tab." twice on screen. Carried over, they are what the next segment is
     * compared against, and it writes only what is new.
     */
    fun frozenCarryAfterFinal(frozen: String, cover: FrozenCover): String =
        if (cover.agrees && cover.frozenUsed < frozen.length) frozen.substring(cover.frozenUsed) else ""

    /** A cursor move we caused is not a reason to stop; one the user made is. */
    fun cursorMoveEndsDictation(msSinceOwnWrite: Long) = msSinceOwnWrite >= WRITE_SETTLE_MS

    /**
     * Keys the user can let short-form dictation carry on through. Short form stops on any other
     * key, which is what people expect from a microphone key; these are the exceptions they opt
     * into. Stored by [name] in [Settings.PREF_VOICE_INPUT_SHORT_FORM_IGNORED_KEYS], so renaming
     * an entry forgets it.
     */
    enum class ShortFormKeyGroup(private val matches: (Int) -> Boolean) {
        SHIFT({ it == KeyCode.SHIFT || it == KeyCode.CAPS_LOCK }),
        LAYOUT_SWITCH({ it in setOf(KeyCode.SYMBOL_ALPHA, KeyCode.ALPHA, KeyCode.SYMBOL, KeyCode.NUMPAD,
            KeyCode.EMOJI, KeyCode.CLIPBOARD, KeyCode.LANGUAGE_SWITCH) }),
        SPACE({ it == Constants.CODE_SPACE }),
        ENTER({ it == Constants.CODE_ENTER || it == KeyCode.SHIFT_ENTER }),
        DELETE({ it == KeyCode.DELETE }),
        CURSOR({ it in setOf(KeyCode.ARROW_LEFT, KeyCode.ARROW_RIGHT, KeyCode.ARROW_UP, KeyCode.ARROW_DOWN,
            KeyCode.WORD_LEFT, KeyCode.WORD_RIGHT, KeyCode.PAGE_UP, KeyCode.PAGE_DOWN,
            KeyCode.MOVE_START_OF_LINE, KeyCode.MOVE_END_OF_LINE, KeyCode.MOVE_START_OF_PAGE,
            KeyCode.MOVE_END_OF_PAGE) }),
        UNDO_REDO({ it == KeyCode.UNDO || it == KeyCode.REDO }),
        // anything that types a character that is neither a letter, a digit nor a space
        PUNCTUATION({ it > Constants.CODE_SPACE && !Character.isLetterOrDigit(it) && !Character.isWhitespace(it) });

        fun matches(code: Int) = matches.invoke(code)

        companion object {
            /** The stored form: names joined by ";". Unknown names are dropped, not an error. */
            fun parse(stored: String?): Set<ShortFormKeyGroup> =
                stored.orEmpty().split(";").mapNotNull { name -> entries.firstOrNull { it.name == name } }.toSet()

            fun serialize(groups: Collection<ShortFormKeyGroup>) = groups.sortedBy { it.ordinal }.joinToString(";") { it.name }
        }
    }

    /**
     * Whether short-form dictation carries on through a key the user pressed.
     *
     * Tab has its own switch rather than a place in the list because it is the key this was asked
     * for: indenting or outdenting a line mid-sentence. In a form, Tab moves to the next field
     * instead, and dictation still ends there — leaving the field ends it, see [leftTheField].
     *
     * @param code the event's key code when it has one, otherwise the code point it types
     */
    fun shortFormIgnoresKey(code: Int, keepOnTab: Boolean, groups: Set<ShortFormKeyGroup>) =
        (keepOnTab && (code == KeyCode.TAB || code == KeyCode.TAB_BACK || code == Constants.CODE_TAB))
                || groups.any { it.matches(code) }

    /**
     * Whether a restarted input means focus has left the field dictation was writing into.
     *
     * Measured on 2026-08-16, tabbing through a web form while dictating, three times out of three:
     * about 35ms after the Tab the input restarts with TYPE_NULL and no cursor, the keyboard's view
     * does not finish until about 300ms later, and in between a partial wrote 6 to 16 characters
     * that belonged to the field just left. Ending the session on the restart, not on the finish,
     * is what keeps them out of the next field.
     *
     * The same app also restarts input on the field it is already in — 2 of 6 restarts during
     * dictation that day — and those arrive with a real input type, so they do not end anything.
     */
    fun leftTheField(inputType: Int) = inputType and InputType.TYPE_MASK_CLASS == InputType.TYPE_NULL
}
