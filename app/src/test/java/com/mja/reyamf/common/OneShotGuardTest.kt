package com.mja.reyamf.common

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class OneShotGuardTest {

    @Test
    fun runOnce_runsTheBlockOnlyOnce() {
        val guard = OneShotGuard()
        var runs = 0

        assertTrue(guard.runOnce { runs++ })
        assertFalse(guard.runOnce { runs++ })
        assertFalse(guard.runOnce { runs++ })

        assertEquals(1, runs)
        assertTrue(guard.isUsed)
    }

    /** A failing teardown step must not be retried on the next callback. */
    @Test
    fun runOnce_marksUsedEvenWhenTheBlockThrows() {
        val guard = OneShotGuard()
        var runs = 0

        val failure = runCatching {
            guard.runOnce {
                runs++
                throw IllegalStateException("view already detached")
            }
        }

        assertTrue(failure.isFailure)
        assertFalse(guard.runOnce { runs++ })
        assertEquals(1, runs)
    }
}
