package io.github.yearsyan.pi.data

import com.russhwolf.settings.PreferencesSettings
import com.russhwolf.settings.Settings
import java.util.prefs.Preferences

actual fun createSettings(): Settings =
    PreferencesSettings(Preferences.userRoot().node("io.github.yearsyan.pi"))
