package com.finora.people;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.finora.AbstractIntegrationTest;
import com.finora.entity.Transaction;
import com.finora.entity.User;
import com.finora.repository.RefreshTokenRepository;
import com.finora.repository.TransactionRepository;
import com.finora.repository.UserRepository;
import com.finora.security.JwtService;
import com.finora.testsupport.TestSessions;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.http.*;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Shared payments and recurring payments through the real security chain, controller advice and
 * database, fed what broke them in live testing on a local backend (2026-10-25):
 * <ul>
 *   <li>a cash settle, then the bank repayment marked paid back: the user "owed" the person, and
 *       settling again was refused;</li>
 *   <li>a let-go loan paid back after all: the gift stayed in spending and the repayment left
 *       income, so the money vanished from the figures;</li>
 *   <li>a lent payment corrected or turned into money in: the balance kept the old figure, and a
 *       flipped one counted as income and as owed;</li>
 *   <li>a replaced statement emptied the recurring list; a pattern that stopped years ago led it;</li>
 *   <li>recurring payee and category names had no limit, or were cut without a word.</li>
 * </ul>
 */
class PeopleAndRecurringBreaksIT extends AbstractIntegrationTest {

    @Autowired private TestRestTemplate restTemplate;
    @Autowired private UserRepository userRepository;
    @Autowired private TransactionRepository transactionRepository;
    @Autowired private JwtService jwtService;
    @Autowired private RefreshTokenRepository refreshTokens;
    private final ObjectMapper mapper = new ObjectMapper();

    private User createUser() {
        User user = new User();
        user.setEmail("people-recurring-it-" + UUID.randomUUID() + "@example.com");
        user.setPasswordHash("irrelevant-for-this-test");
        user.setFullName("People Recurring IT User");
        user.setAccountScope(User.SCOPE_USER);
        user.setPhoneVerified(true);
        return userRepository.save(user);
    }

    private ResponseEntity<String> send(HttpMethod method, String path, String body, User user) {
        HttpHeaders headers = new HttpHeaders();
        headers.setBearerAuth(TestSessions.accessTokenFor(jwtService, refreshTokens, user));
        headers.setContentType(MediaType.APPLICATION_JSON);
        return restTemplate.exchange(path, method, new HttpEntity<>(body, headers), String.class);
    }

    private JsonNode ok(ResponseEntity<String> response) throws Exception {
        assertThat(response.getStatusCode()).as(response.getBody()).isEqualTo(HttpStatus.OK);
        return mapper.readTree(response.getBody()).get("data");
    }

    private UUID account(User user) throws Exception {
        return UUID.fromString(ok(send(HttpMethod.POST, "/api/v1/accounts",
                "{\"name\":\"Savings\",\"accountType\":\"SAVINGS\",\"balance\":100000}", user)).get("id").asText());
    }

    /** A payment to or from one person: the UPI id in the narration is their key (vpa:<id>). */
    private UUID txn(User user, UUID accountId, String date, String amount, String type, String description,
                     String category) throws Exception {
        return UUID.fromString(ok(send(HttpMethod.POST, "/api/v1/transactions",
                "{\"accountId\":\"" + accountId + "\",\"categoryName\":\"" + category + "\",\"date\":\"" + date
                        + "\",\"description\":\"" + description + "\",\"amount\":" + amount + ",\"type\":\"" + type + "\"}",
                user)).get("id").asText());
    }

    private UUID lentTo(User user, UUID accountId, String person, String date, String amount) throws Exception {
        UUID id = txn(user, accountId, date, amount, "EXPENSE", "UPI/DR/600000000001/loan/HDFC/" + person + "@okhdfc", "Shopping");
        ok(send(HttpMethod.POST, "/api/v1/people/entries", "{\"txnId\":\"" + id + "\",\"direction\":\"LENT\"}", user));
        return id;
    }

    private UUID backFrom(User user, UUID accountId, String person, String date, String amount) throws Exception {
        return txn(user, accountId, date, amount, "INCOME", "UPI/CR/600000000002/back/HDFC/" + person + "@okhdfc", "Other");
    }

