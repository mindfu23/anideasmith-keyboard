// SPDX-License-Identifier: GPL-3.0-only
package helium314.keyboard.latin.voice

import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * The fixtures are real payloads captured on an S24 with EXTRA_ENABLE_FORMATTING off, which is the
 * state this mode puts the engine in. That run produced no punctuation of its own — 0 of 10 finals —
 * and left every spoken mark standing as a word, which is what makes this possible at all.
 */
class SpokenPunctuationTest {

    private fun apply(text: String) = SpokenPunctuation.apply(text)

    // --- the marks ----------------------------------------------------------------------------

    @Test fun spokenMarksBecomeMarks() {
        assertEquals(" a test, and here is another clause.",
            apply(" a test comma and here is another clause period"))
    }

    @Test fun aMarkHugsTheWordBeforeIt() {
        // the whole point: "test comma and" is three words, "test, and" is two and a mark
        assertEquals("test, and", apply("test comma and"))
        assertEquals("done.", apply("done period"))
    }

    @Test fun twoWordMarksAreNotReadAsWords() {
        // "question mark" must not become the word "question" followed by the word "mark"
        assertEquals("really?", apply("really question mark"))
        assertEquals("wow!", apply("wow exclamation point"))
        assertEquals("wow!", apply("wow exclamation mark"))
    }

    @Test fun aSmileyIsSpacedLikeTheWordItStandsInFor() {
        // Gboard produces ":-)" for this and a SpeechRecognizer client gets the words, so the
        // mapping is ours to make. Spaced as a word: not hugging the one before it like a comma.
        assertEquals("awesome :-)", apply("awesome smiley face"))
        assertEquals("awesome :-) today", apply("awesome smiley face today"))
        assertEquals(" :-)", apply(" Smiley Face"))
    }

    @Test fun aSmileySaidFastStillCounts() {
        // the observation that prompted the hyphen matcher in the first place
        assertEquals("awesome :-)", apply("awesome Smiley-Face"))
    }

    @Test fun theEscapeGivesBackTheWordsSmileyFace() {
        assertEquals("the words smiley face here", apply("the words literal word smiley face here"))
    }

    @Test fun quotesLeanTheWayTheyPoint() {
        // an opening quote takes the space before it, a closing one the space after
        assertEquals("he said \"hello\" once", apply("he said open quotes hello close quotes once"))
    }

    @Test fun aPhraseSaidFastArrivesHyphenatedAndStillCounts() {
        // observed on "smiley face": said at speed the recogniser returns one token, "Smiley-Face".
        // The same happens to any multi-word command.
        assertEquals("really?", apply("really question-mark"))
        assertEquals("wow!", apply("wow Exclamation-Point"))
        assertEquals("done.\nnext", apply("done period new-line next"))
    }

    @Test fun hyphenatedOrdinaryWordsAreNotTakenApart() {
        // splitting the text on hyphens would have put "low-carb" back together with a space
        assertEquals("going low-carb but not no-carb", apply("going low-carb but not no-carb"))
        assertEquals("a smiley-faced person", apply("a smiley-faced person"))
    }

    @Test fun theEscapeWorksOnAHyphenatedPhraseToo() {
        // and gives back the hyphen, since it gives back what was said: run the phrase together
        // fast enough for the recogniser to hyphenate it and that is what was said
        assertEquals("say question-mark here", apply("say literal word question-mark here"))
        assertEquals("say question-mark here", apply("say literal-word question-mark here"))
        assertEquals(0, SpokenPunctuation.outdents("say literal word out-dent here"))
    }

    @Test fun theEngineCapitalisesTheseSoMatchingIgnoresCase() {
        // every segment arrives capitalised, so the mark is as likely to be "Comma" as "comma"
        assertEquals("first, second.", apply("first Comma second Period"))
    }

    @Test fun aMarkOnItsOwnDoesNotBringTheSegmentSpaceWithIt() {
        // Pause before saying it and the mark arrives as its own payload, leading space and all.
        // Keeping that space put one in front of the comma, which is what a real session produced:
        // "the end of the last word and , the punctuation".
        assertEquals(",", apply("Comma"))
        assertEquals(",", apply(" Comma"))
        assertEquals(".", apply(" Period"))
        // captured verbatim from a session, when "new line" was still arriving as two words
        assertEquals(".\n", apply(" Period New line"))
    }

