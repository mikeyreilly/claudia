package com.quaxt.codingagent.debug;

import java.nio.file.Files;
import java.nio.file.Path;

/** JDWP target: waits for the test's gate so breakpoint setup cannot race execution. */
public final class DebugFixture {
    private DebugFixture() {}

    public static void main(String[] args) throws Exception {
        Path gate = Path.of(args[0]);
        System.out.println("DEBUG_FIXTURE_READY");
        if (System.getenv("DEBUG_SECRET") != null)
            System.out.println("DEBUG_SECRET_VALUE=" + System.getenv("DEBUG_SECRET"));
        long deadline = System.nanoTime() + java.util.concurrent.TimeUnit.SECONDS.toNanos(30);
        while (!Files.exists(gate) && System.nanoTime() < deadline) Thread.sleep(20);
        if (!Files.exists(gate)) throw new IllegalStateException("Test never opened fixture gate");
        System.out.println("DEBUG_FIXTURE_DONE=" + compute(40));
        if (args.length > 1 && args[1].equals("throw"))
            throw new IllegalStateException("fixture failure", new IllegalArgumentException("fixture cause"));
    }

    private static int compute(int seed) {
        Sample sample = new Sample(seed);
        int[] numbers = {seed, seed + 2};
        int answer = sample.count + numbers[1] - seed; // DEBUG_FIXTURE_BREAKPOINT
        return answer;
    }

    private static final class Sample {
        final int count;
        final String secretToken = "fixture-sensitive-value";
        Sample(int count) { this.count = count; }
    }
}
