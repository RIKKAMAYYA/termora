package app.termora.snippet

import app.termora.ApplicationScope
import app.termora.terminal.ControlCharacters
import org.apache.commons.lang3.StringUtils
import java.util.Locale

data class SnippetTriggerMatch(
    val typed: String,
    val completion: String,
    val snippet: Snippet,
)

data class SnippetSuggestionState(
    val match: SnippetTriggerMatch? = null,
) {
    companion object {
        val None = SnippetSuggestionState()
    }
}

internal class SnippetTriggerManager private constructor() {
    companion object {
        fun getInstance(): SnippetTriggerManager {
            return ApplicationScope.forApplicationScope()
                .getOrCreate(SnippetTriggerManager::class) { SnippetTriggerManager() }
        }

        private val TriggerRegex = Regex("^[A-Za-z0-9][A-Za-z0-9._-]*$")
    }

    private val snippetManager get() = SnippetManager.getInstance()

    fun find(input: String): SnippetTriggerMatch? {
        return find(input, snippetManager.snippets())
    }

    internal fun find(input: String, snippets: List<Snippet>): SnippetTriggerMatch? {
        val typed = extractToken(input) ?: return null
        val entries = entries(snippets)
        val exact = entries[typed.lowercase(Locale.getDefault())]
            ?.takeIf { it.size == 1 }
            ?.firstOrNull()
        if (exact != null) {
            return SnippetTriggerMatch(typed, unescape(exact.snippet.snippet), exact.snippet)
        }

        val matches = entries.values
            .asSequence()
            .filter { it.size == 1 }
            .flatten()
            .filter { it.key.startsWith(typed, ignoreCase = true) }
            .distinctBy { it.key.lowercase(Locale.getDefault()) }
            .take(2)
            .toList()

        if (matches.size != 1) return null
        val match = matches.first()
        return SnippetTriggerMatch(typed, match.key.removePrefix(typed), match.snippet)
    }

    fun command(match: SnippetTriggerMatch): String {
        return unescape(match.snippet.snippet)
    }

    fun pathTrigger(snippet: Snippet): String {
        return pathTrigger(snippet, snippetManager.snippets().associateBy { it.id }) ?: StringUtils.EMPTY
    }

    fun isTriggerAvailable(trigger: String, snippetId: String): Boolean {
        val normalized = normalizeTrigger(trigger) ?: return trigger.isBlank()
        return entries(snippetManager.snippets())
            .getOrDefault(normalized.lowercase(Locale.getDefault()), emptyList())
            .all { it.snippet.id == snippetId }
    }

    fun normalizeTrigger(trigger: String): String? {
        val normalized = trigger.trim()
        if (normalized.isBlank()) return null
        if (!TriggerRegex.matches(normalized)) return null
        return normalized
    }

    private fun entries(snippets: List<Snippet>): Map<String, List<Entry>> {
        val byId = snippets.associateBy { it.id }
        return snippets
            .asSequence()
            .filter { it.type == SnippetType.Snippet && it.snippet.isNotBlank() }
            .flatMap { snippet ->
                sequence {
                    normalizeTrigger(snippet.trigger)?.let { yield(Entry(it, snippet)) }
                    pathTrigger(snippet, byId)?.let { yield(Entry(it, snippet)) }
                }
            }
            .groupBy { it.key.lowercase(Locale.getDefault()) }
    }

    private fun pathTrigger(snippet: Snippet, byId: Map<String, Snippet>): String? {
        val names = mutableListOf(snippet.name)
        var parentId = snippet.parentId
        while (parentId.isNotBlank()) {
            val parent = byId[parentId] ?: break
            if (parent.type != SnippetType.Folder) break
            names.add(parent.name)
            parentId = parent.parentId
        }

        val parts = names.asReversed().map { normalizePathPart(it) }
        if (parts.any { it.isBlank() }) return null

        val trigger = parts
            .joinToString(".")
            .trim('.')

        return normalizeTrigger(trigger)
    }

    private fun normalizePathPart(text: String): String {
        return text.trim()
            .replace(Regex("\\s+"), "-")
            .replace(Regex("[^A-Za-z0-9._-]"), "-")
            .replace(Regex("-+"), "-")
            .trim('-', '.', '_')
    }

    private fun extractToken(input: String): String? {
        if (input.isBlank()) return null
        if (input.last().isWhitespace()) return null
        val start = input.indexOfLast { it.isWhitespace() } + 1
        val token = input.substring(start)
        return normalizeTrigger(token)
    }

    private fun unescape(text: String): String {
        val chars = text.toCharArray()
        val sb = StringBuilder()
        for (i in chars.indices) {
            val c = chars[i]
            if (!SpecialChars.containsKey(c)) {
                sb.append(c)
                continue
            }
            if (chars.getOrNull(i - 1) != '\\') {
                sb.append(c)
                continue
            }
            if (chars.getOrNull(i - 2) == '\\') {
                sb.deleteCharAt(sb.length - 1)
                sb.append(c)
                continue
            }
            sb.deleteCharAt(sb.length - 1)
            sb.append(SpecialChars.getValue(c))
        }
        return sb.toString()
    }

    private data class Entry(val key: String, val snippet: Snippet)

    private val SpecialChars = mapOf(
        'r' to '\r',
        'n' to '\n',
        't' to '\t',
        'a' to ControlCharacters.BEL,
        'e' to ControlCharacters.ESC,
        'b' to ControlCharacters.BS,
    )
}
