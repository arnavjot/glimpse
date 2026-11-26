package com.arnavjot.glimpse.server.common;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

import org.junit.jupiter.api.Test;
import org.springframework.dao.QueryTimeoutException;

import io.micrometer.core.instrument.simple.SimpleMeterRegistry;

class RedisBreakerTest {

    private final AtomicLong now = new AtomicLong(1_000_000);
    private final RedisBreaker breaker = new RedisBreaker(new SimpleMeterRegistry(), now::get);
    private final AtomicInteger calls = new AtomicInteger();

    private String down() {
        calls.incrementAndGet();
        throw new QueryTimeoutException("Redis command timed out");
    }

    private String up() {
        calls.incrementAndGet();
        return "ok";
    }

    @Test
    void afterOneTimeoutRedisIsSkippedInsteadOfWaitedOn() {
        assertThatThrownBy(() -> breaker.call(this::down)).isInstanceOf(QueryTimeoutException.class);
        for (int i = 0; i < 100; i++) {
            assertThatThrownBy(() -> breaker.call(this::down)).isInstanceOf(RedisBreaker.OpenException.class);
        }
        assertThat(calls).hasValue(1); // 100 requests, none of them waited on Redis
    }

    @Test
    void afterFiveSecondsOneRequestTriesAgainAndSuccessCloses() {
        assertThatThrownBy(() -> breaker.call(this::down));
        now.addAndGet(RedisBreaker.OPEN_MILLIS);
        assertThat(breaker.call(this::up)).isEqualTo("ok");
        assertThat(breaker.call(this::up)).isEqualTo("ok");
        assertThat(calls).hasValue(3);
    }

    @Test
    void aFailedRetryKeepsItOpenForAnotherRound() {
        assertThatThrownBy(() -> breaker.call(this::down));
        now.addAndGet(RedisBreaker.OPEN_MILLIS);
        assertThatThrownBy(() -> breaker.call(this::down)).isInstanceOf(QueryTimeoutException.class);
        assertThatThrownBy(() -> breaker.call(this::up)).isInstanceOf(RedisBreaker.OpenException.class);
        assertThat(calls).hasValue(2);
    }

    @Test
    void skippingLooksLikeAnyOtherRedisFailureToCallers() {
        assertThat(new RedisBreaker.OpenException()).isInstanceOf(org.springframework.dao.DataAccessException.class);
    }
}
