package io.github.yearsyan.ohpi.ui.screens

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class ScheduledTaskWeeklyScheduleTest {
    @Test
    fun createsStandardCronFromWeekdaysAndTime() {
        val expression = scheduledTaskWeeklyScheduleToCron(
            ScheduledTaskWeeklySchedule(
                weekdays = setOf(1, 3, 5),
                hour = 9,
                minute = 30,
            ),
        )

        assertEquals("30 9 * * 1,3,5", expression)
    }

    @Test
    fun usesWildcardWhenEveryDayIsSelected() {
        val expression = scheduledTaskWeeklyScheduleToCron(
            ScheduledTaskWeeklySchedule(
                weekdays = (1..7).toSet(),
                hour = 0,
                minute = 5,
            ),
        )

        assertEquals("5 0 * * *", expression)
    }

    @Test
    fun parsesRangesNamesAndSunday() {
        assertEquals(
            ScheduledTaskWeeklySchedule((1..5).toSet(), hour = 9, minute = 0),
            parseScheduledTaskWeeklyCron("0 9 * * MON-FRI"),
        )
        assertEquals(
            ScheduledTaskWeeklySchedule(setOf(2, 6, 7), hour = 8, minute = 15),
            parseScheduledTaskWeeklyCron("15 8 * * 0,2,6"),
        )
    }

    @Test
    fun rejectsCronThatCannotBeRepresentedByTheForm() {
        assertNull(parseScheduledTaskWeeklyCron("*/15 9 * * 1-5"))
        assertNull(parseScheduledTaskWeeklyCron("0 9 1 * *"))
        assertNull(parseScheduledTaskWeeklyCron("0 9 * * 1-5/2"))
    }

    @Test
    fun parsesAndDeduplicatesTaskSkillPaths() {
        assertEquals(
            listOf("skills/task", "/srv/shared-skills"),
            parseScheduledTaskSkillPaths("  skills/task  \n\n/srv/shared-skills\nskills/task"),
        )
    }
}
