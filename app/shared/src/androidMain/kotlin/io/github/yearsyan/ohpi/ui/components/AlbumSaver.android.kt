package io.github.yearsyan.ohpi.ui.components

import android.content.ContentValues
import android.os.Build
import android.provider.MediaStore
import io.github.yearsyan.ohpi.data.AndroidAppContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Inserts into MediaStore under Pictures/OhPi. On API 29+ scoped storage makes
 * this permission-free; below that it needs WRITE_EXTERNAL_STORAGE (declared
 * in the manifest with maxSdkVersion=28, granted at install time).
 */
internal actual suspend fun saveImageToAlbum(
    bytes: ByteArray,
    displayName: String,
    mimeType: String,
): AlbumSaveResult =
    withContext(Dispatchers.IO) {
        runCatching {
                val resolver = AndroidAppContext.context.contentResolver
                val scoped = Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q
                val collection =
                    if (scoped) {
                        MediaStore.Images.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY)
                    } else {
                        MediaStore.Images.Media.EXTERNAL_CONTENT_URI
                    }
                val values =
                    ContentValues().apply {
                        put(MediaStore.Images.Media.DISPLAY_NAME, displayName)
                        put(MediaStore.Images.Media.MIME_TYPE, mimeType)
                        if (scoped) {
                            put(MediaStore.Images.Media.RELATIVE_PATH, "Pictures/OhPi")
                            put(MediaStore.Images.Media.IS_PENDING, 1)
                        }
                    }
                val uri = resolver.insert(collection, values) ?: error("MediaStore insert failed")
                resolver.openOutputStream(uri)?.use { it.write(bytes) } ?: error("open $uri failed")
                if (scoped) {
                    values.clear()
                    values.put(MediaStore.Images.Media.IS_PENDING, 0)
                    resolver.update(uri, values, null, null)
                }
            }
            .fold(
                onSuccess = { AlbumSaveResult.Success },
                onFailure = { AlbumSaveResult.Failure(it.message) },
            )
    }
