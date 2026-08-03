package io.github.yearsyan.pi.ui.components

import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember

@Composable
internal actual fun rememberImagePicker(onResult: (ImagePickResult) -> Unit): ImagePicker =
    remember { ImagePicker(available = false) {} }
