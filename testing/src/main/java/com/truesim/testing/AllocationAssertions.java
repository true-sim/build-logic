package com.truesim.testing;

import java.lang.management.ManagementFactory;

/**
 * Asserts that code does not allocate on the heap once the JIT has compiled it.
 *
 * <p>Objects the JIT removes through escape analysis (for example a temporary vector that never
 * leaves the method) do not count, because they never reach the heap. The action is warmed up
 * first, then measured in rounds until a round allocates nothing.
 */
public final class AllocationAssertions {

    private static final com.sun.management.ThreadMXBean THREADS =
            (com.sun.management.ThreadMXBean) ManagementFactory.getThreadMXBean();

    public static final int DEFAULT_WARMUP_ITERATIONS = 50_000;
    public static final int DEFAULT_MEASURED_ITERATIONS = 10_000;
    public static final int DEFAULT_ROUNDS = 10;

    private AllocationAssertions() {
    }

    /** Asserts that {@code action} is allocation free, using the default iteration counts. */
    public static void assertAllocationFree(Runnable action) {
        assertAllocationFree(action, DEFAULT_WARMUP_ITERATIONS, DEFAULT_MEASURED_ITERATIONS, DEFAULT_ROUNDS);
    }

    /**
     * Asserts that {@code action} is allocation free.
     *
     * @param warmupIterations runs before measuring, so the JIT can compile the code
     * @param measuredIterations runs per measured round
     * @param rounds measured rounds to try before failing; one allocation-free round passes
     */
    public static void assertAllocationFree(Runnable action, int warmupIterations, int measuredIterations, int rounds) {
        if (!THREADS.isThreadAllocatedMemorySupported()) {
            throw new IllegalStateException("This JVM cannot measure per-thread allocations");
        }
        THREADS.setThreadAllocatedMemoryEnabled(true);

        repeat(action, warmupIterations);
        long allocated = 0;
        for (int round = 0; round < rounds; round++) {
            long before = THREADS.getCurrentThreadAllocatedBytes();
            repeat(action, measuredIterations);
            allocated = THREADS.getCurrentThreadAllocatedBytes() - before;
            if (allocated == 0) {
                return;
            }
        }
        throw new AssertionError(String.format(
                "Expected no heap allocations, but the last of %d rounds allocated %d bytes (%.1f bytes per call)",
                rounds, allocated, (double) allocated / measuredIterations));
    }

    private static void repeat(Runnable action, int iterations) {
        for (int i = 0; i < iterations; i++) {
            action.run();
        }
    }
}
