package com.finora.service;

import com.finora.AbstractIntegrationTest;
import com.finora.entity.User;
import com.finora.repository.UserRepository;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * A new user's referral code is created on first request, and the invite card, the Referrals page
 * or a second device can ask at the same moment. The old read-then-save let both requests find no
 * code and both insert; the slower insert hit the unique key on user_id and the request failed
 * (409 over HTTP), which the web smoke test caught on main (CI run 6471).
 *
 * <p>Called on the service, eight at once per user, over several users: against the old code this
 * failed 34 of 80 calls; through the HTTP endpoint the same load did not reproduce it reliably.
 */
class ReferralCodeConcurrentFirstRequestIT extends AbstractIntegrationTest {

    @Autowired private ReferralService referralService;
    @Autowired private UserRepository userRepository;
    @Autowired private JdbcTemplate jdbc;

    private User newUser() {
        User user = new User();
        user.setEmail("referral-race-" + UUID.randomUUID() + "@example.com");
        user.setPasswordHash("irrelevant-for-this-test");
        user.setFullName("Referral Race IT");
        return userRepository.save(user);
    }

    @Test
    void simultaneousFirstRequestsAllGetTheSameCode_andOneRowIsStored() throws Exception {
        ExecutorService pool = Executors.newFixedThreadPool(8);
        try {
            for (int round = 0; round < 10; round++) {
                UUID userId = newUser().getId();
                CountDownLatch start = new CountDownLatch(1);
                List<Future<String>> calls = new ArrayList<>();
                for (int i = 0; i < 8; i++) {
                    // myReferrals creates the code through myCode too: the Referrals page and the card.
                    boolean viaPage = i % 4 == 3;
                    calls.add(pool.submit(() -> {
                        start.await();
                        return viaPage ? referralService.myReferrals(userId).code() : referralService.myCode(userId);
                    }));
                }
                start.countDown();

                Set<String> codes = new HashSet<>();
                for (Future<String> call : calls) codes.add(call.get(30, TimeUnit.SECONDS));

                assertThat(codes).as("round %d", round).hasSize(1).doesNotContainNull();
                assertThat(jdbc.queryForObject("SELECT count(*) FROM referral_codes WHERE user_id = ?",
                        Integer.class, userId)).as("round %d", round).isEqualTo(1);
            }
        } finally {
            pool.shutdownNow();
        }
    }
}
