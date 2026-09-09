package com.finora.service;

import com.finora.entity.FeatureViewCount;
import com.finora.exception.ApiException;
import com.finora.repository.FeatureViewCountRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.*;

/** Smart Insights usage tile: real per-user view counts, not the hardcoded "142" Billing.tsx used
 *  to show (see UsageTile's former isStatic gap-list comment). */
class FeatureUsageServiceTest {

    private FeatureViewCountRepository repository;
    private FeatureUsageService service;
    private final UUID userId = UUID.randomUUID();

    @BeforeEach
    void setUp() {
        repository = mock(FeatureViewCountRepository.class);
        service = new FeatureUsageService(repository);
    }

    @Test
    void recordView_incrementsTheCounter_forARecognizedFeature() {
        service.recordView(userId, "insights");

        verify(repository).recordView(userId, "INSIGHTS");
    }

    @Test
    void recordView_rejectsAnUnrecognizedFeature() {
        assertThatThrownBy(() -> service.recordView(userId, "not-a-real-feature"))
                .isInstanceOf(ApiException.class);

        verifyNoInteractions(repository);
    }

    @Test
    void viewCount_rejectsAnUnrecognizedFeature() {
        assertThatThrownBy(() -> service.viewCount(userId, "not-a-real-feature"))
                .isInstanceOf(ApiException.class);

        verifyNoInteractions(repository);
    }

    @Test
    void viewCount_returnsZero_whenTheUserHasNeverViewedTheFeature() {
        when(repository.findByUserIdAndFeature(userId, "INSIGHTS")).thenReturn(Optional.empty());

        int count = service.viewCount(userId, "insights");

        assertThat(count).isZero();
    }

    @Test
    void viewCount_returnsTheRealCount_forAUserWhoHasViewedItBefore() {
        FeatureViewCount row = new FeatureViewCount();
        ReflectionTestUtils.setField(row, "viewCount", 7);
        ReflectionTestUtils.setField(row, "lastViewedAt", Instant.now());
        when(repository.findByUserIdAndFeature(userId, "INSIGHTS")).thenReturn(Optional.of(row));

        int count = service.viewCount(userId, "insights");

        assertThat(count).isEqualTo(7);
    }
}
