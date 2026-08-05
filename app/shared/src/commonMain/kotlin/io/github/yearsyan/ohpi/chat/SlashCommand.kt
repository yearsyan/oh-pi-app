package io.github.yearsyan.ohpi.chat

import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/** One slash command that can be completed and invoked from the message composer. */
data class SlashCommand(
    val name: String,
    val description: String = "",
    val source: SlashCommandSource,
)

enum class SlashCommandSource {
    BuiltIn,
    Extension,
    Prompt,
    Skill,
    Other,
    ;

    companion object {
        fun fromWire(value: String): SlashCommandSource =
            when (value) {
                "extension" -> Extension
                "prompt" -> Prompt
                "skill" -> Skill
                else -> Other
            }
    }
}

internal data class SlashInvocation(
    val name: String,
    val arguments: String,
)

/** Parses a complete slash invocation while keeping multiline command arguments intact. */
internal fun parseSlashInvocation(text: String): SlashInvocation? {
    val trimmed = text.trim()
    if (!trimmed.startsWith('/') || trimmed.length == 1) return null
    val separator = trimmed.indexOfFirst { it.isWhitespace() }
    val name = if (separator < 0) trimmed.drop(1) else trimmed.substring(1, separator)
    if (name.isBlank()) return null
    val arguments = if (separator < 0) "" else trimmed.substring(separator).trim()
    return SlashInvocation(name, arguments)
}

/** Returns autocomplete matches only while the first slash-command token is being typed. */
internal fun matchingSlashCommands(
    text: String,
    commands: List<SlashCommand>,
): List<SlashCommand> {
    if (!text.startsWith('/')) return emptyList()
    val query = text.drop(1)
    if (query.any { it.isWhitespace() }) return emptyList()
    return commands
        .distinctBy { it.name }
        .filter {
            query.isEmpty() ||
                it.name.contains(query, ignoreCase = true) ||
                it.description.contains(query, ignoreCase = true)
        }
        .sortedWith(
            compareBy<SlashCommand> { !it.name.startsWith(query, ignoreCase = true) }
                .thenBy { it.source != SlashCommandSource.BuiltIn }
                .thenBy { it.name.lowercase() },
        )
}

internal fun buildCompactCommand(
    requestId: String,
    customInstructions: String,
): JsonObject =
    buildJsonObject {
        put("id", requestId)
        put("type", "compact")
        customInstructions.trim().takeIf { it.isNotEmpty() }?.let {
            put("customInstructions", it)
        }
    }

/** Hides the expanded SKILL.md body that pi stores in user-message history. */
internal fun compactSkillInvocation(text: String): String {
    if (!text.startsWith("<skill name=\"")) return text
    val headerEnd = text.indexOf("\">\n")
    if (headerEnd < 0) return text
    val nameStart = "<skill name=\"".length
    val nameEnd = text.indexOf('"', nameStart)
    if (nameEnd !in nameStart until headerEnd) return text
    val closing = "\n</skill>"
    val closingStart = text.lastIndexOf(closing)
    if (closingStart <= headerEnd) return text
    val trailing = text.substring(closingStart + closing.length)
    if (trailing.isNotEmpty() && !trailing.startsWith("\n\n")) return text
    val arguments = trailing.removePrefix("\n\n").trim()
    return buildString {
        append("/skill:")
        append(text.substring(nameStart, nameEnd))
        if (arguments.isNotEmpty()) {
            append(' ')
            append(arguments)
        }
    }
}
