package io.github.yearsyan.pi

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.intl.Locale
import androidx.lifecycle.viewmodel.compose.viewModel
import io.github.yearsyan.pi.data.ThemeMode
import io.github.yearsyan.pi.i18n.LocalStrings
import io.github.yearsyan.pi.i18n.stringsFor
import io.github.yearsyan.pi.theme.PiTheme
import io.github.yearsyan.pi.ui.AppViewModel
import io.github.yearsyan.pi.ui.components.ToastHost
import io.github.yearsyan.pi.ui.screens.HomeScreen
import io.github.yearsyan.pi.ui.screens.OnboardingScreen

@Composable
fun App(vm: AppViewModel = viewModel { AppViewModel() }) {
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
                if (!vm.hasServers) {
                    OnboardingScreen(onSave = vm::saveServer)
                } else {
                    HomeScreen(vm)
                }
                ToastHost(vm.toasts)
            }
        }
    }
}
