package com.arkarizdev.bonked.core.watcher

/**
 * In-memory session record for one watched package. This is intentionally
 * NOT Room-backed — persistence across process death is T-103/T-108's job.
 * T-102 only needs the state machine to be correct while the service is
 * alive; a killed-and-restarted service simply starts every package back
 * at IDLE until T-108 wires reload-from-Room.
 */
data class Session(
    val pkg: String,
    var state: SessionState = SessionState.IDLE,
    /** Captured for analytics/reflection only — no roast template reads this. See RoastEngine's doc comment. */
    var intentText: String? = null,
    var grantedMin: Int? = null,
    var expiryAt: Long? = null,
    var extensions: Int = 0,
    var endingSince: Long? = null,
)
