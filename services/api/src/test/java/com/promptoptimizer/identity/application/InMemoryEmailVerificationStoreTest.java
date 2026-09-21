package com.promptoptimizer.identity.application;

import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;

import static org.assertj.core.api.Assertions.assertThat;

class InMemoryEmailVerificationStoreTest {

    private static final EmailVerificationPolicy POLICY = new EmailVerificationPolicy(
            Duration.ofMinutes(5), Duration.ofSeconds(60), 3, 5, 20
    );

    private final MutableClock clock = new MutableClock(Instant.parse("2026-09-21T00:00:00Z"));
    private final InMemoryEmailVerificationStore store = new InMemoryEmailVerificationStore(clock);

    @Test
    void enforcesSixtySecondResendInterval() {
        assertThat(issue("email", "ip", "first", POLICY).result())
                .isEqualTo(EmailVerificationStore.IssueResult.ISSUED);
        assertThat(issue("email", "ip", "second", POLICY))
                .isEqualTo(new EmailVerificationStore.IssueDecision(
                        EmailVerificationStore.IssueResult.RESEND_TOO_SOON,
                        60
                ));

        clock.advance(Duration.ofSeconds(59));
        assertThat(issue("email", "ip", "second", POLICY).retryAfterSeconds()).isEqualTo(1);
        clock.advance(Duration.ofSeconds(1));
        assertThat(issue("email", "ip", "second", POLICY).result())
                .isEqualTo(EmailVerificationStore.IssueResult.ISSUED);
    }

    @Test
    void expiresCodeAfterFiveMinutes() {
        issue("email", "ip", "digest", POLICY);
        clock.advance(Duration.ofMinutes(5));

        assertThat(store.verify("email", "digest"))
                .isEqualTo(EmailVerificationStore.VerificationResult.EXPIRED);
    }

    @Test
    void invalidatesCodeAfterMaximumAttempts() {
        issue("email", "ip", "correct", POLICY);

        assertThat(store.verify("email", "wrong-1"))
                .isEqualTo(EmailVerificationStore.VerificationResult.INVALID);
        assertThat(store.verify("email", "wrong-2"))
                .isEqualTo(EmailVerificationStore.VerificationResult.INVALID);
        assertThat(store.verify("email", "wrong-3"))
                .isEqualTo(EmailVerificationStore.VerificationResult.ATTEMPTS_EXHAUSTED);
        assertThat(store.verify("email", "correct"))
                .isEqualTo(EmailVerificationStore.VerificationResult.EXPIRED);
    }

    @Test
    void rateLimitsByEmailAndIpWithinAnHour() {
        EmailVerificationPolicy emailPolicy = new EmailVerificationPolicy(
                Duration.ofMinutes(5), Duration.ofSeconds(1), 3, 2, 20
        );
        issue("email", "ip", "one", emailPolicy);
        clock.advance(Duration.ofSeconds(1));
        issue("email", "ip", "two", emailPolicy);
        clock.advance(Duration.ofSeconds(1));
        assertThat(issue("email", "ip", "three", emailPolicy).result())
                .isEqualTo(EmailVerificationStore.IssueResult.EMAIL_RATE_LIMITED);

        EmailVerificationPolicy ipPolicy = new EmailVerificationPolicy(
                Duration.ofMinutes(5), Duration.ofSeconds(1), 3, 5, 2
        );
        issue("other-1", "shared-ip", "one", ipPolicy);
        issue("other-2", "shared-ip", "two", ipPolicy);
        assertThat(issue("other-3", "shared-ip", "three", ipPolicy).result())
                .isEqualTo(EmailVerificationStore.IssueResult.IP_RATE_LIMITED);
    }

    @Test
    void cancellingFailedDeliveryRollsBackCooldownAndCounters() {
        issue("email", "ip", "digest", POLICY);
        store.cancelIssue("email", "ip", "digest");

        assertThat(issue("email", "ip", "replacement", POLICY).result())
                .isEqualTo(EmailVerificationStore.IssueResult.ISSUED);
    }

    private EmailVerificationStore.IssueDecision issue(
            String email,
            String ip,
            String digest,
            EmailVerificationPolicy policy
    ) {
        return store.issue(email, ip, digest, policy);
    }

    private static final class MutableClock extends Clock {

        private Instant instant;

        private MutableClock(Instant instant) {
            this.instant = instant;
        }

        private void advance(Duration duration) {
            instant = instant.plus(duration);
        }

        @Override
        public ZoneId getZone() {
            return ZoneOffset.UTC;
        }

        @Override
        public Clock withZone(ZoneId zone) {
            return this;
        }

        @Override
        public Instant instant() {
            return instant;
        }
    }
}
