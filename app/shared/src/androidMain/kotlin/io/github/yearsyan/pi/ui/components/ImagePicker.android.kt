package io.github.yearsyan.pi.ui.components

import android.content.Context
import android.net.Uri
import android.provider.OpenableColumns
import android.util.Base64
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.platform.LocalContext
import io.github.yearsyan.pi.chat.PromptImage
import java.io.ByteArrayOutputStream
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

@Composable
internal actual fun rememberImagePicker(onResult: (ImagePickResult) -> Unit): ImagePicker {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val currentOnResult = rememberUpdatedState(onResult)
    val launcher =
        rememberLauncherForActivityResult(ActivityResultContracts.GetContent()) { uri ->
            if (uri != null) {
                scope.launch {
                    val result = withContext(Dispatchers.IO) { readPickedImage(context, uri) }
                    currentOnResult.value(result)
                }
            }
        }
    return remember(launcher) {
        ImagePicker(available = true) { launcher.launch("image/*") }
    }
}

private fun readPickedImage(context: Context, uri: Uri): ImagePickResult =
    runCatching {
        val output = ByteArrayOutputStream()
        val input = context.contentResolver.openInputStream(uri) ?: return ImagePickResult.Failed
        input.use { stream ->
            val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
            while (true) {
                val count = stream.read(buffer)
                if (count < 0) break
                if (output.size() + count > MaxPickedImageBytes) return ImagePickResult.TooLarge
                output.write(buffer, 0, count)
            }
        }
        val mimeType = context.contentResolver.getType(uri)?.takeIf { it.startsWith("image/") } ?: "image/jpeg"
        ImagePickResult.Success(
            PromptImage(
                data = Base64.encodeToString(output.toByteArray(), Base64.NO_WRAP),
                mimeType = mimeType,
                name = displayName(context, uri),
            ),
        )
    }.getOrElse { ImagePickResult.Failed }

private fun displayName(context: Context, uri: Uri): String {
    val queried =
        runCatching {
            context.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { cursor ->
                if (cursor.moveToFirst()) cursor.getString(0) else null
            }
        }.getOrNull()
    return queried?.takeIf { it.isNotBlank() } ?: uri.lastPathSegment?.substringAfterLast('/') ?: "image"
}
