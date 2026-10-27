package com.kama.jchatmind.service;

import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;

import static org.junit.jupiter.api.Assertions.*;

class LoginRateLimiterTest {

    private static final class MutableClock extends Clock {
        private Instant now = Instant.parse("2026-10-27T10:00:00Z");

        void advance(Duration duration) {
            now = now.plus(duration);
        }

        @Override
        public ZoneId getZone() {
            return ZoneId.of("UTC");
        }

        @Override
        public Clock withZone(ZoneId zone) {
            return this;
        }

        @Override
        public Instant instant() {
            return now;
        }
    }

    @Test
    void fiveFailuresLockForFiveMinutesAndExpireAfterwards() {
        MutableClock clock = new MutableClock();
        LoginRateLimiter limiter = new LoginRateLimiter(clock);

        for (int i = 1; i < LoginRateLimiter.MAX_FAILURES; i++) {
            assertFalse(limiter.recordFailure("admin"), "第 " + i + " 次失败不应锁定");
            assertEquals(0, limiter.lockedSecondsRemaining("admin"));
        }
        assertTrue(limiter.recordFailure("admin"), "第 5 次失败应触发锁定");
        assertEquals(Duration.ofMinutes(5).toSeconds(), limiter.lockedSecondsRemaining("admin"));

        clock.advance(Duration.ofMinutes(5).plusSeconds(1));
        assertEquals(0, limiter.lockedSecondsRemaining("admin"), "锁定期满后应自动解锁");
        assertFalse(limiter.recordFailure("admin"), "解锁后计数应从零开始");
    }

    @Test
    void successfulLoginClearsFailures() {
        MutableClock clock = new MutableClock();
        LoginRateLimiter limiter = new LoginRateLimiter(clock);

        limiter.recordFailure("admin");
        limiter.recordFailure("admin");
        limiter.reset("admin");

        for (int i = 1; i < LoginRateLimiter.MAX_FAILURES; i++) {
            assertFalse(limiter.recordFailure("admin"), "成功登录后此前失败次数应清零");
        }
        assertTrue(limiter.recordFailure("admin"));
    }

    @Test
    void unknownUserIsNeverLocked() {
        LoginRateLimiter limiter = new LoginRateLimiter(new MutableClock());
        assertEquals(0, limiter.lockedSecondsRemaining("nobody"));
    }
}
