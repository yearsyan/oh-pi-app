package io.github.yearsyan.ohpi.ui.components

import kotlin.test.Test
import kotlin.test.assertEquals

class AppDropdownMenuTest {
    @Test
    fun menuSectionsPreserveOrderAndDividerBoundaries() {
        val items =
            listOf(
                AppMenuItem(id = "one", title = "One"),
                AppMenuItem(id = "two", title = "Two", startsSection = true),
                AppMenuItem(id = "three", title = "Three"),
            )
        val sectionIds = items.menuSections().map { section -> section.map(AppMenuItem::id) }

        assertEquals(listOf(listOf("one"), listOf("two", "three")), sectionIds)
    }

    @Test
    fun menuSectionsIgnoreLeadingDivider() {
        val items = listOf(AppMenuItem(id = "one", title = "One", startsSection = true))
        val sectionIds = items.menuSections().map { section -> section.map(AppMenuItem::id) }

        assertEquals(listOf(listOf("one")), sectionIds)
    }
}
