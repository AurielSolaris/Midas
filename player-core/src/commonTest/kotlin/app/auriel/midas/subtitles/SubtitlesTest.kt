package app.auriel.midas.subtitles

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlin.test.assertTrue

class SubtitleParserTest {
    @Test
    fun srt() {
        val t = SubtitleParser.parse(
            "﻿1\r\n00:00:01,500 --> 00:00:03,000\r\n<i>Hello</i>\r\nworld\r\n\r\n2\r\n00:00:04,000 --> 00:00:05,250\r\n{\\an8}Top line\r\n",
            "x",
        )
        assertEquals(2, t.cues.size)
        assertEquals(SubtitleCue(1.5, 3.0, "Hello\nworld"), t.cues[0])
        assertEquals(SubtitleCue(4.0, 5.25, "Top line", top = true), t.cues[1])
    }

    @Test
    fun webVtt() {
        val t = SubtitleParser.parse(
            "WEBVTT\nKind: captions\n\n00:00:01.360 --> 00:00:03.040 align:start\n[♪♪♪] &amp; more\n\n01:02.000 --> 01:03.500\n<c.yellow>short</c> form\n",
            "x",
        )
        assertEquals(listOf("[♪♪♪] & more", "short form"), t.cues.map { it.text })
        assertEquals(62.0, t.cues[1].start)
    }

    @Test
    fun ass() {
        val text = """
            [Script Info]
            Title: test

            [V4+ Styles]
            Format: Name, Fontname, Fontsize, PrimaryColour, SecondaryColour, OutlineColour, BackColour, Bold, Italic, Underline, StrikeOut, ScaleX, ScaleY, Spacing, Angle, BorderStyle, Outline, Shadow, Alignment, MarginL, MarginR, MarginV, Encoding
            Style: Default,Arial,20,&H00FFFFFF,&H000000FF,&H00000000,&H00000000,0,0,0,0,100,100,0,0,1,2,2,2,10,10,10,1
            Style: Sign,Arial,20,&H00FFFFFF,&H000000FF,&H00000000,&H00000000,0,0,0,0,100,100,0,0,1,2,2,8,10,10,10,1

            [Events]
            Format: Layer, Start, End, Style, Name, MarginL, MarginR, MarginV, Effect, Text
            Dialogue: 0,0:00:01.00,0:00:03.50,Default,,0,0,0,,{\i1}Hello,{\i0} there\Nfriend
            Dialogue: 0,0:00:01.00,0:00:03.50,Default,,0,0,0,,{\i1}Hello,{\i0} there\Nfriend
            Dialogue: 0,0:00:02.00,0:00:04.00,Sign,,0,0,0,,Station
            Dialogue: 0,0:00:02.00,0:00:04.00,Default,,0,0,0,,{\p1}m 0 0 l 100 0 100 100{\p0}
            Comment: 0,0:00:05.00,0:00:06.00,Default,,0,0,0,,not shown
        """.trimIndent()
        val t = SubtitleParser.parse(text, "x")
        assertEquals(2, t.cues.size, "duplicate and drawing lines are dropped")
        assertEquals("Hello, there\nfriend", t.cues[0].text)
        assertTrue(t.cues[1].top, "style alignment 8 is top")
        assertEquals(listOf("Hello, there\nfriend", "Station"), t.activeAt(2.5).map { it.text })
    }

    @Test
    fun youTubeSrv1() {
        val t = SubtitleParser.parse(
            """<?xml version="1.0" encoding="utf-8" ?><transcript><text start="1.36" dur="1.68">[♪♪♪]</text><text start="2.5" dur="3">We&amp;#39;re no strangers</text></transcript>""",
            "x",
        )
        assertEquals("We're no strangers", t.cues[1].text)
        assertEquals(2.5, t.cues[0].end, "overlapping auto lines are trimmed to the next start")
    }

    @Test
    fun rejectsNonSubtitles() {
        assertFailsWith<IllegalArgumentException> { SubtitleParser.parse("just some text", "x") }
    }

    @Test
    fun activeAtBoundaries() {
        val t = SubtitleTrack("x", null, listOf(SubtitleCue(1.0, 2.0, "a"), SubtitleCue(10.0, 20.0, "b"), SubtitleCue(12.0, 13.0, "c")))
        assertEquals(emptyList(), t.activeAt(0.5).map { it.text })
        assertEquals(listOf("a"), t.activeAt(1.0).map { it.text })
        assertEquals(emptyList(), t.activeAt(2.0).map { it.text })
        assertEquals(listOf("b", "c"), t.activeAt(12.5).map { it.text })
        assertEquals(listOf("b"), t.activeAt(19.9).map { it.text })
    }
}

class MediaGuesserTest {
    @Test
    fun youtubeIds() {
        assertEquals("dQw4w9WgXcQ", MediaGuesser.youtubeIdFromUrl("https://www.youtube.com/watch?v=dQw4w9WgXcQ&t=10"))
        assertEquals("dQw4w9WgXcQ", MediaGuesser.youtubeIdFromUrl("https://youtu.be/dQw4w9WgXcQ"))
        assertEquals("abcdefghijk", MediaGuesser.youtubeIdFromUrl("https://youtube.com/shorts/abcdefghijk"))
        assertEquals("dQw4w9WgXcQ", MediaGuesser.youtubeIdFromUrl("dQw4w9WgXcQ"))
        assertNull(MediaGuesser.youtubeIdFromUrl("https://example.com/watch?v=dQw4w9WgXcQ"))
    }

    @Test
    fun animeReleaseNames() {
        assertEquals(
            ReleaseName("Sousou no Frieren", null, 5),
            MediaGuesser.parseReleaseName("[SubsPlease] Sousou no Frieren - 05 (1080p) [A1B2C3D4].mkv"),
        )
        assertEquals(ReleaseName("Show Name", 1, 12), MediaGuesser.parseReleaseName("Show.Name.S01E12.1080p.WEB-DL.mkv"))
        assertEquals(
            ReleaseName("Kusuriya no Hitorigoto 2nd Season", 2, 3),
            MediaGuesser.parseReleaseName("[Erai-raws] Kusuriya no Hitorigoto 2nd Season - 03v2 [1080p][HEVC].mkv"),
        )
        assertEquals(ReleaseName("One Piece", null, 1100), MediaGuesser.parseReleaseName("One Piece Episode 1100.mp4"))
    }
}
