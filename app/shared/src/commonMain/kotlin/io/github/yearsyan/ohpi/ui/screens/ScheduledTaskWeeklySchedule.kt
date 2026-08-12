package io.github.yearsyan.ohpi.ui.screens

internal data class ScheduledTaskWeeklySchedule(
    /** ISO weekday numbers: Monday is 1 and Sunday is 7. */
    val weekdays: Set<Int>,
    val hour: Int,
    val minute: Int,
)

internal fun scheduledTaskWeeklyScheduleToCron(schedule: ScheduledTaskWeeklySchedule): String {
    require(schedule.weekdays.isNotEmpty() && schedule.weekdays.all { it in 1..7 })
    require(schedule.hour in 0..23)
    require(schedule.minute in 0..59)

    val weekdayField =
        if (schedule.weekdays.size == 7) {
            "*"
        } else {
            schedule.weekdays
                .map { weekday -> if (weekday == 7) 0 else weekday }
                .sorted()
                .joinToString(",")
        }
    return "${schedule.minute} ${schedule.hour} * * $weekdayField"
}

internal fun parseScheduledTaskWeeklyCron(expression: String): ScheduledTaskWeeklySchedule? {
    val fields = expression.trim().split(Regex("\\s+"))
    if (fields.size != 5 || fields[2] != "*" || fields[3] != "*") return null

    val minute = fields[0].toIntOrNull()?.takeIf { it in 0..59 } ?: return null
    val hour = fields[1].toIntOrNull()?.takeIf { it in 0..23 } ?: return null
    val weekdays = parseCronWeekdays(fields[4]) ?: return null
    return ScheduledTaskWeeklySchedule(weekdays = weekdays, hour = hour, minute = minute)
}

private fun parseCronWeekdays(field: String): Set<Int>? {
    if (field == "*") return (1..7).toSet()
    if (field.isBlank()) return null

    val weekdays = mutableSetOf<Int>()
    for (part in field.split(',')) {
        if (part.isBlank()) return null
        val bounds = part.split('-')
        when (bounds.size) {
            1 -> weekdays += cronWeekdayToIso(parseCronWeekday(bounds[0]) ?: return null)
            2 -> {
                val start = parseCronWeekday(bounds[0]) ?: return null
                val end = parseCronWeekday(bounds[1]) ?: return null
                if (start > end) return null
                for (weekday in start..end) weekdays += cronWeekdayToIso(weekday)
            }
            else -> return null
        }
    }
    return weekdays.takeIf { it.isNotEmpty() }
}

private fun parseCronWeekday(value: String): Int? =
    when (value.trim().uppercase()) {
        "SUN" -> 0
        "MON" -> 1
        "TUE" -> 2
        "WED" -> 3
        "THU" -> 4
        "FRI" -> 5
        "SAT" -> 6
        else -> value.toIntOrNull()?.takeIf { it in 0..7 }
    }

private fun cronWeekdayToIso(weekday: Int): Int = if (weekday == 0 || weekday == 7) 7 else weekday
