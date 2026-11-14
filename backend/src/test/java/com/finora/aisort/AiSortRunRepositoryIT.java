package com.finora.aisort;

import com.finora.AbstractIntegrationTest;
import com.finora.entity.User;
import com.finora.repository.UserRepository;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.DataIntegrityViolationException;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** The constraints AI sorting relies on, against real Postgres (spec 2026-11-14-ai-sort-after-import). */
class AiSortRunRepositoryIT extends AbstractIntegrationTest {

    @Autowired private AiSortRunRepository runs;
    @Autowired private UserRepository users;

    private UUID newUser() {
        User u = new User();
        u.setEmail("ai-sort-run-it-" + UUID.randomUUID() + "@example.com");
        u.setPasswordHash("irrelevant");
        u.setFullName("Ai Sort Run IT");
        u.setAccountScope(User.SCOPE_USER);
        return users.save(u).getId();
    }

    @Test
    void oneRunPerCustomerAndImportSet() {
        UUID userId = newUser();
        String key = UUID.randomUUID() + "," + UUID.randomUUID();
        runs.saveAndFlush(AiSortRun.start(userId, key, 3));

        assertThatThrownBy(() -> runs.saveAndFlush(AiSortRun.start(userId, key, 3)))
                .isInstanceOf(DataIntegrityViolationException.class);

        UUID other = newUser();
        AiSortRun theirs = runs.saveAndFlush(AiSortRun.start(other, key, 3));
        assertThat(runs.findByUserIdAndImportsKey(other, key)).map(AiSortRun::getId).contains(theirs.getId());
        assertThat(runs.findByIdAndUserId(theirs.getId(), userId)).isEmpty();
    }

    @Test
    void aStartedRunIsRunningWithNothingDone() {
        AiSortRun run = runs.saveAndFlush(AiSortRun.start(newUser(), UUID.randomUUID().toString(), 12));

        AiSortRun read = runs.findById(run.getId()).orElseThrow();
        assertThat(read.getStatus()).isEqualTo(AiSortRun.RunStatus.RUNNING);
        assertThat(read.getPaymentsTotal()).isEqualTo(12);
        assertThat(read.getPaymentsDone()).isZero();
        assertThat(read.getPaymentsSorted()).isZero();
        assertThat(read.getFinishedAt()).isNull();
    }

    @Test
    void theOneMoreQuestionStampIsKept() {
        UUID userId = newUser();
        Instant at = Instant.now().truncatedTo(ChronoUnit.MICROS);
        User u = users.findById(userId).orElseThrow();
        u.recordAiImportQuestion(at);
        users.saveAndFlush(u);

        assertThat(users.findById(userId).orElseThrow().getAiImportQuestionAt()).isEqualTo(at);
    }
}
