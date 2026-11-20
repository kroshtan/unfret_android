package net.unfret.android

/**
 * The free quick check, as in the extension (its popup and background, PR #58): Unfret reads only the page, by its
 * rules, without looking the shop up elsewhere; `"tier": "quick"`, free for everyone, with or without a pass.
 *
 * Before a check the screen offers the deep check ("Check this page", which uses a check: free ones first) and under
 * it, secondary, "Quick check (free)". When no deep check can run (no pass, no checks left, or only free ones while
 * today's are all used) the main button is the quick check, and it isn't offered twice. A deep check the server
 * refuses for want of checks becomes a quick one, and the result says why.
 */
object QuickCheck {
    enum class Main { DEEP, QUICK }

    /** Why the main button is the quick check, for the line above it; null when it is the deep check. */
    enum class Why { NO_PASS, NO_CHECKS, FREE_PAUSED }

    data class Offer(val main: Main, val why: Why?) {
        /** "Quick check (free)" under the main button: only while the main button is the deep check. */
        val quickOption: Boolean get() = main == Main.DEEP
    }

    /**
     * What the screen offers. [credits] is the pass's balance when known (it is asked for when the page opens);
     * [deepRefused] is the reason a deep check was refused on this screen (it stays true until the screen closes).
     */
    fun offer(hasPass: Boolean, credits: Credits?, deepRefused: Problem? = null): Offer {
        val why = when {
            !hasPass -> Why.NO_PASS
            deepRefused == Problem.FREE_PAUSED -> Why.FREE_PAUSED
            deepRefused == Problem.NO_CHECKS -> Why.NO_CHECKS
            credits == null || !credits.quickOnly -> null
            credits.checksRemaining == 0 -> Why.NO_CHECKS
            else -> Why.FREE_PAUSED
        }
        return Offer(if (why == null) Main.DEEP else Main.QUICK, why)
    }

    /** A deep check refused with [problem] becomes a quick one (the extension's `quickBecause`); others don't. */
    fun fallsBackToQuick(problem: Problem): Boolean = problem == Problem.NO_CHECKS || problem == Problem.FREE_PAUSED
}
