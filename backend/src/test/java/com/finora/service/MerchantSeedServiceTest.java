package com.finora.service;

import com.finora.entity.Merchant;
import com.finora.repository.MerchantRepository;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;

/**
 * Phase 1, docs/proposals/reconciliation-evolution-roadmap-proposal.md. Called from AuthService
 * alongside seedDefaultCategories -- Merchant.userId is NOT NULL, so this can only happen per
 * user, at registration, the same way default categories already do.
 */
class MerchantSeedServiceTest {

    @Test
    @SuppressWarnings("unchecked")
    void seedCuratedMerchants_writesOneBatch_allApproved_allOwnedByTheNewUser() {
        MerchantRepository merchantRepository = mock(MerchantRepository.class);
        MerchantSeedService service = new MerchantSeedService(merchantRepository);
        UUID userId = UUID.randomUUID();

        service.seedCuratedMerchants(userId);

        var captor = org.mockito.ArgumentCaptor.forClass(List.class);
        verify(merchantRepository).saveAll(captor.capture());
        List<Merchant> saved = captor.getValue();

        assertThat(saved).isNotEmpty();
        assertThat(saved).allSatisfy(m -> {
            assertThat(m.getUserId()).isEqualTo(userId);
            assertThat(m.getLifecycleStatus()).isEqualTo(Merchant.Lifecycle.APPROVED);
            assertThat(m.getCanonicalName()).isNotBlank();
        });
        // One word each, longer than two characters: MerchantNormalizationEngine matches a
        // narration to an APPROVED merchant by its first word alone only when the merchant's name
        // is one such word. A two-word seed would catch only narrations sharing both words.
        assertThat(saved).allSatisfy(m -> assertThat(m.getCanonicalName()).matches("[A-Za-z]{3,}"));
        // No duplicate names -- each seeded merchant should be its own row, not two aliases of
        // the same brand fighting over the same match.
        assertThat(saved.stream().map(Merchant::getCanonicalName).distinct().count())
                .isEqualTo(saved.size());
    }

    @Test
    @SuppressWarnings("unchecked")
    void seedCuratedMerchants_includesTheMerchantsNamedInTheRoadmapProposal() {
        MerchantRepository merchantRepository = mock(MerchantRepository.class);
        MerchantSeedService service = new MerchantSeedService(merchantRepository);
        UUID userId = UUID.randomUUID();

        service.seedCuratedMerchants(userId);

        var captor = org.mockito.ArgumentCaptor.forClass(List.class);
        verify(merchantRepository).saveAll(captor.capture());
        List<String> names = ((List<Merchant>) captor.getValue()).stream().map(Merchant::getCanonicalName).toList();

        assertThat(names).contains("Swiggy", "Blinkit", "Zepto", "Netflix", "Amazon", "Google", "Apple", "Uber");
    }
}