    @Test fun anOpeningQuoteOnItsOwnKeepsTheSpace() {
        // it stands where a word would, so it is spaced like one
        assertEquals(" \"", apply(" open quotes"))
    }

    @Test fun theLeadingSpaceIsKept() {
        // segments arrive with one, and it is what separates them from the previous utterance
        assertEquals(" and then, this", apply(" and then comma this"))
        assertEquals("and then, this", apply("and then comma this"))
    }

    @Test fun wordsThatAreNotMarksAreLeftAlone() {
        val text = " the quick brown fox"
        assertEquals(text, apply(text))
    }

    @Test fun emptyAndBlankAreSafe() {
        assertEquals("", apply(""))
        assertEquals("   ", apply("   "))
    }

    @Test fun aMarkGrowingIntoAWordIsLeftToTheCorrectionPath() {
        // "period" becomes "." at once; if the engine then says "periodically" the next payload
        // simply disagrees and the tail is rewritten, the same as any other revision
        assertEquals("I said.", apply("I said period"))
        assertEquals("I said periodically", apply("I said periodically"))
    }

    // --- the structural ones --------------------------------------------------------------------

    @Test fun aSpokenLineBreakIsALineBreak() {
        assertEquals("done.\nnext", apply("done period new line next"))
        assertEquals("done.\nnext", apply("done period newline next"))
    }

    @Test fun nothingIsSpacedAcrossALineBreak() {
        // a leading space on the new line would indent it by one character for no reason
        assertEquals("\nhere", apply("new line here"))
        assertEquals("\t", apply(" indent"))
    }

    @Test fun anIndentIsATab() {
        assertEquals("\tbullet one", apply("indent bullet one"))
    }

    @Test fun outdentLeavesNothingBehindInTheText() {
        // it goes out as a key event instead, so it must not also appear as a word or a space
        assertEquals("done", apply("done outdent"))
        assertEquals("", apply("outdent"))
    }

    @Test fun outdentsAreCounted() {
        assertEquals(0, SpokenPunctuation.outdents("nothing here"))
        assertEquals(1, SpokenPunctuation.outdents("done outdent"))
        assertEquals(2, SpokenPunctuation.outdents("outdent and then outdent"))
        assertEquals(1, SpokenPunctuation.outdents(" Outdent"))
    }

    @Test fun countingOutdentsIsSafeOnEmptyText() {
        assertEquals(0, SpokenPunctuation.outdents(""))
        assertEquals(0, SpokenPunctuation.outdents("   "))
    }

    @Test fun structuralCharactersAreTheOnesTheAppWillReactTo() {
        // LatinIME freezes the dictated run after writing one of these, so the set has to match
        // exactly what apply can emit
        assertTrue(SpokenPunctuation.STRUCTURAL.contains('\n'))
        assertTrue(SpokenPunctuation.STRUCTURAL.contains('\t'))
        assertFalse(SpokenPunctuation.STRUCTURAL.contains(','))
    }

    // --- saying the name instead of the mark ----------------------------------------------------

    @Test fun literalWordGivesTheNameNotTheMark() {
        assertEquals("say comma here", apply("say literal word comma here"))
        assertEquals("say new line here", apply("say literal word new line here"))
        assertEquals("say question mark here", apply("say literal word question mark here"))
    }

    @Test fun theWordsComeBackAsSpoken() {
        // "newline" is one word and "new line" two; both mean the mark, and each escapes to itself
        assertEquals("say newline here", apply("say literal word newline here"))
    }

    @Test fun theEscapeIsInertWithNoMarkAfterIt() {
        // "the literal word for it" is ordinary prose and has to survive as such
        assertEquals("the literal word for it", apply("the literal word for it"))
        assertEquals("a literal word", apply("a literal word"))
    }

    @Test fun theEscapeIgnoresCaseButDoesNotChangeIt() {
        // the recogniser capitalises segment starts, so the marker arrives capitalised and has to
        // still be recognised. What follows comes back exactly as spoken -- deciding its case is
        // capitalise's job, and doing it here as well would fight it.
        assertEquals("Comma", apply("Literal Word Comma"))
        assertEquals("comma", SpokenPunctuation.capitalise(apply("Literal Word Comma"), false))
    }

