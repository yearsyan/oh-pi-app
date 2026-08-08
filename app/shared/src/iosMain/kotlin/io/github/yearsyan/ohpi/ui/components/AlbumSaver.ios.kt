package io.github.yearsyan.ohpi.ui.components

import kotlin.coroutines.resume
import kotlin.time.Clock
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.addressOf
import kotlinx.cinterop.usePinned
import kotlinx.coroutines.suspendCancellableCoroutine
import platform.Foundation.NSFileManager
import platform.Foundation.NSTemporaryDirectory
import platform.Foundation.NSURL
import platform.Photos.PHAccessLevelAddOnly
import platform.Photos.PHAssetChangeRequest
import platform.Photos.PHAuthorizationStatus
import platform.Photos.PHAuthorizationStatusAuthorized
import platform.Photos.PHAuthorizationStatusLimited
import platform.Photos.PHAuthorizationStatusNotDetermined
import platform.Photos.PHPhotoLibrary
import platform.posix.fclose
import platform.posix.fopen
import platform.posix.fwrite

/**
 * Saves to the iOS Photos library with add-only access (iOS 14+); the app's
 * Info.plist declares NSPhotoLibraryAddUsageDescription. The bytes are staged
 * in the temp directory because Photos accepts asset files by URL — that
 * avoids NSData/UIImage bridging entirely.
 */
@OptIn(ExperimentalForeignApi::class)
internal actual suspend fun saveImageToAlbum(
    bytes: ByteArray,
    displayName: String,
    mimeType: String,
): AlbumSaveResult {
    val path =
        "${NSTemporaryDirectory()}ohpi-save-${Clock.System.now().toEpochMilliseconds()}." +
            imageExtensionOf(mimeType)
    val file = fopen(path, "wb") ?: return AlbumSaveResult.Failure("temp file failed")
    bytes.usePinned { pinned -> fwrite(pinned.addressOf(0), 1uL, bytes.size.toULong(), file) }
    fclose(file)

    var status = PHPhotoLibrary.authorizationStatusForAccessLevel(PHAccessLevelAddOnly)
    if (status == PHAuthorizationStatusNotDetermined) {
        status = requestAddOnlyAuthorization()
    }
    if (status != PHAuthorizationStatusAuthorized && status != PHAuthorizationStatusLimited) {
        NSFileManager.defaultManager.removeItemAtPath(path, null)
        return AlbumSaveResult.Failure("Photos access not granted")
    }

    val url = NSURL(fileURLWithPath = path)
    return suspendCancellableCoroutine { continuation ->
        PHPhotoLibrary.sharedPhotoLibrary().performChanges(
            changeBlock = {
                PHAssetChangeRequest.creationRequestForAssetFromImageAtFileURL(fileURL = url)
            },
            completionHandler = { success, error ->
                NSFileManager.defaultManager.removeItemAtPath(path, null)
                continuation.resume(
                    if (success) AlbumSaveResult.Success
                    else AlbumSaveResult.Failure(error?.localizedDescription)
                )
            },
        )
    }
}

@OptIn(ExperimentalForeignApi::class)
private suspend fun requestAddOnlyAuthorization(): PHAuthorizationStatus =
    suspendCancellableCoroutine { continuation ->
        PHPhotoLibrary.requestAuthorizationForAccessLevel(PHAccessLevelAddOnly) { status ->
            continuation.resume(status)
        }
    }
