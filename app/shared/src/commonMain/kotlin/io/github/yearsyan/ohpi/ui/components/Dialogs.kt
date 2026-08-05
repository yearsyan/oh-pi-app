package io.github.yearsyan.ohpi.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.ArrowUpward
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.CreateNewFolder
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import io.github.yearsyan.ohpi.chat.UiDialogRequest
import io.github.yearsyan.ohpi.i18n.S
import io.github.yearsyan.ohpi.net.FsListResponse
import kotlinx.coroutines.launch
import kotlinx.serialization.json.JsonObjectBuilder
import kotlinx.serialization.json.put

@Composable
fun RenameDialog(
    initial: String,
    onDismiss: () -> Unit,
    onConfirm: (String) -> Unit,
) {
    var value by remember { mutableStateOf(initial) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(S.renameDialogTitle) },
        text = {
            OutlinedTextField(
                value = value,
                onValueChange = { value = it },
                label = { Text(S.sessionNameLabel) },
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
            )
        },
        confirmButton = {
            TextButton(
                onClick = { onConfirm(value.trim()); onDismiss() },
                enabled = value.isNotBlank(),
            ) {
                Text(S.confirm)
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text(S.cancel) }
        },
    )
}

/** New-chat workspace picker: browses directories on the gateway host. */
@Composable
fun WorkspaceDialog(
    initial: String,
    fetchDirs: suspend (String) -> FsListResponse,
    createDir: suspend (parent: String, name: String) -> Unit,
    onDismiss: () -> Unit,
    onConfirm: (String) -> Unit,
) {
    var pathInput by remember { mutableStateOf(initial) }
    var current by remember { mutableStateOf<FsListResponse?>(null) }
    var loading by remember { mutableStateOf(true) }
    var error by remember { mutableStateOf("") }
    var reloadKey by remember { mutableStateOf(0) }
    var showCreateFolder by remember { mutableStateOf(false) }

    LaunchedEffect(reloadKey) {
        loading = true
        error = ""
        try {
            val result = fetchDirs(pathInput.trim())
            current = result
            pathInput = result.path
        } catch (t: Throwable) {
            error = t.message ?: "error"
        }
        loading = false
    }

    fun navigate(path: String) {
        if (loading || path.isBlank()) return
        pathInput = path
        reloadKey++
    }

    val canGoUp = !loading && current != null && current!!.parent.isNotBlank() && current!!.parent != current!!.path

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(S.workspaceDialogTitle) },
        text = {
            Column(Modifier.fillMaxWidth()) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    OutlinedTextField(
                        value = pathInput,
                        onValueChange = { pathInput = it },
                        label = { Text(S.workspaceLabel) },
                        placeholder = { Text(S.workspaceHint) },
                        singleLine = true,
                        modifier = Modifier.weight(1f),
                        keyboardOptions = KeyboardOptions(imeAction = ImeAction.Go),
                        keyboardActions = KeyboardActions(onGo = { navigate(pathInput.trim()) }),
                    )
                    IconButton(onClick = { navigate(current!!.parent) }, enabled = canGoUp) {
                        Icon(
                            Icons.Filled.ArrowUpward,
                            contentDescription = S.upLevel,
                            tint = if (canGoUp) MaterialTheme.colorScheme.primary
                            else MaterialTheme.colorScheme.outline,
                        )
                    }
                    val canCreate = !loading && current != null && error.isBlank()
                    IconButton(onClick = { showCreateFolder = true }, enabled = canCreate) {
                        Icon(
                            Icons.Filled.CreateNewFolder,
                            contentDescription = S.createFolder,
                            tint = if (canCreate) MaterialTheme.colorScheme.primary
                            else MaterialTheme.colorScheme.outline,
                        )
                    }
                }
                Spacer(Modifier.height(10.dp))
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(260.dp)
                        .clip(RoundedCornerShape(12.dp))
                        .background(MaterialTheme.colorScheme.surfaceContainerLow),
                ) {
                    when {
                        loading -> CircularProgressIndicator(
                            modifier = Modifier.align(Alignment.Center).size(28.dp),
                        )
                        error.isNotBlank() -> Text(
                            error,
                            modifier = Modifier.align(Alignment.Center).padding(16.dp),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.error,
                            textAlign = TextAlign.Center,
                        )
                        current?.dirs.isNullOrEmpty() -> Text(
                            S.noSubdirectories,
                            modifier = Modifier.align(Alignment.Center).padding(16.dp),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        else -> LazyColumn(Modifier.fillMaxSize()) {
                            items(current!!.dirs, key = { it.path }) { dir ->
                                Row(
                                    verticalAlignment = Alignment.CenterVertically,
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .clickable { navigate(dir.path) }
                                        .padding(horizontal = 12.dp, vertical = 9.dp),
                                ) {
                                    Icon(
                                        Icons.Filled.Folder,
                                        contentDescription = null,
                                        modifier = Modifier.size(17.dp),
                                        tint = MaterialTheme.colorScheme.primary,
                                    )
                                    Spacer(Modifier.size(10.dp))
                                    Text(
                                        dir.name,
                                        style = MaterialTheme.typography.bodyMedium,
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis,
                                    )
                                }
                            }
                        }
                    }
                }
            }
        },
        confirmButton = {
            TextButton(
                onClick = { onConfirm(pathInput.trim()); onDismiss() },
                enabled = pathInput.trim().isNotEmpty() && !loading,
            ) {
                Text(S.selectThisDirectory)
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text(S.cancel) }
        },
    )

    if (showCreateFolder) {
        CreateFolderDialog(
            onDismiss = { showCreateFolder = false },
            onConfirm = { name ->
                val base = current?.path ?: pathInput.trim()
                createDir(base, name)
                reloadKey++
            },
        )
    }
}

