package io.github.yearsyan.pi.ui.components

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import io.github.yearsyan.pi.chat.Toast

@Composable
fun ToastHost(toasts: List<Toast>) {
    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.BottomCenter) {
        Column(
            modifier = Modifier.padding(bottom = 96.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            toasts.forEach { toast ->
                AnimatedVisibility(
                    visible = true,
                    enter = fadeIn() + slideInVertically { it / 2 },
                    exit = fadeOut() + slideOutVertically { it / 2 },
                ) {
                    Surface(
                        color = when (toast.kind) {
                            Toast.Kind.Error -> MaterialTheme.colorScheme.errorContainer
                            Toast.Kind.Success -> MaterialTheme.colorScheme.tertiaryContainer
                            Toast.Kind.Info -> MaterialTheme.colorScheme.inverseSurface
                        },
                        shape = RoundedCornerShape(20.dp),
                        shadowElevation = 6.dp,
                    ) {
                        Text(
                            toast.text,
                            modifier = Modifier.padding(horizontal = 16.dp, vertical = 9.dp).widthIn(max = 420.dp),
                            style = MaterialTheme.typography.labelLarge,
                            color = when (toast.kind) {
                                Toast.Kind.Error -> MaterialTheme.colorScheme.onErrorContainer
                                Toast.Kind.Success -> MaterialTheme.colorScheme.onTertiaryContainer
                                Toast.Kind.Info -> MaterialTheme.colorScheme.inverseOnSurface
                            },
                        )
                    }
                }
            }
        }
    }
}
