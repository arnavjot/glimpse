package com.arnavjot.glimpse.server.common;

import java.util.concurrent.atomic.AtomicLong;
import java.util.function.LongSupplier;
import java.util.function.Supplier;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.stereotype.Component;

import io.micrometer.core.instrument.MeterRegistry;

/**
 * A circuit breaker for Redis. Without it, every request waits out the 500 ms command timeout
 * while Redis is down, once per Redis call: a feed page (cache read + write) took 1 s and a
 * sign-in 1.4 s in the drill. With it, the first failure opens the breaker and for the next
 * {@link #OPEN_MILLIS} every Redis call is skipped at once (treated as "unavailable", the
 * same path as a timeout). Then one request tries Redis again: success closes the breaker,
 * failure keeps it open for another round.
 */
@Component
public class RedisBreaker {

    /**
     * Thrown instead of calling Redis while the breaker is open. A DataAccessException, so
     * callers handle it exactly like a Redis timeout.
     */
    public static class OpenException extends DataAccessResourceFailureException {
        OpenException() {
            super("Redis marked unavailable; skipped");
        }
    }

    static final long OPEN_MILLIS = 5_000;

    /** 0 = closed; otherwise Redis is skipped until this time (ms). */
    private final AtomicLong openUntil = new AtomicLong();

    private final LongSupplier clock;

    @Autowired
    public RedisBreaker(MeterRegistry meters) {
        this(meters, System::currentTimeMillis);
    }

    RedisBreaker(MeterRegistry meters, LongSupplier clock) {
        this.clock = clock;
        meters.gauge("glimpse.redis.breaker.open", openUntil, o -> o.get() == 0 ? 0 : 1);
    }

    /**
     * Runs {@code op} against Redis unless the breaker is open.
     *
     * @throws OpenException if Redis is being skipped
     * @throws RuntimeException whatever {@code op} threw (the breaker opens)
     */
    public <T> T call(Supplier<T> op) {
        long until = openUntil.get();
        if (until != 0) {
            long now = clock.getAsLong();
            // Still open, or another request already claimed the trial call.
            if (now < until || !openUntil.compareAndSet(until, now + OPEN_MILLIS)) throw new OpenException();
        }
        try {
            T result = op.get();
            openUntil.set(0);
            return result;
        } catch (RuntimeException e) {
            openUntil.set(clock.getAsLong() + OPEN_MILLIS);
            throw e;
        }
    }

    public void run(Runnable op) {
        call(() -> {
            op.run();
            return null;
        });
    }

    /** For tests: forget past failures. */
    public void reset() {
        openUntil.set(0);
    }
}