/** Name prompt for the workspace picker's new-folder action. */
@Composable
private fun CreateFolderDialog(
    onDismiss: () -> Unit,
    onConfirm: suspend (String) -> Unit,
) {
    var name by remember { mutableStateOf("") }
    var error by remember { mutableStateOf("") }
    var submitting by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()
    val trimmedName = name.trim()
    val valid =
        trimmedName.isNotEmpty() &&
            trimmedName != "." &&
            trimmedName != ".." &&
            '/' !in trimmedName &&
            '\\' !in trimmedName

    fun submit() {
        if (!valid || submitting) return
        submitting = true
        scope.launch {
            try {
                onConfirm(trimmedName)
                onDismiss()
            } catch (t: Throwable) {
                error = t.message ?: "error"
                submitting = false
            }
        }
    }

    AlertDialog(
        onDismissRequest = { if (!submitting) onDismiss() },
        title = { Text(S.createFolder) },
        text = {
            Column(Modifier.fillMaxWidth()) {
                OutlinedTextField(
                    value = name,
                    onValueChange = { name = it; error = "" },
                    label = { Text(S.folderNameLabel) },
                    singleLine = true,
                    isError = error.isNotBlank(),
                    modifier = Modifier.fillMaxWidth(),
                    keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
                    keyboardActions = KeyboardActions(onDone = { submit() }),
                )
                if (error.isNotBlank()) {
                    Spacer(Modifier.height(6.dp))
                    Text(
                        error,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.error,
                    )
                }
            }
        },
        confirmButton = {
            TextButton(onClick = { submit() }, enabled = valid && !submitting) {
                Text(S.confirm)
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss, enabled = !submitting) { Text(S.cancel) }
        },
    )
}

@Composable
fun ConfirmDialog(
    title: String,
    body: String,
    onDismiss: () -> Unit,
    onConfirm: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = { Text(body) },
        confirmButton = {
            TextButton(onClick = { onConfirm(); onDismiss() }) {
                Text(S.confirm, color = MaterialTheme.colorScheme.error)
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text(S.cancel) }
        },
    )
}

/** Dialog for pi extension UI requests (select / confirm / input / editor). */
@Composable
fun ExtensionDialog(
    request: UiDialogRequest,
    onRespond: (JsonObjectBuilder.() -> Unit) -> Unit,
) {
    var text by remember { mutableStateOf(request.prefill) }
    var selected by remember { mutableStateOf<String?>(null) }

    Dialog(onDismissRequest = { onRespond { put("cancelled", true) } }) {
        Surface(
            shape = RoundedCornerShape(24.dp),
            color = MaterialTheme.colorScheme.surfaceContainerHigh,
            modifier = Modifier.fillMaxWidth(),
        ) {
            Column(Modifier.padding(20.dp)) {
                Text(
                    request.title.ifBlank { request.method },
                    style = MaterialTheme.typography.titleMedium,
                )
                if (request.message.isNotBlank()) {
                    Spacer(Modifier.height(8.dp))
                    Text(
                        request.message,
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                Spacer(Modifier.height(14.dp))

                when (request.method) {
                    "select" -> Column(
                        Modifier.heightIn(max = 320.dp).verticalScroll(rememberScrollState()),
                    ) {
                        request.options.forEach { option ->
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .clickable {
                                        onRespond { put("value", option); this }
                                    }
                                    .padding(vertical = 6.dp),
                            ) {
                                RadioButton(selected = false, onClick = null, modifier = Modifier.size(20.dp))
                                Spacer(Modifier.size(10.dp))
                                Text(option, style = MaterialTheme.typography.bodyMedium)
                            }
                        }
                    }
                    "confirm" -> Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.End,
                    ) {
                        TextButton(onClick = { onRespond { put("cancelled", true) } }) {
                            Text(S.dialogCancel)
                        }
                        Spacer(Modifier.size(8.dp))
                        Button(onClick = { onRespond { put("confirmed", true) } }) {
                            Text(S.dialogOk)
                        }
                    }
                    else -> {
                        OutlinedTextField(
                            value = text,
                            onValueChange = { text = it },
                            modifier = Modifier
                                .fillMaxWidth()
                                .heightIn(min = if (request.method == "editor") 140.dp else 56.dp),
                            placeholder = { Text(request.placeholder.ifBlank { S.dialogInputHint }) },
                            minLines = if (request.method == "editor") 5 else 1,
                        )
                        Spacer(Modifier.height(14.dp))
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.End,
                        ) {
                            TextButton(onClick = { onRespond { put("cancelled", true) } }) {
                                Text(S.dialogCancel)
                            }
                            Spacer(Modifier.size(8.dp))
                            Button(onClick = { onRespond { put("value", text) } }) {
                                Text(S.dialogOk)
                            }
                        }
                    }
                }
            }
        }
    }
}
