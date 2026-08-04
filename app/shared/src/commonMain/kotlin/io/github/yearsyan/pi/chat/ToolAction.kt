package io.github.yearsyan.pi.chat

import io.github.yearsyan.pi.net.PiJson
import io.github.yearsyan.pi.net.str
import kotlinx.serialization.json.JsonObject

internal enum class ToolActionKind {
    Execute,
    Read,
    Write,
    Edit,
    Search,
    List,
    Call,
}

internal data class ToolAction(
    val kind: ToolActionKind,
    val target: String,
)

private fun parseToolArgs(args: String): JsonObject? =
    runCatching { PiJson.parseToJsonElement(args) as? JsonObject }.getOrNull()

private fun JsonObject.argument(vararg keys: String): String? =
    keys.firstNotNullOfOrNull { key -> str(key)?.takeIf { it.isNotBlank() } }

private fun toolKind(name: String): ToolActionKind =
    when (name.trim().lowercase().replace('-', '_')) {
        "bash", "shell", "exec", "execute", "run_command" -> ToolActionKind.Execute
        "read", "read_file", "readfile" -> ToolActionKind.Read
        "write", "write_file", "writefile", "create_file" -> ToolActionKind.Write
        "edit", "edit_file", "editfile", "apply_patch", "patch" -> ToolActionKind.Edit
        "grep", "search", "rg", "find", "glob" -> ToolActionKind.Search
        "ls", "list", "list_directory" -> ToolActionKind.List
        else -> ToolActionKind.Call
    }

internal fun toolAction(name: String, args: String): ToolAction {
    val arguments = parseToolArgs(args)

    fun argument(vararg keys: String): String? = arguments?.argument(*keys)

    val kind = toolKind(name)
    val rawTarget =
        when (kind) {
            ToolActionKind.Execute -> argument("command", "cmd", "script")
            ToolActionKind.Read,
            ToolActionKind.Write,
            ToolActionKind.Edit,
            -> argument("path", "filePath", "file_path", "file")
            ToolActionKind.Search -> argument("pattern", "query", "path")
            ToolActionKind.List -> argument("path", "directory")
            ToolActionKind.Call -> name
        }
    return ToolAction(
        kind = kind,
        target = compactToolTarget(rawTarget.orEmpty().ifBlank { name.ifBlank { "tool" } }),
    )
}

/** Full untruncated shell command for Execute-kind tools, or null when unavailable. */
internal fun toolCommandArgument(name: String, args: String): String? {
    if (toolKind(name) != ToolActionKind.Execute) return null
    return parseToolArgs(args)?.argument("command", "cmd", "script")?.trim()
}

private fun compactToolTarget(raw: String): String {
    val compact = raw.lineSequence().firstOrNull().orEmpty().trim().replace(Regex("\\s+"), " ")
    return if (compact.length > 64) compact.take(63) + "…" else compact
}
