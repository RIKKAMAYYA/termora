package app.termora

import app.termora.terminal.ControlCharacters
import org.apache.commons.io.FileUtils
import org.apache.commons.lang3.StringUtils
import java.io.File
import java.nio.charset.Charset
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.StandardOpenOption
import java.util.Locale

internal object LocalCommandHistoryManager {
    private const val MAX_ENTRIES = 1000
    private const val MAX_COMMAND_LENGTH = 4000
    private val lock = Any()

    fun read(host: Host, limit: Int): List<String> {
        return synchronized(lock) {
            val file = historyFile(host)
            if (!file.isFile) return@synchronized emptyList()
            runCatching {
                Files.readAllLines(file.toPath(), StandardCharsets.UTF_8)
                    .asReversed()
                    .asSequence()
                    .map { it.trim() }
                    .filter { it.isNotBlank() }
                    .take(limit)
                    .toList()
            }.getOrElse { emptyList() }
        }
    }

    fun append(host: Host, command: String) {
        val normalized = normalizeCommand(command) ?: return
        synchronized(lock) {
            val file = historyFile(host)
            FileUtils.forceMkdirParent(file)

            val lines = if (file.isFile) {
                runCatching { Files.readAllLines(file.toPath(), StandardCharsets.UTF_8) }.getOrElse { emptyList() }
            } else {
                emptyList()
            }

            if (lines.lastOrNull() == normalized) return@synchronized

            Files.writeString(
                file.toPath(),
                "$normalized\n",
                StandardCharsets.UTF_8,
                StandardOpenOption.CREATE,
                StandardOpenOption.APPEND
            )

            prune(file)
        }
    }

    internal fun normalizeCommand(command: String): String? {
        if (command.startsWith(' ')) return null

        val normalized = command
            .replace(ControlCharacters.CR, ' ')
            .replace(ControlCharacters.LF, ' ')
            .replace('\u0000', ' ')
            .trim()

        if (normalized.isBlank()) return null
        if (normalized.length > MAX_COMMAND_LENGTH) return null
        if (isSensitive(normalized)) return null
        return normalized
    }

    private fun prune(file: File) {
        val lines = runCatching { Files.readAllLines(file.toPath(), StandardCharsets.UTF_8) }.getOrNull() ?: return
        if (lines.size <= MAX_ENTRIES) return
        Files.write(
            file.toPath(),
            lines.takeLast(MAX_ENTRIES),
            StandardCharsets.UTF_8,
            StandardOpenOption.TRUNCATE_EXISTING,
            StandardOpenOption.CREATE
        )
    }

    private fun historyFile(host: Host): File {
        val key = "${host.ownerId}_${host.id}".replace(Regex("[^A-Za-z0-9._-]"), "_")
        return File(Application.getBaseDataDir(), "command-history/$key.history")
    }

    private fun isSensitive(command: String): Boolean {
        val lower = command.lowercase(Locale.getDefault())
        if (Regex("""^(passwd|sudo\s+passwd)(\s|$)""").containsMatchIn(lower)) return true
        if (Regex("""\bsudo\s+-s\b""").containsMatchIn(lower)) return true
        if (Regex("""\bsudo\s+-S\b""").containsMatchIn(command)) return true
        if (Regex("""\bsshpass\b""").containsMatchIn(lower)) return true
        if (Regex("""\b(mysql|mariadb|mysqldump|mysqladmin)\b.*\s-p\S+""").containsMatchIn(lower)) return true
        if (Regex("""\b[a-z0-9_-]*(password|passwd|secret|token|api[_-]?key|mysql_pwd)[a-z0-9_-]*\b\s*[:=]""").containsMatchIn(lower)) return true
        if (Regex("""\b(--password|--pass|--token|--secret)(=|\s+)""").containsMatchIn(lower)) return true
        if (Regex("""\bdocker\s+login\b.*\s-p(\s+|=)?\S+""").containsMatchIn(lower)) return true
        return false
    }
}

internal class LocalCommandHistoryRecorder internal constructor(
    private val onCommand: (String) -> Unit
) {
    constructor(host: Host) : this({ command -> LocalCommandHistoryManager.append(host, command) })

    private val buffer = StringBuilder()
    private var invalid = false
    private var inEscapeSequence = false

    fun accept(bytes: ByteArray, charset: Charset) {
        if (bytes.isEmpty()) return
        accept(String(bytes, charset))
    }

    internal fun accept(text: String) {
        val content = text
            .replace("${ControlCharacters.ESC}[200~", StringUtils.EMPTY)
            .replace("${ControlCharacters.ESC}[201~", StringUtils.EMPTY)

        for (c in content) {
            if (inEscapeSequence) {
                if (c in '@'..'~') {
                    inEscapeSequence = false
                }
                continue
            }

            when (c) {
                ControlCharacters.CR, ControlCharacters.LF -> commit()
                ControlCharacters.BS, 0x7F.toChar() -> {
                    if (!invalid && buffer.isNotEmpty()) {
                        buffer.deleteAt(buffer.length - 1)
                    }
                }
                ControlCharacters.TAB -> invalid = true
                ControlCharacters.ESC -> {
                    invalid = true
                    inEscapeSequence = true
                }
                0x03.toChar(), 0x04.toChar() -> reset()
                0x15.toChar() -> {
                    buffer.clear()
                    invalid = false
                }
                0x17.toChar() -> deleteLastWord()
                else -> {
                    if (!invalid && !Character.isISOControl(c)) {
                        buffer.append(c)
                    }
                }
            }
        }
    }

    private fun deleteLastWord() {
        if (invalid || buffer.isEmpty()) return
        while (buffer.isNotEmpty() && buffer.last().isWhitespace()) {
            buffer.deleteAt(buffer.length - 1)
        }
        while (buffer.isNotEmpty() && !buffer.last().isWhitespace()) {
            buffer.deleteAt(buffer.length - 1)
        }
    }

    private fun commit() {
        if (!invalid) {
            LocalCommandHistoryManager.normalizeCommand(buffer.toString())?.let(onCommand)
        }
        reset()
    }

    private fun reset() {
        buffer.clear()
        invalid = false
        inEscapeSequence = false
    }
}
