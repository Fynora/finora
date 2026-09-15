package com.finora.service;

import com.finora.entity.Transaction;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.*;

class FynCategorizationFallbackServiceTest {

    private UserMerchantCategoryResolutionService resolutionService;
    private FynCategorizationFallbackService service;
    private final UUID userId = UUID.randomUUID();

    @BeforeEach
    void setUp() {
        resolutionService = mock(UserMerchantCategoryResolutionService.class);
        service = new FynCategorizationFallbackService(resolutionService);
    }

    @Test
    void suggest_delegatesToResolutionServiceWithSameArguments() {
        when(resolutionService.resolve(userId, "vpa:headsupfortails", Transaction.Type.EXPENSE, "UPI/HUFT/..."))
                .thenReturn(Optional.of("Pet Care"));

        Optional<String> result = service.suggest(userId, "vpa:headsupfortails", Transaction.Type.EXPENSE, "UPI/HUFT/...");

        assertThat(result).contains("Pet Care");
    }

    @Test
    void suggest_resolutionServiceReturnsEmpty_propagatesEmpty() {
        when(resolutionService.resolve(any(), any(), any(), any())).thenReturn(Optional.empty());

        Optional<String> result = service.suggest(userId, "vpa:headsupfortails", Transaction.Type.EXPENSE, "UPI/HUFT/...");

        assertThat(result).isEmpty();
    }
}
