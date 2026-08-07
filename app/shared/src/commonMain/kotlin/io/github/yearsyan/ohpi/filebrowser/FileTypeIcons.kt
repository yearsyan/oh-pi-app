package io.github.yearsyan.ohpi.filebrowser

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.path
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/** Short label plus brand colour describing a known file type. */
data class FileTypeVisual(val label: String, val color: Color)

private fun visual(label: String, color: Long) = FileTypeVisual(label, Color(color))

private fun putAll(
    map: MutableMap<String, FileTypeVisual>,
    label: String,
    color: Long,
    vararg extensions: String,
) {
    val v = visual(label, color)
    extensions.forEach { map[it] = v }
}

/**
 * Colours follow each language's brand hue, darkened enough that the white
 * label keeps roughly 4:1 contrast on both light and dark surfaces.
 */
private val ByExtension: Map<String, FileTypeVisual> = buildMap {
    putAll(this, "KT", 0xFF7450D8, "kt", "kts")
    putAll(this, "JAVA", 0xFFA56517, "java", "class", "jar")
    putAll(this, "PY", 0xFF3572A5, "py", "pyw", "pyi")
    putAll(this, "JS", 0xFF8A6D0B, "js", "mjs", "cjs")
    putAll(this, "JSX", 0xFF0F7FA8, "jsx")
    putAll(this, "TS", 0xFF3178C6, "ts", "mts", "cts")
    putAll(this, "TSX", 0xFF0F7FA8, "tsx")
    putAll(this, "VUE", 0xFF2F8C66, "vue")
    putAll(this, "SV", 0xFFD0381B, "svelte")
    putAll(this, "C", 0xFF4F6272, "c", "h")
    putAll(this, "C++", 0xFF044F86, "cpp", "cxx", "cc", "hpp", "hh", "hxx")
    putAll(this, "C#", 0xFF68217A, "cs", "csx")
    putAll(this, "F#", 0xFF2C6E9B, "fs", "fsx")
    putAll(this, "VB", 0xFF3F4F92, "vb")
    putAll(this, "GO", 0xFF007E9E, "go")
    putAll(this, "RS", 0xFFA8430F, "rs")
    putAll(this, "RB", 0xFFB52B25, "rb", "erb", "gemspec")
    putAll(this, "PHP", 0xFF4F5D95, "php", "phtml")
    putAll(this, "SW", 0xFFC7422A, "swift")
    putAll(this, "OBJ", 0xFF2F6FD0, "m", "mm")
    putAll(this, "SC", 0xFFC22D40, "scala", "sc")
    putAll(this, "HS", 0xFF5E5086, "hs", "lhs")
    putAll(this, "CLJ", 0xFF4C8C28, "clj", "cljs")
    putAll(this, "EX", 0xFF6E4A7E, "ex", "exs")
    putAll(this, "ERL", 0xFF8A0A2E, "erl", "hrl")
    putAll(this, "LUA", 0xFF4A4EA0, "lua")
    putAll(this, "PL", 0xFF00618A, "pl", "pm")
    putAll(this, "R", 0xFF276DC3, "r", "rmd")
    putAll(this, "DART", 0xFF0175C2, "dart")
    putAll(this, ">_", 0xFF3E9120, "sh", "bash", "zsh", "fish")
    putAll(this, "HTML", 0xFFCF4520, "html", "htm")
    putAll(this, "CSS", 0xFF5C2D91, "css")
    putAll(this, "SCSS", 0xFFB55485, "scss", "sass")
    putAll(this, "LESS", 0xFF2A4C84, "less")
    putAll(this, "{}", 0xFF5E6A71, "json", "jsonc", "json5")
    putAll(this, "XML", 0xFFC26A24, "xml", "xsl", "xsd", "svg")
    putAll(this, "YML", 0xFFB01E28, "yml", "yaml")
    putAll(this, "TOML", 0xFF8C3B1E, "toml")
    putAll(this, "MD", 0xFF2F6DA3, "md", "markdown", "mdx")
    putAll(this, "SQL", 0xFF9C5A1F, "sql")
    putAll(this, "CFG", 0xFF5B6770, "ini", "cfg", "conf", "config", "properties", "env")
    putAll(this, "GRD", 0xFF23505A, "gradle")
}

/** Exact lowercase file names that win over the extension lookup. */
private val ByFileName: Map<String, FileTypeVisual> = mapOf(
    "dockerfile" to visual("DKR", 0xFF1D7AB8),
    "containerfile" to visual("DKR", 0xFF1D7AB8),
    "makefile" to visual("MK", 0xFF6E5233),
    "cmakelists.txt" to visual("CMK", 0xFF2E5E4E),
    ".gitignore" to visual("GIT", 0xFFC44A2E),
    ".gitattributes" to visual("GIT", 0xFFC44A2E),
    ".gitmodules" to visual("GIT", 0xFFC44A2E),
)

/** Resolves a file name to its colourful type visual, or null when unknown. */
fun fileTypeVisual(name: String): FileTypeVisual? {
    val lower = name.lowercase()
    ByFileName[lower]?.let { return it }
    val ext = lower.substringAfterLast('.', "")
    if (ext.isEmpty() || ext == lower) return null
    return ByExtension[ext]
}

// ---- vector drawing (SVG-style paths expressed with Compose PathBuilder) ----

private val sheetCache = HashMap<Color, ImageVector>()

/** Dog-eared file sheet filled with [color]; the fold reads as lighter paper. */
private fun fileSheetVector(color: Color): ImageVector = sheetCache.getOrPut(color) {
    ImageVector.Builder(
        name = "FileSheet",
        defaultWidth = 24.dp,
        defaultHeight = 24.dp,
        viewportWidth = 24f,
        viewportHeight = 24f,
    ).apply {
        // Sheet: rounded corners (r=2), top-right corner cut for the fold.
        path(fill = SolidColor(color)) {
            moveTo(7f, 2f)
            lineTo(14f, 2f)
            lineTo(19f, 7f)
            lineTo(19f, 20f)
            quadTo(19f, 22f, 17f, 22f)
            lineTo(7f, 22f)
            quadTo(5f, 22f, 5f, 20f)
            lineTo(5f, 4f)
            quadTo(5f, 2f, 7f, 2f)
            close()
        }
        // Fold, inset a touch so a thin coloured edge separates it.
        path(fill = SolidColor(Color.White), fillAlpha = 0.38f) {
            moveTo(14.8f, 2.9f)
            lineTo(18.1f, 6.2f)
            lineTo(14.8f, 6.2f)
            close()
        }
    }.build()
}

/** Colourful per-language file icon: tinted sheet plus a short white label. */
@Composable
fun FileTypeChip(visual: FileTypeVisual, modifier: Modifier = Modifier) {
    Box(modifier.size(22.dp), contentAlignment = Alignment.Center) {
        Icon(
            imageVector = fileSheetVector(visual.color),
            contentDescription = null,
            modifier = Modifier.matchParentSize(),
            tint = Color.Unspecified,
        )
        Text(
            text = visual.label,
            modifier = Modifier.padding(top = 2.5.dp),
            color = Color.White,
            fontSize = when (visual.label.length) {
                1, 2 -> 7.sp
                3 -> 6.sp
                else -> 4.5.sp
            },
            fontWeight = FontWeight.Bold,
            letterSpacing = 0.sp,
            maxLines = 1,
        )
    }
}
