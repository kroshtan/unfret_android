package net.unfret.android

/**
 * A finished step of a streamed check: the step's name, and whether it is one of the checks the result's
 * "What we checked" counts (null from an older server, which doesn't say).
 */
data class Step(val skill: String, val check: Boolean?)

/**
 * The progress line while checking. A server that says which steps are checks gets "Checking… 7 checks done", the
 * same unit and, at the end, the same number as the result's "What we checked: 7 checks in 4 areas"; reading the
 * page, classifying it and triage are steps, not checks. An older server's steps are counted as steps.
 */
class Progress {
    var steps = 0
        private set

    /** The checks done; null while no step has said whether it is one (an older server). */
    var checks: Int? = null
        private set

    fun add(step: Step) {
        steps++
        step.check?.let { checks = (checks ?: 0) + if (it) 1 else 0 }
    }
}
