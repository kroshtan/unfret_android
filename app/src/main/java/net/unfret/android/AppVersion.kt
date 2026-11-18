package net.unfret.android

/**
 * Whether [versionName] is a beta: a version with a pre-release suffix, such as 0.1.0-beta.1 (the release workflow
 * publishes it as a pre-release, docs/RELEASING.md). The about screen says so.
 */
fun isBeta(versionName: String): Boolean = '-' in versionName
