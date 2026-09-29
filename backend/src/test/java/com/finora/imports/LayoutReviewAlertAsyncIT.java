package com.finora.imports;

import com.finora.AbstractIntegrationTest;
import com.finora.dto.ImportDto.StagedRow;
import com.finora.entity.User;
import com.finora.repository.UserRepository;
import com.finora.service.EmailProvider;
import com.finora.service.EmailResult;
import com.finora.service.ProviderType;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.timeout;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * The layout review alert is sent off the upload request. With the real alert service and an email
 * provider that takes seconds to answer, flagging a new layout during staging must still return at
 * once -- and the email must still go out afterwards.
 */
class LayoutReviewAlertAsyncIT extends AbstractIntegrationTest {

    @Autowired private LayoutReviewService reviewService;
    @Autowired private UserRepository userRepository;
    @Autowired private com.finora.repository.RoleRepository roleRepository;
    @MockitoBean private EmailProvider emailProvider;

    @Test
    void aSlowEmailProviderDoesNotHoldTheUploadRequest() {
        User admin = new User();
        admin.setEmail("layout-alert-async-" + UUID.randomUUID() + "@example.com");
        admin.setPasswordHash("irrelevant-for-this-test");
        admin.setFullName("Layout Alert Async Admin");
        admin.setRole("ADMIN");
        admin.setAccountScope(User.SCOPE_ADMIN);
        admin.setPhoneVerified(true);
        // The real ADMIN role row, so this also proves V243 granted LAYOUT_REGISTRY_MANAGE to it.
        admin.getRoles().add(roleRepository.findByName("ADMIN").orElseThrow());
        userRepository.save(admin);
        when(emailProvider.send(any())).thenAnswer(invocation -> {
            Thread.sleep(3000);
            return EmailResult.success(ProviderType.RESEND, "slow");
        });
        String fingerprint = "FP-T-" + UUID.randomUUID().toString().substring(0, 8).toUpperCase();
        StagedRow row = new StagedRow(LocalDate.of(2026, 1, 15), "SAMPLE STORE", new BigDecimal("1.00"),
                "EXPENSE", "Other", "default", null, false, null, null);

        long started = System.nanoTime();
        reviewService.onStaged(fingerprint, "PDF", List.of(row), List.of(), "SA-ASYNC-1");
        long elapsedMs = (System.nanoTime() - started) / 1_000_000;

        assertThat(elapsedMs).isLessThan(2000);
        verify(emailProvider, timeout(10000).atLeastOnce()).send(any());
    }
}
