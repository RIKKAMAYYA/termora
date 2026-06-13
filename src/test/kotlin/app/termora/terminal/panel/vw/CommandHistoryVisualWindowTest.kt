package app.termora.terminal.panel.vw

import kotlin.test.Test
import kotlin.test.assertEquals

class CommandHistoryVisualWindowTest {

    @Test
    fun testParseRemoteBashHistory() {
        val history = """
            __TERMORA_HISTORY_TYPE__:bash
            ls -la
            git status
        """.trimIndent()

        assertEquals(listOf("git status", "ls -la"), CommandHistoryVisualWindow.parseRemoteHistory(history))
    }

    @Test
    fun testParseRemoteZshHistory() {
        val history = """
            __TERMORA_HISTORY_TYPE__:zsh
            : 1710000000:0;cd /opt/app
            : 1710000001:0;docker ps
        """.trimIndent()

        assertEquals(listOf("docker ps", "cd /opt/app"), CommandHistoryVisualWindow.parseRemoteHistory(history))
    }

    @Test
    fun testParseRemoteFishHistory() {
        val history = """
            __TERMORA_HISTORY_TYPE__:fish
            - cmd: cd /opt/app
              when: 1710000000
            - cmd: mysql -h 127.0.0.1\n  -uroot
              when: 1710000001
        """.trimIndent()

        assertEquals(
            listOf("mysql -h 127.0.0.1\n  -uroot", "cd /opt/app"),
            CommandHistoryVisualWindow.parseRemoteHistory(history)
        )
    }
}
