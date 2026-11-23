package reikai.domain.novel.tts

import eu.kanade.tachiyomi.util.lang.Hash
import java.text.Normalizer

/**
 * The identity of a paragraph's rendered audio, so a render made on this phone, on a PC server or in an
 * imported pack is found again by the text alone. The rule is part of the PC server contract, so changing
 * it strands every render already made.
 */
object TtsParagraphKey {

    /** The version of the paragraph rule, sent to a PC server so a pack made under another can be refused. */
    const val SPLITTER_VERSION = 1

    private val whitespace = Regex("\\s+")

    /** Lowercase hex SHA-256 of [text] in NFC, with every whitespace run collapsed to one space, trimmed. */
    fun of(text: String): String =
        Hash.sha256(Normalizer.normalize(text, Normalizer.Form.NFC).replace(whitespace, " ").trim())
}
