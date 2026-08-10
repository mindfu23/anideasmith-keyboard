// SPDX-License-Identifier: GPL-3.0-only
package helium314.keyboard.latin.voice

/**
 * Turns spoken punctuation into marks, for people who would rather say where the commas go than let
 * the recogniser guess.
 *
 * The engine offers a version of this itself, and it is not the same thing: `EXTRA_ENABLE_FORMATTING`
 * infers punctuation from intonation, so a rising pitch produces a "?" whether or not one was
 * wanted. Measured on an S24 with it on, 34 of 34 finals carried punctuation nobody asked for, and
 * the spoken words came through *as well* — "the same day comma, so", "Sure period, and also". Both
 * at once, which is the worst of the two.
 *
 * With formatting off the same device produced no punctuation at all, 0 of 10 finals, and left the
 * spoken words intact for this to pick up. That is why the mode turns formatting off rather than
 * trying to unpick it: the engine is much better at not doing something than at being corrected.
 *
 * Everything here is a pure function of the engine's text, so the streaming machinery in LatinIME
 * neither knows nor cares that a transform happened.
 */
internal object SpokenPunctuation {

    /**
     * How a mark joins the words on either side of it. A closing mark hugs the word before it and
     * pushes the next one away; an opening quote does the reverse.
     */
    private class Mark(val words: List<String>, val text: String, val spaceBefore: Boolean, val spaceAfter: Boolean)

    private fun closing(text: String, vararg words: String) = Mark(words.toList(), text, false, true)
    private fun opening(text: String, vararg words: String) = Mark(words.toList(), text, true, false)

    /**
     * Longest phrase first, so "question mark" is not read as the word "question" followed by the
     * word "mark". Both spellings of the sentence-enders are here because people say both.
     */
    private val MARKS = listOf(
        closing("!", "exclamation", "point"),
        closing("!", "exclamation", "mark"),
        closing("?", "question", "mark"),
        closing("\"", "close", "quotes"),
        closing("\"", "close", "quote"),
        opening("\"", "open", "quotes"),
        opening("\"", "open", "quote"),
        closing(".", "full", "stop"),
        // A line break and an indent are characters like any other here, so they land where they
        // were spoken and the ordinary streaming puts them there. What they are not is harmless:
        // the app answers a newline with a bullet and an indent of its own, behind text this class
        // is still tracking, so LatinIME freezes the run after writing one exactly as it does for a
        // pressed key. Nothing follows them on the same line, hence no trailing space.
        Mark(listOf("new", "line"), "\n", spaceBefore = false, spaceAfter = false),
        Mark(listOf("newline"), "\n", spaceBefore = false, spaceAfter = false),
        Mark(listOf("indent"), "\t", spaceBefore = false, spaceAfter = false),
        // Outdent has no character to be. It is dropped from the text here and carried out as a
        // shift-Tab by the caller, which is the only way to ask an editor to unindent.
        Mark(listOf("outdent"), "", spaceBefore = false, spaceAfter = false),
        closing(",", "comma"),
        closing(".", "period"),
        closing(";", "semicolon"),
        closing(":", "colon")
    ).sortedByDescending { it.words.size }

    /** Characters this can put in the text that the app will answer with edits of its own. */
    const val STRUCTURAL = "\n\t"

    private val OUTDENT = listOf("outdent")

    /**
     * Say this before a mark's name to get the name instead of the mark.
     *
     * Windows Speech Recognition has had the idea for years, as "literal <word>"; Dragon has no
     * escape at all and tells people to pause around punctuation instead. The second word is this
     * fork's, and it earns its place: "literal" alone turns up in ordinary speech often enough to
     * misfire, and the pair almost never does.
     *
     * It only takes effect when a mark actually follows, so "the literal word for it" is left alone
     * and only "the literal word comma" is read as an escape. What no escape can do is escape
     * itself: the marker is consumed, so saying "the literal word comma" yields "the comma", and
     * there is no way to ask for the words "literal word" followed by the word "comma". Windows has
     * the same hole and it is not a solvable one — a prefix cannot both be a command and not be.
     */
    private val ESCAPE = listOf("literal", "word")

    /** The mark being escaped at [from], or null if there is no escape there. */
    private fun escapeAt(tokens: List<String>, from: Int): Mark? {
        if (ESCAPE.size > tokens.size - from) return null
        ESCAPE.forEachIndexed { offset, word ->
            if (!tokens[from + offset].equals(word, ignoreCase = true)) return null
        }
        return markAt(tokens, from + ESCAPE.size)
    }

    /**
     * How many times the text asks to unindent.
     *
     * Counted rather than mapped because the mark it produces is nothing at all: a shift-Tab has to
     * be sent as a key event, which is an action and not a character, so it cannot be streamed into
     * place with the words. Only ever acted on for a finalised utterance — partials repeat the whole
     * utterance every time, and an action repeated once per partial would walk the text left across
     * the screen.
     */
    fun outdents(text: String): Int {
        if (text.isBlank()) return 0
        val tokens = text.trim().split(WHITESPACE)
        var count = 0
        var i = 0
        while (i < tokens.size) {
            val escaped = escapeAt(tokens, i)
            if (escaped != null) {
                i += ESCAPE.size + escaped.words.size // spoken as words, so it commands nothing
                continue
            }
            val mark = markAt(tokens, i)
            if (mark == null) {
                i++
            } else {
                if (mark.words == OUTDENT) count++
                i += mark.words.size
            }
        }
        return count
    }

