package com.android.wificalllocal.util;

/**
 * Hybrid Logical Clock (HLC) for causal ordering of distributed offline messages
 * across devices whose wall clocks may drift slightly.
 */
public class HybridLogicalClock {

    private long physicalTime = 0L;
    private int counter = 0;

    public static class Timestamp {
        public final long physical;
        public final int counter;

        public Timestamp(long physical, int counter) {
            this.physical = physical;
            this.counter = counter;
        }
    }

    public synchronized Timestamp nextLocal() {
        long wall = System.currentTimeMillis();
        if (wall > physicalTime) {
            physicalTime = wall;
            counter = 0;
        } else {
            counter++;
        }
        return new Timestamp(physicalTime, counter);
    }

    public synchronized Timestamp mergeRemote(long remotePhysical, int remoteCounter) {
        long wall = System.currentTimeMillis();
        long prevPhysical = physicalTime;
        physicalTime = Math.max(Math.max(prevPhysical, remotePhysical), wall);

        if (physicalTime == prevPhysical && physicalTime == remotePhysical) {
            counter = Math.max(counter, remoteCounter) + 1;
        } else if (physicalTime == prevPhysical) {
            counter = counter + 1;
        } else if (physicalTime == remotePhysical) {
            counter = remoteCounter + 1;
        } else {
            counter = 0;
        }
        return new Timestamp(physicalTime, counter);
    }

    public static int compare(long p1, int c1, long p2, int c2) {
        int cmp = Long.compare(p1, p2);
        return cmp != 0 ? cmp : Integer.compare(c1, c2);
    }
}
