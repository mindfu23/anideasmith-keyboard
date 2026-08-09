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
        closing(",", "comma"),
        closing(".", "period"),
        closing(";", "semicolon"),
        closing(":", "colon")
    ).sortedByDescending { it.words.size }

    private val WHITESPACE = Regex("\\s+")

    /** Characters that end a sentence, and so make the next word start one. */
    private const val SENTENCE_ENDS = ".?!"

    fun endsSentence(text: CharSequence?): Boolean {
        if (text == null) return true // nothing before it is the start of something
        for (i in text.length - 1 downTo 0) {
            val c = text[i]
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
        val leading = if (text[0].isWhitespace()) " " else ""
        val tokens = text.trim().split(WHITESPACE)
        val out = StringBuilder()
        var spaceOwed = false
        var i = 0
        while (i < tokens.size) {
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
     * mark. Everything between them is left exactly as the engine sent it, which keeps proper nouns
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
            } else if (SENTENCE_ENDS.indexOf(c) >= 0) {
                startsSentence = true
                seenFirstLetter = true
            }
        }
        return out.toString()
    }
}
