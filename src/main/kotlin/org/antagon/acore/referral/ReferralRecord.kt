package org.antagon.acore.referral

import java.util.UUID

data class ReferralRecord(
    val referralUuid: UUID,
    val inviterUuid: UUID,
    var startTime: Long = 0L,
    var isRewarded: Boolean = false,
    @Volatile var isDirty: Boolean = false
)
