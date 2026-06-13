package app.termora.snippet

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class SnippetTriggerManagerTest {

    private val manager = SnippetTriggerManager.getInstance()

    @Test
    fun testPathTriggerMatchesFolderAndName() {
        val snippets = listOf(
            Snippet(id = "loan", name = "loan", type = SnippetType.Folder),
            Snippet(id = "loan-g", name = "g", parentId = "loan", snippet = "grep loan"),
        )

        val match = manager.find("loan.g", snippets)

        assertEquals("loan.g", match?.typed)
        assertEquals("grep loan", match?.completion)
        assertEquals("grep loan", manager.command(match!!))
    }

    @Test
    fun testDuplicateShortNamesDoNotMatchWithoutPath() {
        val snippets = listOf(
            Snippet(id = "loan", name = "loan", type = SnippetType.Folder),
            Snippet(id = "card", name = "card", type = SnippetType.Folder),
            Snippet(id = "loan-g", name = "g", parentId = "loan", snippet = "grep loan"),
            Snippet(id = "card-g", name = "g", parentId = "card", snippet = "grep card"),
        )

        assertNull(manager.find("g", snippets))
        assertEquals("grep loan", manager.command(manager.find("loan.g", snippets)!!))
        assertEquals("grep card", manager.command(manager.find("card.g", snippets)!!))
    }

    @Test
    fun testShortcutTriggerMatchesBeforePathPrefix() {
        val snippets = listOf(
            Snippet(id = "loan", name = "loan", type = SnippetType.Folder),
            Snippet(id = "loan-g", name = "g", parentId = "loan", trigger = "grepw", snippet = "grep loan"),
        )

        val match = manager.find("grepw", snippets)

        assertEquals("grepw", match?.typed)
        assertEquals("grep loan", match?.completion)
        assertEquals("grep loan", manager.command(match!!))
    }

    @Test
    fun testDuplicateShortcutDoesNotMatch() {
        val snippets = listOf(
            Snippet(id = "a", name = "a", trigger = "log", snippet = "tail a"),
            Snippet(id = "b", name = "b", trigger = "log", snippet = "tail b"),
        )

        assertNull(manager.find("log", snippets))
    }

    @Test
    fun testCompletionUsesSingleCandidate() {
        val snippets = listOf(
            Snippet(id = "loan", name = "loan", type = SnippetType.Folder),
            Snippet(id = "loan-g", name = "g", parentId = "loan", snippet = "grep loan"),
        )

        val match = manager.find("loa", snippets)

        assertEquals("loa", match?.typed)
        assertEquals("n.g", match?.completion)
    }
}
