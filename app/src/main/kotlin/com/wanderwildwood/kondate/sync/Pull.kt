package com.wanderwildwood.kondate.sync

/**
 * What the line above the recipe list says once a swipe down at its top has asked the Nextcloud
 * again: syncing while it runs, then up to date or what went wrong, for a few seconds.
 */
enum class Pulled { SYNCING, UP_TO_DATE, UNREACHABLE, REFUSED, FAILED }

/** What a finished sync, which left [state] behind, has to say to the one who pulled for it. */
fun pulledAfter(state: Cloud.State): Pulled = when (state) {
    Cloud.State.UNREACHABLE -> Pulled.UNREACHABLE
    Cloud.State.REFUSED -> Pulled.REFUSED
    Cloud.State.FAILED -> Pulled.FAILED
    // Signed out while it ran, or still running another: nothing went wrong that the pull caused.
    Cloud.State.OFF, Cloud.State.IDLE, Cloud.State.SYNCING -> Pulled.UP_TO_DATE
}

/** How long the line stays after a pull, in milliseconds. */
const val PULLED_LINE_MS = 3_000L
