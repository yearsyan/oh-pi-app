package io.github.yearsyan.pi.data

import android.content.Context
import com.russhwolf.settings.Settings
import com.russhwolf.settings.SharedPreferencesSettings

/** Holds the Application context so the settings delegate can be constructed lazily. */
object AndroidAppContext {
    lateinit var context: Context

    fun init(context: Context) {
        this.context = context.applicationContext
    }
}

actual fun createSettings(): Settings =
    SharedPreferencesSettings(AndroidAppContext.context.getSharedPreferences("pi_app", Context.MODE_PRIVATE))
