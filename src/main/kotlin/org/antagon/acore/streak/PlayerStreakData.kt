package org.antagon.acore.streak

import java.time.LocalDate
import java.util.UUID

data class PlayerStreakData(
    val uuid: UUID,
    var currentStreak: Int = 0,
    var highestStreak: Int = 0,
    var totalLogins: Int = 0,
    var lastLoginDate: LocalDate? = null,
    var streakFreezes: Int = 0
) {
    @Volatile
    var isDirty: Boolean = false
}
