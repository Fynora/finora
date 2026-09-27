package com.finora.transactions;

import com.finora.AbstractIntegrationTest;
import com.finora.entity.Account;
import com.finora.entity.User;
import com.finora.integrations.anthropic.LlmClient;
import com.finora.integrations.anthropic.LlmClient.LlmCompletion;
import com.finora.integrations.anthropic.LlmClient.ToolUse;
import com.finora.repository.AccountRepository;
import com.finora.repository.UserRepository;
import com.finora.service.FynAvailabilityGuard;
import com.finora.util.CounterpartyTyping;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * A narration with no identifiable counterparty -- only a reference fragment -- gets a null
 * {@code CounterpartyTyping.key()}. The AI fallback is keyed on that value: its understanding
 * cache ({@code merchant_understanding.counterparty_key}) and its per-user resolution cache
 * ({@code user_merchant_category_resolution.counterparty_key}) are both NOT NULL. This runs a
 * manual create against real Postgres with the LLM available, so that the fallback is actually
 * reached rather than short-circuited by an unavailable Fyn.
 */
class KeylessNarrationAiFallbackIT extends AbstractIntegrationTest {

    private static final String KEYLESS_NARRATION = "UPI/REF37/UPI";

    @Autowired private TransactionService transactionService;
    @Autowired private AccountRepository accountRepository;
    @Autowired private UserRepository userRepository;
    @Autowired private JdbcTemplate jdbcTemplate;

    @MockitoBean private LlmClient llmClient;
    @MockitoBean private FynAvailabilityGuard availabilityGuard;

    @Test
    void manualCreate_withKeylessNarration_savesTheTransaction_andNeverCallsTheLlm() {
        assertThat(CounterpartyTyping.of(KEYLESS_NARRATION).key()).isNull();

        when(availabilityGuard.categorizationAvailableFor(any())).thenReturn(true);
        when(llmClient.complete(any())).thenReturn(new LlmCompletion(null,
                List.of(new ToolUse("t1", "UNDERSTAND_MERCHANT", Map.of("understanding", "A payment"))),
                "claude-haiku-4-5-20251001", 40, 10, "tool_use"));

        User user = new User();
        user.setEmail("keyless-ai-fallback-it-" + UUID.randomUUID() + "@example.com");
        user.setPasswordHash("irrelevant-for-this-test");
        user.setFullName("Keyless AI Fallback IT User");
        user.setPhoneVerified(false);
        user = userRepository.save(user);

        Account account = new Account();
        account.setUserId(user.getId());
        account.setName("Keyless AI Fallback IT Account");
        account.setAccountType(Account.Type.SAVINGS);
        account.setBalance(BigDecimal.valueOf(1000));
        account = accountRepository.save(account);

        TransactionDto created = transactionService.create(user.getId(), new TransactionDto.CreateRequest(
                account.getId(), null, LocalDate.of(2026, 9, 1), KEYLESS_NARRATION,
                BigDecimal.valueOf(250), "EXPENSE", List.of(), null));

        assertThat(created.id()).isNotNull();
        Integer rows = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM transactions WHERE user_id = ?", Integer.class, user.getId());
        assertThat(rows).isEqualTo(1);
        verify(llmClient, never()).complete(any());
    }
}
