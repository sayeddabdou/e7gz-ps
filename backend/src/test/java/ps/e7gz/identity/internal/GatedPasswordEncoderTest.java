package ps.e7gz.identity.internal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;
import org.springframework.security.crypto.password.PasswordEncoder;
import ps.e7gz.common.error.ApiErrorCode;
import ps.e7gz.common.error.ApiException;

/** Sprint 0 review S-2 (E7-24): one bounded gate in front of every Argon2id hash and check. */
class GatedPasswordEncoderTest {

    private static final int PERMITS = 4;

    @Test
    void permitsPlusOneConcurrentHashesRefuseExactlyOneWithRateLimited() throws Exception {
        CountDownLatch allInside = new CountDownLatch(PERMITS);
        CountDownLatch release = new CountDownLatch(1);
        PasswordEncoder slow = new BlockingEncoder(allInside, release);
        GatedPasswordEncoder gate = new GatedPasswordEncoder(slow, PERMITS, Duration.ofMillis(300));
        ExecutorService pool = Executors.newFixedThreadPool(PERMITS + 1);
        try {
            List<Future<String>> holders = new ArrayList<>();
            for (int i = 0; i < PERMITS; i++) {
                holders.add(pool.submit(() -> gate.encode("password1")));
            }
            assertThat(allInside.await(5, TimeUnit.SECONDS)).as("all permits taken").isTrue();

            Future<String> extra = pool.submit(() -> gate.encode("password1"));
            assertThatThrownBy(extra::get).hasCauseInstanceOf(ApiException.class)
                    .cause().extracting(e -> ((ApiException) e).code()).isEqualTo(ApiErrorCode.RATE_LIMITED);

            release.countDown();
            for (Future<String> holder : holders) {
                assertThat(holder.get(5, TimeUnit.SECONDS)).isEqualTo("hash");
            }
        } finally {
            release.countDown();
            pool.shutdownNow();
        }
    }

    @Test
    void aTurnIsGivenBackSoLaterCallsSucceed() {
        GatedPasswordEncoder gate = new GatedPasswordEncoder(new BlockingEncoder(null, null), 1, Duration.ZERO);
        for (int i = 0; i < 3; i++) {
            assertThat(gate.encode("x")).isEqualTo("hash");
            assertThat(gate.matches("x", "hash")).isTrue();
        }
    }

    @Test
    void aFailingHashGivesItsTurnBack() {
        PasswordEncoder failing = new PasswordEncoder() {
            @Override
            public String encode(CharSequence raw) {
                throw new IllegalStateException("boom");
            }

            @Override
            public boolean matches(CharSequence raw, String encoded) {
                return true;
            }
        };
        GatedPasswordEncoder gate = new GatedPasswordEncoder(failing, 1, Duration.ZERO);
        assertThatThrownBy(() -> gate.encode("x")).isInstanceOf(IllegalStateException.class);
        assertThat(gate.matches("x", "y")).isTrue();
    }

    /** Holds every call until released, after signalling that it is inside. */
    private record BlockingEncoder(CountDownLatch inside, CountDownLatch release) implements PasswordEncoder {

        @Override
        public String encode(CharSequence raw) {
            hold();
            return "hash";
        }

        @Override
        public boolean matches(CharSequence raw, String encoded) {
            hold();
            return "hash".equals(encoded);
        }

        private void hold() {
            if (inside == null) {
                return;
            }
            inside.countDown();
            try {
                release.await(10, TimeUnit.SECONDS);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }
    }
}