    @Test fun anEscapedOutdentDoesNotOutdent() {
        assertEquals(0, SpokenPunctuation.outdents("say literal word outdent here"))
        assertEquals("say outdent here", apply("say literal word outdent here"))
        // and an unescaped one still does
        assertEquals(1, SpokenPunctuation.outdents("say outdent here"))
    }

    @Test fun outdentsAreCountedPastOtherMarks() {
        // stepping one token at a time would find "outdent" inside a longer mark's words
        assertEquals(1, SpokenPunctuation.outdents("one question mark outdent two"))
    }

    @Test fun theWholeExampleFromTheRequest() {
        val spoken = "In this prose I am saying the phrase open quote literal word new line close quote " +
                "in order to add a literal word new line after this sentence colon new line and then continue"
        assertEquals("In this prose I am saying the phrase \"new line\" " +
                "in order to add a new line after this sentence:\nand then continue", apply(spoken))
    }

    // --- capitalisation -----------------------------------------------------------------------

    @Test fun aSegmentAfterNoPunctuationDoesNotStartASentence() {
        // the recogniser capitalises every segment; a pause for breath is not a full stop
        assertEquals(" and then it moved", SpokenPunctuation.capitalise(" And then it moved", false))
    }

    @Test fun aSegmentAfterAFullStopDoes() {
        assertEquals(" And then it moved", SpokenPunctuation.capitalise(" and then it moved", true))
    }

    @Test fun aSpokenMarkStartsTheNextSentence() {
        assertEquals("one. Two. Three", SpokenPunctuation.capitalise("one. two. three", false))
        assertEquals("what? Yes!", SpokenPunctuation.capitalise("what? yes!", false))
    }

    @Test fun onlyTheSentenceStartsAreTouched() {
        // proper nouns and "I" survive, because a rule cannot tell one from a stray capital
        assertEquals("i saw Mark in Paris", SpokenPunctuation.capitalise("I saw Mark in Paris", false))
        assertEquals("I saw Mark in Paris", SpokenPunctuation.capitalise("I saw Mark in Paris", true))
    }

    @Test fun capitalisingIsSafeOnTextWithNoLetters() {
        assertEquals(",", SpokenPunctuation.capitalise(",", false))
        assertEquals("", SpokenPunctuation.capitalise("", true))
    }

    // --- what counts as the end of a sentence --------------------------------------------------

    @Test fun onlySentenceMarksEndASentence() {
        assertTrue(SpokenPunctuation.endsSentence("done."))
        assertTrue(SpokenPunctuation.endsSentence("really?"))
        assertTrue(SpokenPunctuation.endsSentence("wow!"))
        assertFalse(SpokenPunctuation.endsSentence("a list,"))
        assertFalse(SpokenPunctuation.endsSentence("mid sentence"))
    }

    @Test fun trailingSpaceDoesNotHideTheMark() {
        assertTrue(SpokenPunctuation.endsSentence("done. "))
        assertFalse(SpokenPunctuation.endsSentence("word "))
    }

    @Test fun aNewLineStartsASentence() {
        // a heading, a list item or a line broken mid-thought all read as beginnings, and none of
        // them carries a full stop
        assertEquals("one\nTwo", SpokenPunctuation.capitalise("one\ntwo", false))
        assertEquals("done.\nNext", SpokenPunctuation.capitalise(apply("done period new line next"), false))
    }

    @Test fun aNewLineBehindTheTextStartsOneToo() {
        // the previous segment ended with a line break, so this one begins a sentence even though
        // nothing punctuated it
        assertTrue(SpokenPunctuation.endsSentence("a list item\n"))
        assertTrue(SpokenPunctuation.endsSentence("a list item\n  "))
        assertFalse(SpokenPunctuation.endsSentence("a list item "))
    }

    @Test fun theWholeExampleFromTheRequestThroughBothPasses() {
        val spoken = "In this prose I am saying the phrase open quote literal word new line close quote " +
                "in order to add a literal word new line after this sentence colon new line and then continue"
        assertEquals("In this prose I am saying the phrase \"new line\" " +
                "in order to add a new line after this sentence:\nAnd then continue",
            SpokenPunctuation.capitalise(apply(spoken), true))
    }

    @Test fun anEmptyFieldBeginsASentence() {
        assertTrue(SpokenPunctuation.endsSentence(null))
        assertTrue(SpokenPunctuation.endsSentence(""))
        assertTrue(SpokenPunctuation.endsSentence("\n"))
    }
}
