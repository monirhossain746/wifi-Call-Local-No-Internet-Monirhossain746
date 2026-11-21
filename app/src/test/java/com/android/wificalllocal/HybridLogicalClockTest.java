package com.android.wificalllocal;

import com.android.wificalllocal.util.HybridLogicalClock;

import org.junit.Test;

import static org.junit.Assert.assertTrue;

public class HybridLogicalClockTest {

    @Test
    public void testLocalTickIsStrictlyMonotonic() {
        HybridLogicalClock clock = new HybridLogicalClock();
        HybridLogicalClock.Timestamp prev = clock.nextLocal();
        for (int i = 0; i < 50; i++) {
            HybridLogicalClock.Timestamp next = clock.nextLocal();
            assertTrue(HybridLogicalClock.compare(next.physical, next.counter, prev.physical, prev.counter) > 0);
            prev = next;
        }
    }

    @Test
    public void testRemoteMergeAdvancesAheadOfFutureRemoteClock() {
        HybridLogicalClock clock = new HybridLogicalClock();
        HybridLogicalClock.Timestamp local = clock.nextLocal();
        long futurePhysical = System.currentTimeMillis() + 30_000L;
        HybridLogicalClock.Timestamp merged = clock.mergeRemote(futurePhysical, 7);

        assertTrue(HybridLogicalClock.compare(merged.physical, merged.counter, futurePhysical, 7) > 0);
        assertTrue(HybridLogicalClock.compare(merged.physical, merged.counter, local.physical, local.counter) > 0);
    }
}