    private ResponseEntity<String> markPaidBack(User user, UUID credit) {
        return send(HttpMethod.POST, "/api/v1/people/entries", "{\"txnId\":\"" + credit + "\",\"direction\":\"PAID_BACK\"}", user);
    }

    private JsonNode people(User user) throws Exception {
        return ok(send(HttpMethod.GET, "/api/v1/people", null, user));
    }

    private JsonNode report(User user, String month) throws Exception {
        return ok(send(HttpMethod.GET, "/api/v1/reports?month=" + month, null, user));
    }

    private List<String> recurringPayees(User user) throws Exception {
        List<String> out = new ArrayList<>();
        ok(send(HttpMethod.GET, "/api/v1/recurring", null, user)).forEach(g -> out.add(g.get("merchant").asText()));
        return out;
    }

    // ------------------------------------------------------------------ people

    @Test
    void aRepaymentMarkedAfterACashSettle_leavesNothingOwed_eitherWay() throws Exception {
        User user = createUser();
        UUID acc = account(user);
        lentTo(user, acc, "asha.k", "2026-08-01", "1000");
        ok(send(HttpMethod.POST, "/api/v1/people/settle", "{\"personKey\":\"vpa:asha.k\",\"how\":\"CASH\"}", user));
        ok(markPaidBack(user, backFrom(user, acc, "asha.k", "2026-08-15", "1000")));

        assertThat(people(user)).isEmpty();
    }

    @Test
    void aLetGoLoanPaidBackAfterAll_isNeitherSpendingNorIncome() throws Exception {
        User user = createUser();
        UUID acc = account(user);
        lentTo(user, acc, "vikram.s", "2026-08-02", "500");
        ok(send(HttpMethod.POST, "/api/v1/people/settle", "{\"personKey\":\"vpa:vikram.s\",\"how\":\"LET_GO\"}", user));
        assertThat(report(user, "2026-08").get("expense").decimalValue()).isEqualByComparingTo("500");

        ok(markPaidBack(user, backFrom(user, acc, "vikram.s", "2026-10-01", "500")));

        assertThat(report(user, "2026-08").get("expense").decimalValue()).isEqualByComparingTo("0");
        assertThat(report(user, "2026-10").get("income").decimalValue()).isEqualByComparingTo("0");
        assertThat(people(user)).isEmpty();
    }

    @Test
    void aCorrectedLentAmount_isWhatIsOwed() throws Exception {
        User user = createUser();
        UUID acc = account(user);
        UUID lent = lentTo(user, acc, "asha.k", "2026-10-01", "1000");
        ok(send(HttpMethod.PUT, "/api/v1/transactions/" + lent, "{\"date\":\"2026-10-01\",\"description\":"
                + "\"UPI/DR/600000000001/loan/HDFC/asha.k@okhdfc\",\"amount\":100,\"type\":\"EXPENSE\",\"categoryName\":\"Shopping\"}", user));

        JsonNode people = people(user);
        assertThat(people).hasSize(1);
        assertThat(people.get(0).get("owedToUser").decimalValue()).isEqualByComparingTo("100");
        JsonNode timeline = ok(send(HttpMethod.GET, "/api/v1/people/person?key=vpa:asha.k", null, user)).get("timeline");
        assertThat(timeline.get(0).get("amount").decimalValue()).isEqualByComparingTo("100");
    }

    @Test
    void aLentPaymentTurnedIntoMoneyIn_isIncomeOnly_notAlsoOwed() throws Exception {
        User user = createUser();
        UUID acc = account(user);
        UUID lent = lentTo(user, acc, "neha.r", "2026-10-03", "700");
        ok(send(HttpMethod.PUT, "/api/v1/transactions/" + lent, "{\"date\":\"2026-10-03\",\"description\":"
                + "\"UPI/DR/600000000001/loan/HDFC/neha.r@okhdfc\",\"amount\":700,\"type\":\"INCOME\",\"categoryName\":\"Other\"}", user));

        assertThat(people(user)).isEmpty();
        assertThat(report(user, "2026-10").get("income").decimalValue()).isEqualByComparingTo("700");
    }

