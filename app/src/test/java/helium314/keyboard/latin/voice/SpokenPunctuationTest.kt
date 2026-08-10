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

    @Test fun quotesLeanTheWayTheyPoint() {
        // an opening quote takes the space before it, a closing one the space after
        assertEquals("he said \"hello\" once", apply("he said open quotes hello close quotes once"))
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
        assertEquals(". New line", apply(" Period New line"))
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

    @Test fun anEmptyFieldBeginsASentence() {
        assertTrue(SpokenPunctuation.endsSentence(null))
        assertTrue(SpokenPunctuation.endsSentence(""))
        assertTrue(SpokenPunctuation.endsSentence("\n"))
    }
}
