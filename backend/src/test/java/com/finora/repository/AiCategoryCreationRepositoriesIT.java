package com.finora.repository;

import com.finora.AbstractIntegrationTest;
import com.finora.entity.*;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import java.time.Instant;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

class AiCategoryCreationRepositoriesIT extends AbstractIntegrationTest {

    @Autowired private MerchantUnderstandingRepository understandingRepo;
    @Autowired private UserMerchantCategoryResolutionRepository resolutionRepo;
    @Autowired private CategoryRepository categoryRepository;
    @Autowired private UserRepository userRepository;

    // categories.user_id has a FK to users -- a bare UUID.randomUUID() fails the insert with a
    // DataIntegrityViolationException, same pattern NetWorthSnapshotRepositoryIT's own newUser()
    // already works around.
    private UUID newUser() {
        User user = new User();
        user.setEmail("ai-category-creation-it-" + UUID.randomUUID() + "@example.com");
        user.setPasswordHash("irrelevant-for-this-test");
        user.setFullName("AI Category Creation IT User");
        user.setPhoneVerified(true);
        return userRepository.save(user).getId();
    }

    @Test
    void understanding_upsertTwice_replacesRatherThanDuplicates() {
        understandingRepo.upsert("vpa:headsupfortails", "EXPENSE", "A pet store", "claude-haiku-4-5-20251001", Instant.now());
        understandingRepo.upsert("vpa:headsupfortails", "EXPENSE", "A pet supplies retailer", "claude-haiku-4-5-20251001", Instant.now());

        var found = understandingRepo.findByCounterpartyKeyAndDirection("vpa:headsupfortails", Transaction.Type.EXPENSE);
        assertThat(found).isPresent();
        assertThat(found.get().getUnderstanding()).isEqualTo("A pet supplies retailer");
    }

    @Test
    void resolution_insertIfAbsent_secondCallForSameKeyNoOps() {
        UUID userId = newUser();
        Category category = new Category();
        category.setUserId(userId);
        category.setName("Pet Care");
        category.setSystem(false);
        category = categoryRepository.save(category);

        int first = resolutionRepo.insertIfAbsent(userId, "vpa:headsupfortails", "EXPENSE", category.getId(), Instant.now());
        int second = resolutionRepo.insertIfAbsent(userId, "vpa:headsupfortails", "EXPENSE", UUID.randomUUID(), Instant.now());

        assertThat(first).isEqualTo(1);
        assertThat(second).isEqualTo(0);
        assertThat(resolutionRepo.findByUserIdAndCounterpartyKeyAndDirection(userId, "vpa:headsupfortails", Transaction.Type.EXPENSE)
                .orElseThrow().getCategoryId()).isEqualTo(category.getId());
    }

    @Test
    void resolution_upsertPinned_overwritesExisting() {
        UUID userId = newUser();
        Category first = categoryRepository.save(newCategory(userId, "Pet Care"));
        Category second = categoryRepository.save(newCategory(userId, "Pets"));
        resolutionRepo.insertIfAbsent(userId, "vpa:headsupfortails", "EXPENSE", first.getId(), Instant.now());

        resolutionRepo.upsertPinned(userId, "vpa:headsupfortails", "EXPENSE", second.getId(), Instant.now());

        assertThat(resolutionRepo.findByUserIdAndCounterpartyKeyAndDirection(userId, "vpa:headsupfortails", Transaction.Type.EXPENSE)
                .orElseThrow().getCategoryId()).isEqualTo(second.getId());
    }

    private static Category newCategory(UUID userId, String name) {
        Category c = new Category();
        c.setUserId(userId);
        c.setName(name);
        c.setSystem(false);
        return c;
    }
}
