package net.unfret.android

/** What went wrong, in the terms the user is told about (the extension's wording, never the server's detail). */
enum class Problem {
    /** Checks are switched off on the server for now. */
    SERVICE_OFF,

    /** The server doesn't know the key (mistyped, or replaced by its recovery code). */
    BAD_KEY,

    /** No checks left on the key. */
    NO_CHECKS,

    /** Only free checks left, and today's free deep checks are all used: back tomorrow (quick checks work). */
    FREE_PAUSED,

    /** The check failed on the server's side; it gives the check back. */
    FAILED,

    /** Unfret couldn't be reached. */
    OFFLINE,
    ;

    companion object {
        fun of(error: Throwable): Problem = when {
            error !is ApiError -> OFFLINE
            error.serviceOff -> SERVICE_OFF
            error.badKey -> BAD_KEY
            error.needsChecks -> NO_CHECKS
            error.freePaused -> FREE_PAUSED
            else -> FAILED
        }
    }
}
