package io.github.yearsyan.ohpi

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.intl.Locale
import androidx.lifecycle.viewmodel.compose.viewModel
import io.github.yearsyan.ohpi.data.ThemeMode
import io.github.yearsyan.ohpi.i18n.LocalStrings
import io.github.yearsyan.ohpi.i18n.stringsFor
import io.github.yearsyan.ohpi.theme.PiTheme
import io.github.yearsyan.ohpi.ui.AppViewModel
import io.github.yearsyan.ohpi.ui.components.SshHostKeyDialog
import io.github.yearsyan.ohpi.ui.components.ToastHost
import io.github.yearsyan.ohpi.ui.screens.HomeScreen
import io.github.yearsyan.ohpi.ui.screens.OnboardingScreen

@Composable
fun App(
    vm: AppViewModel = viewModel { AppViewModel() },
    titleBar: (@Composable () -> Unit)? = null,
) {
    val systemDark = isSystemInDarkTheme()
    val dark = when (vm.themeMode) {
        ThemeMode.System -> systemDark
        ThemeMode.Light -> false
        ThemeMode.Dark -> true
    }
    val strings = stringsFor(vm.language, systemIsChinese = Locale.current.language == "zh")

    LaunchedEffect(strings) {
        vm.setStringsProvider { strings }
    }

    CompositionLocalProvider(LocalStrings provides strings) {
        PiTheme(darkTheme = dark) {
            Surface(
                modifier = Modifier.fillMaxSize(),
                color = MaterialTheme.colorScheme.background,
            ) {
                Box(Modifier.fillMaxSize()) {
                    Column(Modifier.fillMaxSize()) {
                        titleBar?.invoke()
                        Box(
                            modifier =
                                Modifier
                                    .fillMaxWidth()
                                    .weight(1f),
                        ) {
                            if (!vm.hasServers) {
                                OnboardingScreen(onSave = vm::saveServer)
                            } else {
                                HomeScreen(vm)
                            }
                        }
                    }
                    vm.sshHostKeyPrompt?.let { prompt ->
                        SshHostKeyDialog(
                            prompt = prompt,
                            onReject = { vm.answerSshHostKeyPrompt(false) },
                            onTrust = { vm.answerSshHostKeyPrompt(true) },
                        )
                    }
                    ToastHost(vm.toasts)
                }
            }
        }
    }
}
