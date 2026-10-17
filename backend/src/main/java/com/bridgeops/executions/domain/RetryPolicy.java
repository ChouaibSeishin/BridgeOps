package com.bridgeops.executions.domain;

import java.time.Duration;
import java.util.function.DoubleSupplier;

public final class RetryPolicy {
    public static final int MAX_ATTEMPTS = 3;
    private final DoubleSupplier random;
    public RetryPolicy(DoubleSupplier random) { this.random = random; }
    public Duration delay(int completedAttempts) {
        if (completedAttempts < 1 || completedAttempts >= MAX_ATTEMPTS) throw new IllegalArgumentException("No further automatic attempt.");
        double sample = random.getAsDouble();
        if (!Double.isFinite(sample) || sample < 0 || sample >= 1) throw new IllegalStateException("Invalid randomness source.");
        return Duration.ofMillis((1000L << (completedAttempts - 1)) + (long)(sample * 501));
    }
}
