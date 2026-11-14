package com.finora.service;

import com.finora.entity.Transaction;
import com.finora.service.UserMerchantCategoryResolutionService.SavedCategory;
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
                .thenReturn(Optional.of(new SavedCategory("Pet Care", false)));

        Optional<SavedCategory> result = service.suggest(userId, "vpa:headsupfortails", Transaction.Type.EXPENSE, "UPI/HUFT/...");

        assertThat(result).contains(new SavedCategory("Pet Care", false));
    }

    @Test
    void suggest_resolutionServiceReturnsEmpty_propagatesEmpty() {
        when(resolutionService.resolve(any(), any(), any(), any())).thenReturn(Optional.empty());

        Optional<SavedCategory> result = service.suggest(userId, "vpa:headsupfortails", Transaction.Type.EXPENSE, "UPI/HUFT/...");

        assertThat(result).isEmpty();
    }

    @Test
    void suggestReadOnly_delegatesToResolveReadOnly_neverToTheWritingResolve() {
        when(resolutionService.resolveReadOnly(userId, "vpa:headsupfortails", Transaction.Type.EXPENSE))
                .thenReturn(Optional.of(new SavedCategory("Pet Care", true)));

        Optional<SavedCategory> result = service.suggestReadOnly(userId, "vpa:headsupfortails", Transaction.Type.EXPENSE);

        // Who chose it is passed through as is: the user's own saved choice stays the user's.
        assertThat(result).contains(new SavedCategory("Pet Care", true));
        verify(resolutionService, never()).resolve(any(), any(), any(), any());
    }
}
