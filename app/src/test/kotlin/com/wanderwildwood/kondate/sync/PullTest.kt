package com.wanderwildwood.kondate.sync

import org.junit.Assert.assertEquals
import org.junit.Test

class PullTest {
    @Test
    fun aCleanSyncIsUpToDate() {
        assertEquals(Pulled.UP_TO_DATE, pulledAfter(Cloud.State.IDLE))
    }

    @Test
    fun eachTroubleIsSaidAsItself() {
        assertEquals(Pulled.UNREACHABLE, pulledAfter(Cloud.State.UNREACHABLE))
        assertEquals(Pulled.REFUSED, pulledAfter(Cloud.State.REFUSED))
        assertEquals(Pulled.FAILED, pulledAfter(Cloud.State.FAILED))
    }

    @Test
    fun signedOutWhileItRanIsNoTrouble() {
        assertEquals(Pulled.UP_TO_DATE, pulledAfter(Cloud.State.OFF))
    }
}
