package com.truesim.testing;

import java.lang.management.ManagementFactory;

/**
 * Asserts that code does not allocate on the heap once the JIT has compiled it.
 *
 * <p>Objects the JIT removes through escape analysis (for example a temporary vector that never
 * leaves the method) do not count, because they never reach the heap. The action is warmed up
 * first, then measured in rounds until {@value #CLEAN_ROUNDS} rounds in a row allocate nothing:
 * rounds while the JIT still compiles may allocate, and an allocation that comes back every few
 * rounds (a buffer growing, a cache refilling) breaks the run of clean rounds and fails.
 *
 * <p>Allocations are measured on the calling thread, and on the {@code workers} given, for actions
 * that hand work to other threads (a job system, parallel recording).
 */
public final class AllocationAssertions {

    private static final com.sun.management.ThreadMXBean THREADS =
            (com.sun.management.ThreadMXBean) ManagementFactory.getThreadMXBean();

    public static final int DEFAULT_WARMUP_ITERATIONS = 50_000;
    public static final int DEFAULT_MEASURED_ITERATIONS = 10_000;
    public static final int DEFAULT_ROUNDS = 10;
    /** Clean rounds in a row that pass. */
    public static final int CLEAN_ROUNDS = 3;

    private AllocationAssertions() {
    }

    /** Asserts that {@code action} is allocation free, using the default iteration counts. */
    public static void assertAllocationFree(Runnable action) {
        assertAllocationFree(action, DEFAULT_WARMUP_ITERATIONS, DEFAULT_MEASURED_ITERATIONS, DEFAULT_ROUNDS);
    }

    /**
     * Asserts that {@code action} allocates nothing on the calling thread or on {@code workers}, using the default
     * iteration counts.
     */
    public static void assertAllocationFree(Runnable action, Thread... workers) {
        assertAllocationFree(action, DEFAULT_WARMUP_ITERATIONS, DEFAULT_MEASURED_ITERATIONS, DEFAULT_ROUNDS, workers);
    }

    /**
     * Asserts that {@code action} is allocation free.
     *
     * @param warmupIterations runs before measuring, so the JIT can compile the code
     * @param measuredIterations runs per measured round
     * @param rounds measured rounds to try before failing; {@value #CLEAN_ROUNDS} allocation-free
     *     rounds in a row pass (at least that many are run)
     * @param workers other threads the action runs code on, measured too
     */
    @SuppressWarnings("deprecation") // Thread.getId: this module targets a release before threadId()
    public static void assertAllocationFree(Runnable action, int warmupIterations, int measuredIterations, int rounds,
            Thread... workers) {
        if (!THREADS.isThreadAllocatedMemorySupported()) {
            throw new IllegalStateException("This JVM cannot measure per-thread allocations");
        }
        THREADS.setThreadAllocatedMemoryEnabled(true);
        long[] ids = new long[workers.length];
        for (int i = 0; i < workers.length; i++) {
            ids[i] = workers[i].getId();
        }

        repeat(action, warmupIterations);
        int total = Math.max(rounds, CLEAN_ROUNDS);
        int clean = 0;
        int allocating = 0;
        long most = 0;
        for (int round = 0; round < total; round++) {
            // The workers are read outside the calling thread's window: reading them allocates on this thread.
            long workersBefore = allocated(ids);
            long before = THREADS.getCurrentThreadAllocatedBytes();
            repeat(action, measuredIterations);
            long allocated = THREADS.getCurrentThreadAllocatedBytes() - before;
            allocated += allocated(ids) - workersBefore;
            clean = allocated == 0 ? clean + 1 : 0;
            if (clean == CLEAN_ROUNDS) {
                return;
            }
            if (allocated > 0) {
                allocating++;
                most = Math.max(most, allocated);
            }
        }
        throw new AssertionError(String.format(
                "Expected %d rounds in a row without heap allocations, but %d of %d rounds allocated, up to %d bytes"
                        + " (%.1f bytes per call)",
                CLEAN_ROUNDS, allocating, total, most, (double) most / measuredIterations));
    }

    /** Bytes allocated so far by the threads of {@code ids}; threads that have ended count 0. */
    private static long allocated(long[] ids) {
        if (ids.length == 0) {
            return 0;
        }
        long sum = 0;
        for (long bytes : THREADS.getThreadAllocatedBytes(ids)) {
            sum += Math.max(0, bytes);
        }
        return sum;
    }

    private static void repeat(Runnable action, int iterations) {
        for (int i = 0; i < iterations; i++) {
            action.run();
        }
    }
}
