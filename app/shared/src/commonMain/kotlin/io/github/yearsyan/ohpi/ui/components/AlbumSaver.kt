package io.github.yearsyan.ohpi.ui.components

/** Outcome of [saveImageToAlbum]. */
internal sealed interface AlbumSaveResult {
    data object Success : AlbumSaveResult

    /** User dismissed the platform save UI (desktop save dialog); not an error. */
    data object Cancelled : AlbumSaveResult

    data class Failure(val message: String? = null) : AlbumSaveResult
}

/**
 * Saves image bytes to the platform photo album (Android MediaStore, iOS
 * Photos). Desktop has no album, so a native save-file dialog is shown instead.
 */
internal expect suspend fun saveImageToAlbum(
    bytes: ByteArray,
    displayName: String,
    mimeType: String,
): AlbumSaveResult

/** Image MIME type from magic bytes; falls back to PNG. */
internal fun imageMimeOf(bytes: ByteArray): String =
    when {
        bytes.size >= 4 &&
            bytes[0] == 0x89.toByte() && bytes[1] == 0x50.toByte() &&
            bytes[2] == 0x4E.toByte() && bytes[3] == 0x47.toByte() -> "image/png"
        bytes.size >= 2 && bytes[0] == 0xFF.toByte() && bytes[1] == 0xD8.toByte() -> "image/jpeg"
        bytes.size >= 3 &&
            bytes[0] == 'G'.code.toByte() && bytes[1] == 'I'.code.toByte() &&
            bytes[2] == 'F'.code.toByte() -> "image/gif"
        bytes.size >= 12 &&
            bytes[0] == 'R'.code.toByte() && bytes[1] == 'I'.code.toByte() &&
            bytes[2] == 'F'.code.toByte() && bytes[3] == 'F'.code.toByte() &&
            bytes[8] == 'W'.code.toByte() && bytes[9] == 'E'.code.toByte() &&
            bytes[10] == 'B'.code.toByte() && bytes[11] == 'P'.code.toByte() -> "image/webp"
        bytes.size >= 2 && bytes[0] == 'B'.code.toByte() && bytes[1] == 'M'.code.toByte() ->
            "image/bmp"
        else -> "image/png"
    }

/** File extension (without dot) for a MIME type produced by [imageMimeOf]. */
internal fun imageExtensionOf(mimeType: String): String =
    when (mimeType) {
        "image/jpeg" -> "jpg"
        "image/gif" -> "gif"
        "image/webp" -> "webp"
        "image/bmp" -> "bmp"
        else -> "png"
    }
