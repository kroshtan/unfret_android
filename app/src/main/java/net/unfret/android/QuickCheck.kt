package net.unfret.android

/**
 * The deep check and the quick check, both free and without a pass (contract v1, "Free checks for shoppers").
 *
 * Before a check the screen offers the deep check ("Check this page") and under it, secondary, "Quick check". While
 * free deep checks are paused (the month's spend ceiling) the main button is the quick check, and it isn't offered
 * twice. A deep check the server can't run now (paused, too many from this network, unavailable) becomes a quick one,
 * and the result says why.
 */
object QuickCheck {
    enum class Main { DEEP, QUICK }

    data class Offer(val main: Main, val paused: Boolean) {
        /** "Quick check" under the main button: only while the main button is the deep check. */
        val quickOption: Boolean get() = main == Main.DEEP
    }

    /**
     * What the screen offers. [paused]: free deep checks are paused, as `GET /v1/pricing` said when the page opened or
     * a deep check found on this screen.
     */
    fun offer(paused: Boolean) = Offer(if (paused) Main.QUICK else Main.DEEP, paused)

    /** A deep check refused with [problem] becomes a quick one (the extension's `quickBecause`); others don't. */
    fun fallsBackToQuick(problem: Problem): Boolean =
        problem == Problem.PAUSED || problem == Problem.TOO_MANY || problem == Problem.SERVICE_OFF
}
