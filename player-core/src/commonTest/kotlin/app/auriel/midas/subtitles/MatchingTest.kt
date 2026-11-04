package app.auriel.midas.subtitles

import app.auriel.midas.core.Chapter
import app.auriel.midas.core.Languages
import app.auriel.midas.library.NaturalOrder
import app.auriel.midas.library.SubtitleMatcher
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class MatchingTest {
    @Test
    fun naturalOrderPutsE2BeforeE10() {
        val names = listOf("Show E10.mkv", "Show E2.mkv", "Show E1.mkv", "show e3.mkv")
        assertEquals(listOf("Show E1.mkv", "Show E2.mkv", "show e3.mkv", "Show E10.mkv"), names.sortedWith(NaturalOrder))
    }

    @Test
    fun sameNameSubtitlesWithLanguageTags() {
        val files = listOf("Fate 01.mkv", "Fate 01.ja.srt", "Fate 01.en.ass", "Fate 02.en.ass", "other.srt")
        val m = SubtitleMatcher.match("Fate 01.mkv", files, "en", byEpisode = false)
        assertEquals(listOf("Fate 01.en.ass", "Fate 01.ja.srt"), m.map { it.name })
        assertEquals("en", m.first().language)
    }

    @Test
    fun episodeNumberMatchingForSubtitleFolders() {
        val subs = listOf("[SumiSora] Fate stay night - 01.ass", "[SumiSora] Fate stay night - 02.ass", "[SumiSora] Fate stay night - 10.ass")
        val m = SubtitleMatcher.match("[Group] Fate Stay Night - 02 (1080p).mp4", subs, "en", byEpisode = true)
        assertEquals(listOf("[SumiSora] Fate stay night - 02.ass"), m.map { it.name })
    }

    @Test
    fun languagesNormalize() {
        assertEquals("ja", Languages.normalize("jpn"))
        assertEquals("en", Languages.normalize("English"))
        assertTrue(Languages.same("eng", "en-US"))
        assertEquals(null, Languages.normalize("und"))
    }

    @Test
    fun chapterSkipKinds() {
        assertEquals(Chapter.SkipKind.Intro, Chapter(0.0, 90.0, "Opening").skipKind)
        assertEquals(Chapter.SkipKind.Ending, Chapter(0.0, 90.0, "ED").skipKind)
        assertEquals(null, Chapter(0.0, 90.0, "Part A").skipKind)
    }

    @Test
    fun ffmpegAssEventsBecomeCues() {
        val cue = assertNotNull(SubtitleParser.assEventCue(1.0, 2.0, """0,0,Default,,0,0,0,,{\i1}Hello{\i0}\Nworld""", emptySet()))
        assertEquals("Hello\nworld", cue.text)
        val sign = assertNotNull(SubtitleParser.assEventCue(1.0, 2.0, """3,0,Sign,,0,0,0,,{\an8}Station""", emptySet()))
        assertTrue(sign.top)
    }

    @Test
    fun guessFromBareEpisodeFileUsesFolder() {
        val g = MediaGuesser.guess("05.mkv", folderName = "Sousou no Frieren")
        assertEquals("Sousou no Frieren", g.title)
        assertEquals(5, g.episode)
    }
}
