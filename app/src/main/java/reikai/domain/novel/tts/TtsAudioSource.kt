package reikai.domain.novel.tts

import dev.icerock.moko.resources.StringResource
import tachiyomi.i18n.MR

/** Where read-aloud gets its audio. Persisted by name through `getEnum`, so the names are load-bearing. */
enum class TtsAudioSource(val titleRes: StringResource) {
    /** The speech engine speaks each paragraph as it is reached, as read-aloud always has. */
    LIVE(MR.strings.tts_audio_source_live),

    /** Paragraphs are rendered to files on this phone ahead of playback; one not ready yet is spoken live. */
    PHONE(MR.strings.tts_audio_source_phone),

    /** Paragraphs are rendered by a render server on the user's PC and downloaded over the local network. */
    PC(MR.strings.tts_audio_source_pc),
}
