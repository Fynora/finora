package com.finora.imports;

import com.finora.AbstractIntegrationTest;
import com.finora.dto.ImportDto.ConfirmRequest;
import com.finora.dto.ImportDto.ConfirmedRow;
import com.finora.dto.ImportDto.NewAccountRequest;
import com.finora.entity.User;
import com.finora.repository.AccountRepository;
import com.finora.repository.UserRepository;
import com.finora.service.SubscriptionService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.timeout;
import static org.mockito.Mockito.verify;

/**
 * The bank-choice count is bookkeeping. If writing it fails, the user's import must still land:
 * a broken counter must never cost someone their statement. Every value is synthetic.
 */
class ImportBankChoiceIsolationIT extends AbstractIntegrationTest {

    @Autowired private ImportService importService;
    @Autowired private AccountRepository accountRepository;
    @Autowired private UserRepository userRepository;
    @Autowired private SubscriptionService subscriptionService;
    @MockitoBean private ImportBankChoiceRecorder recorder;
    @MockitoBean private LayoutReviewAlertService alertService;

    @Test
    void aFailingCountNeverFailsTheImport() throws Exception {
        doThrow(new IllegalStateException("simulated bookkeeping failure")).when(recorder).record(any(), any(), any());
        User user = new User();
        user.setEmail("bank-choice-isolation-it-" + UUID.randomUUID() + "@example.com");
        user.setPasswordHash("irrelevant-for-this-test");
        user.setFullName("Bank Choice Isolation IT");
        user.setPhoneVerified(true);
        user = userRepository.save(user);
        subscriptionService.provisionFreeSubscription(user.getId());
        byte[] csv = ("Date,Narration,Chq./Ref.No.,Value Dt,Withdrawal Amt.,Deposit Amt.,Closing Balance\n"
                + "01/04/25,SYNTHETIC SHOP,0000100000000001,01/04/25,100.00,,1900.00\n").getBytes(StandardCharsets.UTF_8);
        var staged = importService.parseAndStageWithSession(user.getId(), "statement.csv", csv);
        var rows = staged.staging().rows().stream().map(r -> new ConfirmedRow(r.date(), r.description(), r.amount(),
                r.type(), r.suggestedCategory(), true, r.categorySource(), r.ruleId(), r.likelyDuplicate(),
                r.referenceNumber(), r.balanceAfter())).toList();
        NewAccountRequest account = new NewAccountRequest("Synthetic Account", "SAVINGS", BigDecimal.ZERO, null, null,
                null, null, "HDFC", null, null, null, null, null, null, null, null, null, null, null);

        var response = importService.confirmSession(user.getId(), new ConfirmRequest(staged.sessionId(), rows, null,
                account, null, null, null, null, null, null, null, true, null, null));

        assertThat(response.imported()).isEqualTo(1);
        assertThat(accountRepository.findByUserId(user.getId())).hasSize(1);
        verify(recorder, timeout(5000)).record(any(), any(), any());
    }
}
