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

internal fun toolAction(name: String, args: String): ToolAction {
    val normalizedName = name.trim().lowercase().replace('-', '_')
    val arguments =
        runCatching { PiJson.parseToJsonElement(args) as? JsonObject }
            .getOrNull()

    fun argument(vararg keys: String): String? =
        keys.firstNotNullOfOrNull { key -> arguments?.str(key)?.takeIf { it.isNotBlank() } }

    val kind =
        when (normalizedName) {
            "bash", "shell", "exec", "execute", "run_command" -> ToolActionKind.Execute
            "read", "read_file", "readfile" -> ToolActionKind.Read
            "write", "write_file", "writefile", "create_file" -> ToolActionKind.Write
            "edit", "edit_file", "editfile", "apply_patch", "patch" -> ToolActionKind.Edit
            "grep", "search", "rg", "find", "glob" -> ToolActionKind.Search
            "ls", "list", "list_directory" -> ToolActionKind.List
            else -> ToolActionKind.Call
        }
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

private fun compactToolTarget(raw: String): String {
    val compact = raw.lineSequence().firstOrNull().orEmpty().trim().replace(Regex("\\s+"), " ")
    return if (compact.length > 64) compact.take(63) + "…" else compact
}
