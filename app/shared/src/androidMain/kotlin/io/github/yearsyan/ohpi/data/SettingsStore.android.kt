package io.github.yearsyan.ohpi.data

import android.content.Context
import com.russhwolf.settings.Settings
import com.russhwolf.settings.SharedPreferencesSettings

private const val SETTINGS_FILE_NAME = "oh-pi-app"

/** Holds the Application context so the settings delegate can be constructed lazily. */
object AndroidAppContext {
    lateinit var context: Context

    fun init(context: Context) {
        this.context = context.applicationContext
    }
}

actual fun createSettings(): Settings =
    SharedPreferencesSettings(
        AndroidAppContext.context.getSharedPreferences(SETTINGS_FILE_NAME, Context.MODE_PRIVATE),
    )
