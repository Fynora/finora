package com.finora.imports.migration;

import com.finora.AbstractIntegrationTest;
import com.finora.entity.ImportJob;
import com.finora.entity.User;
import com.finora.repository.ImportJobRepository;
import com.finora.repository.UserRepository;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import java.time.Instant;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

class V194ImportJobStatementHeldEmailSentMigrationIT extends AbstractIntegrationTest {

    @Autowired
    private ImportJobRepository repository;

    @Autowired
    private UserRepository userRepository;

    private User user() {
        User user = new User();
        user.setEmail("v194-migration-it-" + UUID.randomUUID() + "@example.test");
        user.setPasswordHash("irrelevant-for-this-test");
        user.setFullName("V194 Migration IT User");
        user.setPhoneVerified(true);
        return userRepository.save(user);
    }

    @Test
    void newColumnDefaultsToNullAndCanBeSetOnce() {
        User owner = user();
        ImportJob job = new ImportJob(owner.getId(), "statement.csv", "hash", "objects/key", "CSV");
        job = repository.save(job);

        ImportJob fetched = repository.findById(job.getId()).orElseThrow();
        assertThat(fetched.markStatementHeldEmailSent(Instant.now()))
                .as("first call marks it sent and returns true")
                .isTrue();
        repository.save(fetched);

        ImportJob reloaded = repository.findById(job.getId()).orElseThrow();
        assertThat(reloaded.markStatementHeldEmailSent(Instant.now()))
                .as("a second call must not report sent again")
                .isFalse();
    }
}
