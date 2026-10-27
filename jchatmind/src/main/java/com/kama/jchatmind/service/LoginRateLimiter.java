package com.kama.jchatmind.service;

import org.springframework.stereotype.Component;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 登录失败限流：同一用户名连续失败 5 次锁定 5 分钟，成功登录清零（内存计数，单实例）
 */
@Component
public class LoginRateLimiter {

    static final int MAX_FAILURES = 5;
    static final Duration LOCK_DURATION = Duration.ofMinutes(5);

    private final Clock clock;
    private final Map<String, Attempt> attempts = new ConcurrentHashMap<>();

    private static final class Attempt {
        int failures;
        Instant lockedUntil;
    }

    public LoginRateLimiter() {
        this(Clock.systemDefaultZone());
    }

    LoginRateLimiter(Clock clock) {
        this.clock = clock;
    }

    /** @return 剩余锁定秒数；0 表示未锁定（过期锁自动清除） */
    public long lockedSecondsRemaining(String username) {
        Attempt attempt = attempts.get(username);
        if (attempt == null || attempt.lockedUntil == null) return 0;
        long remaining = Duration.between(clock.instant(), attempt.lockedUntil).toSeconds();
        if (remaining <= 0) {
            attempts.remove(username, attempt);
            return 0;
        }
        return remaining;
    }

    /** 记录一次失败；达到阈值时触发锁定并返回 true */
    public boolean recordFailure(String username) {
        Attempt attempt = attempts.compute(username, (key, existing) -> {
            if (existing == null) existing = new Attempt();
            existing.failures++;
            if (existing.failures >= MAX_FAILURES) {
                existing.lockedUntil = clock.instant().plus(LOCK_DURATION);
            }
            return existing;
        });
        return attempt.lockedUntil != null;
    }

    /** 登录成功后清除计数 */
    public void reset(String username) {
        attempts.remove(username);
    }
}
