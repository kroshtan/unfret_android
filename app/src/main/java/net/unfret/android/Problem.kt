package net.unfret.android

/** What went wrong, in the terms the user is told about (the extension's wording, never the server's detail). */
enum class Problem {
    /** Checks are switched off on the server for now (503). */
    SERVICE_OFF,

    /** Free deep checks are paused until next month: the month's spend ceiling (`FREE_CHECKS_PAUSED`). Quick checks work. */
    PAUSED,

    /** Too many checks from this network for now (429 `TOO_MANY_REQUESTS`): wait a moment. */
    TOO_MANY,

    /** The check failed on the server's side. */
    FAILED,

    /** Unfret couldn't be reached. */
    OFFLINE,
    ;

    companion object {
        fun of(error: Throwable): Problem = when {
            error !is ApiError -> OFFLINE
            error.code == UnfretApi.PAUSED -> PAUSED
            error.status == 503 -> SERVICE_OFF
            error.status == 429 -> TOO_MANY
            else -> FAILED
        }
    }
}
