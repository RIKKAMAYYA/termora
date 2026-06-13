package app.termora

import app.termora.terminal.ControlCharacters
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class LocalCommandHistoryRecorderTest {

    @Test
    fun testRecordCommandOnEnter() {
        val commands = mutableListOf<String>()
        val recorder = LocalCommandHistoryRecorder { commands.add(it) }

        recorder.accept("git status\r")

        assertEquals(listOf("git status"), commands)
    }

    @Test
    fun testRecordPastedCommands() {
        val commands = mutableListOf<String>()
        val recorder = LocalCommandHistoryRecorder { commands.add(it) }

        recorder.accept("cd /opt/app\r./deploy.sh\r")

        assertEquals(listOf("cd /opt/app", "./deploy.sh"), commands)
    }

    @Test
    fun testBackspaceEditsCommand() {
        val commands = mutableListOf<String>()
        val recorder = LocalCommandHistoryRecorder { commands.add(it) }

        recorder.accept("git stats${ControlCharacters.BS}us\r")

        assertEquals(listOf("git status"), commands)
    }

    @Test
    fun testEscapeSequenceInvalidatesCurrentCommand() {
        val commands = mutableListOf<String>()
        val recorder = LocalCommandHistoryRecorder { commands.add(it) }

        recorder.accept("git${ControlCharacters.ESC}[D status\rpwd\r")

        assertEquals(listOf("pwd"), commands)
    }

    @Test
    fun testSensitiveCommandIsSkipped() {
        assertNull(LocalCommandHistoryManager.normalizeCommand("mysql -uroot -p123456"))
        assertNull(LocalCommandHistoryManager.normalizeCommand("export API_TOKEN=abc"))
    }
}