    private val WHITESPACE = Regex("\\s+")

    /** Characters that end a sentence, and so make the next word start one. */
    private const val SENTENCE_ENDS = ".?!"

    /**
     * The same, plus a line break. Starting a new line starts a sentence whether or not the last one
     * was finished off with a mark — a heading, a list item or a line broken mid-thought all read as
     * beginnings, and none of them carries a full stop.
     */
    private const val SENTENCE_STARTS_AFTER = "$SENTENCE_ENDS\n"

    fun endsSentence(text: CharSequence?): Boolean {
        if (text == null) return true // nothing before it is the start of something
        for (i in text.length - 1 downTo 0) {
            val c = text[i]
            // checked before the whitespace skip, which would otherwise step straight over it
            if (c == '\n') return true
            if (c.isWhitespace()) continue
            return SENTENCE_ENDS.indexOf(c) >= 0
        }
        return true // only whitespace behind it, so it begins a line
    }

    /**
     * Replace spoken punctuation with the marks it names.
     *
     * The whole utterance is transformed every time, including its last word, so a mark appears the
     * moment it is spoken rather than one word later. When the engine turns out to have meant
     * something else — "period" growing into "periodically" — the next payload simply disagrees, and
     * the ordinary correction path rewrites the tail. That is the same shape of edit the engine
     * already makes on its own, so it needs no special handling here.
     */
    fun apply(text: String): String {
        if (text.isBlank()) return text
        val tokens = text.trim().split(WHITESPACE)
        // Segments arrive with a leading space, which is what separates them from the last one. A
        // closing mark does not want it: pause before saying "comma" and the mark lands as its own
        // segment, so keeping the space puts one in front of the comma — "the end of the last word
        // and , the punctuation". An opening quote does want it, being a word as far as spacing is
        // concerned.
        val first = markAt(tokens, 0)
        val leading = if (text[0].isWhitespace() && (first == null || first.spaceBefore)) " " else ""
        val out = StringBuilder()
        var spaceOwed = false
        var i = 0
        while (i < tokens.size) {
            val escaped = escapeAt(tokens, i)
            if (escaped != null) {
                // the words as they were spoken rather than the canonical spelling, so "newline"
                // comes back as "newline" and not as "new line"
                for (k in escaped.words.indices) {
                    if (spaceOwed && out.isNotEmpty()) out.append(' ')
                    out.append(tokens[i + ESCAPE.size + k])
                    spaceOwed = true
                }
                i += ESCAPE.size + escaped.words.size
                continue
            }
            val mark = markAt(tokens, i)
            if (mark == null) {
                if (spaceOwed && out.isNotEmpty()) out.append(' ')
                out.append(tokens[i])
                spaceOwed = true
                i++
            } else {
                if (mark.spaceBefore && out.isNotEmpty()) out.append(' ')
                out.append(mark.text)
                spaceOwed = mark.spaceAfter
                i += mark.words.size
            }
        }
        return leading + out
    }

    private fun markAt(tokens: List<String>, from: Int): Mark? = MARKS.firstOrNull { mark ->
        mark.words.size <= tokens.size - from &&
                mark.words.withIndex().all { (offset, word) -> tokens[from + offset].equals(word, ignoreCase = true) }
    }

    /**
     * Capitalise from the punctuation and from nothing else.
     *
     * The recogniser capitalises the first word of every segment whatever came before it, so a pause
     * for breath starts a sentence that was never finished. Turning its formatting off does not stop
     * that — measured the same morning, 10 of 10 segments still arrived capitalised with punctuation
     * gone entirely — so it has to be undone here.
     *
     * Only two letters in the text are ever touched: the first one, and the first after a sentence
     * mark or a line break. Everything else is left exactly as the engine sent it, which keeps nouns
     * and "I" intact. The engine's other habit — a stray capital mid-sentence, "another Clause
     * period" — survives this deliberately, because telling a name from a mistake is not something a
     * rule can do. Selecting the word offers it in the other case instead.
     *
     * @param afterSentenceEnd what precedes this text finishes a sentence, so it may begin one.
     */
    fun capitalise(text: String, afterSentenceEnd: Boolean): String {
        val out = StringBuilder(text)
        var startsSentence = afterSentenceEnd
        var seenFirstLetter = false
        for (i in out.indices) {
            val c = out[i]
            if (c.isLetter()) {
                if (!seenFirstLetter || startsSentence) {
                    out[i] = if (startsSentence) c.uppercaseChar() else c.lowercaseChar()
                }
                seenFirstLetter = true
                startsSentence = false
            } else if (SENTENCE_STARTS_AFTER.indexOf(c) >= 0) {
                startsSentence = true
                seenFirstLetter = true
            }
        }
        return out.toString()
    }
}
