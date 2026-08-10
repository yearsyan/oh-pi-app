package io.github.yearsyan.ohpi.data

import com.russhwolf.settings.Settings

internal actual fun createSshKeyStorage(settings: Settings): SshKeyStorage =
    SettingsSshKeyStorage(settings)

internal actual fun createServerSecretStorage(settings: Settings): ServerSecretStorage =
    InlineServerSecretStorage
