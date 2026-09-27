package com.finora.transactions;

import com.finora.AbstractIntegrationTest;
import com.finora.entity.Account;
import com.finora.entity.User;
import com.finora.integrations.anthropic.LlmClient;
import com.finora.integrations.anthropic.LlmClient.LlmCompletion;
import com.finora.integrations.anthropic.LlmClient.ToolUse;
import com.finora.repository.AccountRepository;
import com.finora.repository.RefreshTokenRepository;
import com.finora.repository.UserRepository;
import com.finora.security.JwtService;
import com.finora.service.FynAvailabilityGuard;
import com.finora.testsupport.TestSessions;
import com.finora.util.CounterpartyTyping;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.atLeastOnce;
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
    @Autowired private TestRestTemplate restTemplate;
    @Autowired private JwtService jwtService;
    @Autowired private RefreshTokenRepository refreshTokens;

    @MockitoBean private LlmClient llmClient;
    @MockitoBean private FynAvailabilityGuard availabilityGuard;

    private record Fixture(User user, Account account) {}

    private Fixture fixture() {
        when(availabilityGuard.categorizationAvailableFor(any())).thenReturn(true);
        when(llmClient.complete(any())).thenReturn(new LlmCompletion(null,
                List.of(new ToolUse("t1", "UNDERSTAND_MERCHANT", Map.of("understanding", "A payment"))),
                "claude-haiku-4-5-20251001", 40, 10, "tool_use"));

        User user = new User();
        user.setEmail("keyless-ai-fallback-it-" + UUID.randomUUID() + "@example.com");
        user.setPasswordHash("irrelevant-for-this-test");
        user.setFullName("Keyless AI Fallback IT User");
        user.setAccountScope(User.SCOPE_USER);
        user.setPhoneVerified(true);
        user = userRepository.save(user);

        Account account = new Account();
        account.setUserId(user.getId());
        account.setName("Keyless AI Fallback IT Account");
        account.setAccountType(Account.Type.SAVINGS);
        account.setBalance(BigDecimal.valueOf(1000));
        return new Fixture(user, accountRepository.save(account));
    }

    private int transactionCount(UUID userId) {
        Integer rows = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM transactions WHERE user_id = ?", Integer.class, userId);
        return rows == null ? 0 : rows;
    }

    @Test
    void manualCreate_withKeylessNarration_savesTheTransaction_andNeverCallsTheLlm() {
        assertThat(CounterpartyTyping.of(KEYLESS_NARRATION).key()).isNull();
        Fixture f = fixture();

        TransactionDto created = transactionService.create(f.user().getId(), new TransactionDto.CreateRequest(
                f.account().getId(), null, LocalDate.of(2026, 9, 1), KEYLESS_NARRATION,
                BigDecimal.valueOf(250), "EXPENSE", List.of(), null));

        assertThat(created.id()).isNotNull();
        assertThat(transactionCount(f.user().getId())).isEqualTo(1);
        verify(llmClient, never()).complete(any());
    }

    /** The same case through the real endpoint a user hits: the response status, and the
     *  account balance create() moves in the same transaction as the insert. */
    @Test
    void postTransaction_withKeylessNarration_returns200_andMovesTheBalance() {
        Fixture f = fixture();
        HttpHeaders headers = new HttpHeaders();
        headers.setBearerAuth(TestSessions.accessTokenFor(jwtService, refreshTokens, f.user()));
        headers.setContentType(MediaType.APPLICATION_JSON);
        String body = """
                {"accountId":"%s","date":"2026-09-01","description":"%s","amount":250,"type":"EXPENSE","tags":[]}
                """.formatted(f.account().getId(), KEYLESS_NARRATION);

        ResponseEntity<String> response = restTemplate.exchange(
                "/api/v1/transactions", HttpMethod.POST, new HttpEntity<>(body, headers), String.class);

        assertThat(response.getStatusCode()).as(response.getBody()).isEqualTo(HttpStatus.OK);
        assertThat(transactionCount(f.user().getId())).isEqualTo(1);
        assertThat(accountRepository.findById(f.account().getId()).orElseThrow().getBalance())
                .isEqualByComparingTo(BigDecimal.valueOf(750));
        verify(llmClient, never()).complete(any());
    }

    /** Control: with the same harness, a narration that DOES carry a key still reaches the LLM.
     *  Without this, the never()-called assertions above could pass because the fallback was
     *  unreachable in this context for some unrelated reason. The VPA is unique per run because
     *  merchant_understanding is a global table shared by every IT. */
    @Test
    void manualCreate_withKeyedNarration_stillReachesTheLlm() {
        String vpa = "qzxvendor" + UUID.randomUUID().toString().replace("-", "").substring(0, 12);
        String narration = "UPI-QZX VENDOR-" + vpa + "@ybl-REF881234";
        assertThat(CounterpartyTyping.of(narration).key()).isEqualTo("vpa:" + vpa);
        Fixture f = fixture();

        TransactionDto created = transactionService.create(f.user().getId(), new TransactionDto.CreateRequest(
                f.account().getId(), null, LocalDate.of(2026, 9, 1), narration,
                BigDecimal.valueOf(250), "EXPENSE", List.of(), null));

        assertThat(created.id()).isNotNull();
        verify(llmClient, atLeastOnce()).complete(any());
    }
}
