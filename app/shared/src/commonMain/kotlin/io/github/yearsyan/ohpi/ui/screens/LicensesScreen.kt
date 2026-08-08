package io.github.yearsyan.ohpi.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import io.github.yearsyan.ohpi.getPlatform
import io.github.yearsyan.ohpi.i18n.S
import io.github.yearsyan.ohpi.licenses.APACHE_2_0_TEXT
import io.github.yearsyan.ohpi.licenses.LGPL_2_1_TEXT
import io.github.yearsyan.ohpi.licenses.MIT_TEXT
import io.github.yearsyan.ohpi.licenses.OssCategory
import io.github.yearsyan.ohpi.licenses.OssComponent
import io.github.yearsyan.ohpi.licenses.OssLicense
import io.github.yearsyan.ohpi.licenses.ossComponentsFor
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.ChevronRight

/**
 * Open-source licenses / acknowledgements screen.
 *
 * Surfaced from Settings → About so the app complies with the attribution
 * requirements of the bundled OSS licenses (Apache 2.0, MIT, LGPL 2.1+) when
 * submitting to app stores such as Apple App Store.
 */
@Composable
fun LicensesScreen(onBack: (() -> Unit)?) {
    var viewing by remember { mutableStateOf<OssLicense?>(null) }
    val platformComponents = ossComponentsFor(getPlatform().target)

    Column(
        Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background)
            .safeDrawingPadding(),
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier.padding(horizontal = 4.dp, vertical = 6.dp),
        ) {
            if (onBack != null) {
                IconButton(onClick = onBack) {
                    Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = S.back)
                }
            } else {
                Spacer(Modifier.width(16.dp))
            }
            Text(
                S.openSourceLicenses,
                style = MaterialTheme.typography.titleLarge,
                fontWeight = FontWeight.SemiBold,
            )
        }
        HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)

        Column(
            Modifier
                .widthIn(max = 640.dp)
                .fillMaxWidth()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 16.dp, vertical = 12.dp)
                .align(Alignment.CenterHorizontally),
        ) {
            Text(
                S.licensesIntro,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(Modifier.height(20.dp))

            OssCategory.entries.forEach { category ->
                val components = platformComponents.filter { it.category == category }
                if (components.isEmpty()) return@forEach
                SectionHeader(categoryHeader(category))
                components.forEach { component ->
                    ComponentRow(component) { viewing = component.license }
                    Spacer(Modifier.height(8.dp))
                }
                Spacer(Modifier.height(16.dp))
            }

            // LGPL written-offer / relinking notice.
            SectionHeader(S.licensesRelinkingTitle)
            Surface(
                color = MaterialTheme.colorScheme.surfaceContainerLow,
                shape = RoundedCornerShape(16.dp),
                modifier = Modifier.fillMaxWidth(),
            ) {
                Text(
                    S.licensesRelinkingBody,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(14.dp),
                )
            }
            Spacer(Modifier.height(48.dp))
        }
    }

    viewing?.let { license ->
        LicenseTextDialog(license = license, onDismiss = { viewing = null })
    }
}

@Composable
private fun SectionHeader(text: String) {
    Text(
        text,
        style = MaterialTheme.typography.labelLarge,
        color = MaterialTheme.colorScheme.primary,
        fontWeight = FontWeight.SemiBold,
        modifier = Modifier.padding(bottom = 10.dp),
    )
}

@Composable
private fun ComponentRow(
    component: OssComponent,
    onClick: () -> Unit,
) {
    Surface(
        color = MaterialTheme.colorScheme.surfaceContainerLow,
        shape = RoundedCornerShape(16.dp),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier
                .clickable(onClick = onClick)
                .padding(horizontal = 14.dp, vertical = 12.dp),
        ) {
            Column(Modifier.weight(1f)) {
                Text(
                    component.name,
                    style = MaterialTheme.typography.bodyMedium,
                    fontWeight = FontWeight.SemiBold,
                )
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        "${S.licensesVersion} ${component.version}",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    component.copyright.takeIf { it.isNotBlank() }?.let { c ->
                        Text(
                            " · $c",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            maxLines = 1,
                        )
                    }
                }
                Text(
                    licenseLabel(component.license),
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.padding(top = 2.dp),
                )
            }
            Icon(
                Icons.Filled.ChevronRight,
                contentDescription = S.licenseFullText,
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.size(20.dp),
            )
        }
    }
}

@Composable
private fun LicenseTextDialog(license: OssLicense, onDismiss: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(licenseLabel(license)) },
        text = {
            Column(
                Modifier
                    .fillMaxWidth()
                    .verticalScroll(rememberScrollState()),
            ) {
                Text(
                    license.fullText,
                    style = MaterialTheme.typography.bodySmall,
                )
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss) { Text(S.dialogOk) }
        },
    )
}

@Composable
private fun categoryHeader(category: OssCategory): String = when (category) {
    OssCategory.Framework -> S.licensesFrameworks
    OssCategory.Native -> S.licensesNative
    OssCategory.App -> S.licensesThisApp
}

@Composable
private fun licenseLabel(license: OssLicense): String = when (license) {
    OssLicense.Apache2 -> S.licenseApache20
    OssLicense.Mit -> S.licenseMit
    OssLicense.Lgpl21OrLater -> S.licenseLgpl21
    OssLicense.Ofl11 -> S.licenseOfl11
}
