package io.github.yearsyan.pi.ui.screens

import kotlinx.serialization.serializer
import kotlin.reflect.typeOf
import kotlin.test.Test

class NavigationRouteSerializationTest {
    @Test
    fun runtimeSerializerCanAccessNavigationRoutes() {
        serializer(typeOf<SessionListRoute>())
        serializer(typeOf<ChatRoute>())
        serializer(typeOf<SettingsRoute>())
    }
}
