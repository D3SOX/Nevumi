package me.d3sox.nevumi.library

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class LocalFolderRulesTest {
    private val downloads = "/storage/emulated/0/Music/Nevumi/SoundCloud"

    @Test
    fun allowingAFolderLiftsItsOwnExclusionsHoweverTheyAreWritten() {
        val rules = LocalFolderRules(excluded = listOf("Music/Nevumi/SoundCloud/", "$downloads/Old", "Podcasts"))
        assertNull(rules.excludedParent(downloads))
        val allowed = rules.allowing(downloads)
        assertEquals(LocalFolderRules(excluded = listOf("Podcasts")), allowed)
        assertTrue(allowed.allows(downloads))
    }

    @Test
    fun allowingAFolderAddsItOnlyWhenIncludeFoldersLeaveItOut() {
        assertEquals(listOf("Music/Albums", downloads), LocalFolderRules(listOf("Music/Albums")).allowing(downloads).included)
        assertEquals(listOf("Music"), LocalFolderRules(listOf("Music")).allowing(downloads).included)
        assertEquals(emptyList(), LocalFolderRules().allowing(downloads).included)
    }

    @Test
    fun anExcludedParentFolderIsReportedInsteadOfLifted() {
        val rules = LocalFolderRules(excluded = listOf("Music/"))
        assertEquals("Music/", rules.excludedParent(downloads))
        assertEquals(listOf("Music/"), rules.allowing(downloads).excluded)
    }
}
