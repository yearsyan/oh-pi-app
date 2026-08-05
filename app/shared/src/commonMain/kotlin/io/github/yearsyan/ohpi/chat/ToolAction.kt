package io.github.yearsyan.ohpi.chat

import io.github.yearsyan.ohpi.net.PiJson
import io.github.yearsyan.ohpi.net.str
import kotlinx.serialization.json.JsonArray
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

/** How a tool's raw JSON arguments should be rendered in the detail view. */
internal sealed interface ToolInputView {
    /** Shell command shown terminal-style. */
    data class Command(val command: String) : ToolInputView

    /** File content being written, shown as a plain code block. */
    data class FileContent(val content: String) : ToolInputView

    /** One removed/added line pair inside an edit payload. */
    data class EditHunk(val oldText: String, val newText: String)

    /** Edit payload shown as removed/added line pairs. */
    data class FileEdit(val hunks: List<EditHunk>) : ToolInputView

    /** Nothing extra to show: the header label already carries the target. */
    data object Hidden : ToolInputView

    /** Unparsed arguments shown as raw JSON (unknown tools). */
    data class Raw(val args: String) : ToolInputView
}

internal fun toolInputView(name: String, args: String): ToolInputView {
    if (args.isBlank()) return ToolInputView.Hidden
    val kind = toolKind(name)
    val arguments = parseToolArgs(args)

    fun argument(vararg keys: String): String? = arguments?.argument(*keys)

    return when (kind) {
        ToolActionKind.Execute ->
            argument("command", "cmd", "script")?.trim()?.takeIf { it.isNotBlank() }
                ?.let { ToolInputView.Command(it) }
                ?: ToolInputView.Raw(args)
        ToolActionKind.Write ->
            argument("content", "text", "contents")
                ?.let { ToolInputView.FileContent(it) }
                ?: ToolInputView.Raw(args)
        ToolActionKind.Edit -> {
            val hunks = mutableListOf<ToolInputView.EditHunk>()
            val editsArray = arguments?.get("edits") as? JsonArray
            if (editsArray != null) {
                // pi schema: { "path": …, "edits": [{ "oldText": …, "newText": … }] }
                editsArray.forEach { element ->
                    val hunk = element as? JsonObject ?: return@forEach
                    val oldText = hunk.argument("oldText", "old_string", "old").orEmpty()
                    val newText = hunk.argument("newText", "new_string", "new").orEmpty()
                    if (oldText.isNotEmpty() || newText.isNotEmpty()) {
                        hunks += ToolInputView.EditHunk(oldText, newText)
                    }
                }
            } else {
                val oldText = argument("oldText", "old_string", "old")
                val newText = argument("newText", "new_string", "new")
                if (oldText != null || newText != null) {
                    hunks += ToolInputView.EditHunk(oldText.orEmpty(), newText.orEmpty())
                }
            }
            if (hunks.isNotEmpty()) {
                ToolInputView.FileEdit(hunks)
            } else {
                ToolInputView.Raw(args)
            }
        }
        // the collapsed header already shows the path/pattern as the target
        ToolActionKind.Read, ToolActionKind.Search, ToolActionKind.List -> ToolInputView.Hidden
        ToolActionKind.Call -> ToolInputView.Raw(args)
    }
}

private fun compactToolTarget(raw: String): String {
    val compact = raw.lineSequence().firstOrNull().orEmpty().trim().replace(Regex("\\s+"), " ")
    return if (compact.length > 64) compact.take(63) + "…" else compact
}

/**
 * Last path segment of a file target, e.g. "src/components/index.ts" → "index.ts".
 * Handles both "/" and "\\" separators so Windows-style paths collapse too.
 */
internal fun fileNameOf(target: String): String {
    val normalized = target.replace('\\', '/').trimEnd('/')
    val slash = normalized.lastIndexOf('/')
    return if (slash >= 0) normalized.substring(slash + 1) else normalized
}
