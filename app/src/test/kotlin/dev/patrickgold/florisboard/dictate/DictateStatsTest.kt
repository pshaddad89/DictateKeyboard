package dev.patrickgold.florisboard.dictate

import dev.patrickgold.florisboard.dictate.data.stats.DictateStats
import kotlin.test.Test
import kotlin.test.assertEquals

class DictateStatsTest {

    @Test
    fun wordCountHandlesWhitespaceSeparatedText() {
        assertEquals(0, DictateStats.wordCount(""))
        assertEquals(0, DictateStats.wordCount(" \n\t "))
        assertEquals(1, DictateStats.wordCount("hello"))
        assertEquals(2, DictateStats.wordCount("  hello   world  "))
        assertEquals(3, DictateStats.wordCount("one\ntwo\tthree"))
    }

    @Test
    fun wordCountTreatsUnspacedCjkAsOneToken() {
        assertEquals(1, DictateStats.wordCount("你好世界"))
    }
}