    @Test
    void anAnswerAboveTheLeftover_saysSo() throws Exception {
        User user = createUser();
        UUID acc = account(user);
        UUID bill = txn(user, acc, "2026-10-05", "400", "EXPENSE", "UPI/DR/600000000003/Cab/HDFC/cab.y@okhdfc", "Transportation");
        UUID credit = txn(user, acc, "2026-10-06", "1000", "INCOME", "UPI/CR/600000000004/stuff/AXIS/kiran.p@okaxis", "Other");
        ok(send(HttpMethod.POST, "/api/v1/people/shares", "{\"creditId\":\"" + credit + "\",\"billId\":\"" + bill + "\"}", user));

        ResponseEntity<String> response = send(HttpMethod.POST, "/api/v1/people/entries",
                "{\"txnId\":\"" + credit + "\",\"direction\":\"PAID_BACK\",\"amount\":700}", user);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.UNPROCESSABLE_ENTITY);
        assertThat(mapper.readTree(response.getBody()).get("errorCode").asText()).isEqualTo("TXN_012");
    }

    // ------------------------------------------------------------------ recurring

    @Test
    void aReplacedStatementsOldCopies_leaveTheRecurringPayeeListed() throws Exception {
        User user = createUser();
        UUID acc = account(user);
        List<UUID> old = new ArrayList<>();
        for (String d : List.of("2026-07-05", "2026-08-05", "2026-09-05")) {
            txn(user, acc, d, "799", "EXPENSE", "JIOFIBER BROADBAND", "Bills & Utilities");
            old.add(txn(user, acc, d, "799", "EXPENSE", "JIOFIBER BROADBAND", "Bills & Utilities"));
        }
        // What StatementImportService.supersede leaves: the replaced statement's rows kept, SUPERSEDED,
        // no longer marked duplicates of the replacement's.
        for (UUID id : old) {
            Transaction t = transactionRepository.findById(id).orElseThrow();
            t.setIsDuplicateOf(null);
            t.setReconciliationStatus(Transaction.ReconciliationStatus.SUPERSEDED);
            transactionRepository.save(t);
        }

        assertThat(recurringPayees(user)).containsExactly("jiofiber broadband");
    }

    @Test
    void aPatternThatStoppedYearsAgo_isNotListedAboveALiveOne() throws Exception {
        User user = createUser();
        UUID acc = account(user);
        for (String d : List.of("2023-12-31", "2024-01-31", "2024-02-29", "2024-03-31")) {
            txn(user, acc, d, "999", "EXPENSE", "GYMPASS MEMBERSHIP", "Health");
        }
        for (String d : List.of("2026-08-31", "2026-09-30", "2026-10-31")) {
            txn(user, acc, d, "199", "EXPENSE", "NETFLIX SUBSCRIPTION", "Entertainment");
        }

        assertThat(recurringPayees(user)).containsExactly("netflix subscription");
    }

    @Test
    void recurringNamesTheColumnsCannotHold_areRefusedWithAMessage() throws Exception {
        User user = createUser();
        String longName = "m".repeat(256);
        for (String path : List.of("/api/v1/recurring/dismiss", "/api/v1/recurring/confirm")) {
            ResponseEntity<String> response = send(HttpMethod.POST, path, "{\"merchant\":\"" + longName + "\"}", user);
            assertThat(response.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
            assertThat(mapper.readTree(response.getBody()).get("message").asText()).contains("255 characters");
        }
        ResponseEntity<String> category = send(HttpMethod.POST, "/api/v1/recurring/categorize",
                "{\"merchant\":\"netflix\",\"category\":\"" + "c".repeat(81) + "\"}", user);
        assertThat(category.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(mapper.readTree(category.getBody()).get("message").asText()).contains("80 characters");
    }
}
