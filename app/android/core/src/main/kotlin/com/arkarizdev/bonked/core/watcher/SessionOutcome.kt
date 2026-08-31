package com.arkarizdev.bonked.core.watcher

/**
 * T-111 / PRD P0-5: "estimate beaten = session ends at or under granted
 * time with no extension." Any extension disqualifies BEATEN permanently
 * for that session, even if the user then respects the new deadline —
 * checked before the beaten-vs-overage split in SessionStateMachine.
 */
enum class SessionOutcome { BEATEN, OVERAGE, EXTENDED }

/**
 * One session that just finalized, queued by SessionStateMachine and
 * drained by WatcherService into a Room UPDATE (endedAt/outcome/overageS)
 * — the row survives as history instead of being deleted, the first time
 * that's true for this table.
 */
data class FinishedSession(
    val pkg: String,
    val endedAt: Long,
    val outcome: SessionOutcome,
    val overageS: Int?,
    val extensions: Int,
    /**
     * True if this session actually reached ROASTING (the overlay was
     * shown) before finalizing — via markDone or an app-switch away while
     * ROASTING. False for the RUNNING->ENDING grace-timeout path, where
     * the user left before expiry and the roast never appeared. Outcome
     * alone can't distinguish these anymore: a markDone tap with no prior
     * extension now finalizes BEATEN too, even though the roast WAS shown.
     */
    val roastShown: Boolean,
)
