package com.finora.transactions;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.finora.AbstractIntegrationTest;
import com.finora.entity.Account;
import com.finora.entity.Transaction;
import com.finora.entity.User;
import com.finora.repository.AccountRepository;
import com.finora.repository.RefreshTokenRepository;
import com.finora.repository.TransactionRepository;
import com.finora.repository.UserRepository;
import com.finora.security.JwtService;
import com.finora.testsupport.TestSessions;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.JdbcTemplate;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;
import java.util.Random;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Paging through {@code GET /api/v1/transactions} must hand back every row exactly once.
 *
 * <p>The search sorted on one field only (the date, or the amount). Rows that share that field had
 * no defined order, so Postgres was free to arrange them differently for each page request, and a
 * row could come back on two pages while another came back on none. The mobile Transactions list
 * showed exactly that: a repeated row at a page boundary, inside a day with several rows.
 *
 * <p>The seeded rows come in groups of {@value #ROWS_PER_GROUP} that share one date and one amount,
 * and a page holds {@value #PAGE_SIZE}, so every page boundary but the last falls inside a group --
 * the place where the order rests entirely on the tiebreak.
 */
class TransactionSearchPagingStabilityIT extends AbstractIntegrationTest {

    private static final int PAGE_SIZE = 20;
    private static final int ROWS_PER_GROUP = 7;
    private static final int GROUPS = 30;
    private static final int ROWS = GROUPS * ROWS_PER_GROUP;

    @Autowired private TestRestTemplate restTemplate;
    @Autowired private UserRepository userRepository;
    @Autowired private AccountRepository accountRepository;
    @Autowired private TransactionRepository transactionRepository;
    @Autowired private JwtService jwtService;
    @Autowired private RefreshTokenRepository refreshTokens;
    @Autowired private JdbcTemplate jdbc;
    private final ObjectMapper mapper = new ObjectMapper();

    private record Seeded(User user, List<Transaction> rows) {}

    /** Called with each page's ids once that page has been read, before the next one is requested. */
    private interface BetweenPages {
        void afterPage(List<UUID> idsOnThatPage);
    }

    private Seeded seed() {
        User user = new User();
        user.setEmail("txn-paging-stability-it-" + UUID.randomUUID() + "@example.com");
        user.setPasswordHash("irrelevant-for-this-test");
        user.setFullName("Txn Paging Stability IT");
        user.setPhoneVerified(true);
        user = userRepository.save(user);
        Account account = new Account();
        account.setUserId(user.getId());
        account.setName("Account");
        account.setAccountType(Account.Type.SAVINGS);
        account.setBalance(BigDecimal.TEN);
        account = accountRepository.save(account);
        // Inserted in a shuffled order (fixed seed), so the table is not already laid out in the
        // order any of the four sorts asks for.
        List<Integer> insertOrder = new ArrayList<>();
        for (int i = 0; i < ROWS; i++) insertOrder.add(i);
        Collections.shuffle(insertOrder, new Random(42));
        List<Transaction> rows = new ArrayList<>();
        for (int i : insertOrder) {
            int group = i / ROWS_PER_GROUP;
            Transaction t = new Transaction();
            t.setUserId(user.getId());
            t.setAccountId(account.getId());
            t.setAmount(BigDecimal.valueOf(100 + group));
            t.setTxnType(Transaction.Type.EXPENSE);
            t.setTxnDate(LocalDate.of(2026, 7, 1).plusDays(group));
            t.setDescription("ROW " + i);
            rows.add(transactionRepository.save(t));
        }
        return new Seeded(user, rows);
    }

    private JsonNode page(User user, String sortField, String sortDir, int page) throws Exception {
        return page(user, sortField, sortDir, page, null);
    }

    private JsonNode page(User user, String sortField, String sortDir, int page, UUID after) throws Exception {
        HttpHeaders headers = new HttpHeaders();
        headers.setBearerAuth(TestSessions.accessTokenFor(jwtService, refreshTokens, user));
        String query = "?page=" + page + "&size=" + PAGE_SIZE + "&sortField=" + sortField + "&sortDir=" + sortDir
                + (after == null ? "" : "&after=" + after);
        ResponseEntity<String> response = restTemplate.exchange(
                "/api/v1/transactions" + query, HttpMethod.GET, new HttpEntity<>(headers), String.class);
        assertThat(response.getStatusCode()).as(query).isEqualTo(HttpStatus.OK);
        return mapper.readTree(response.getBody()).get("data");
    }

    /** Every id the endpoint returns, page after page, in the order it returned them. */
    private List<UUID> idsAcrossAllPages(User user, String sortField, String sortDir, BetweenPages between)
            throws Exception {
        List<UUID> ids = new ArrayList<>();
        int totalPages = 1;
        for (int p = 0; p < totalPages; p++) {
            JsonNode data = page(user, sortField, sortDir, p);
            totalPages = data.get("totalPages").asInt();
            List<UUID> onThisPage = new ArrayList<>();
            data.get("content").forEach(row -> onThisPage.add(UUID.fromString(row.get("id").asText())));
            ids.addAll(onThisPage);
            between.afterPage(onThisPage);
        }
        return ids;
    }

    private List<UUID> idsAcrossAllPages(User user, String sortField, String sortDir) throws Exception {
        return idsAcrossAllPages(user, sortField, sortDir, idsOnThatPage -> { });
    }

    private static List<UUID> ids(List<Transaction> rows) {
        return rows.stream().map(Transaction::getId).toList();
    }

    private static Comparator<Transaction> bySortField(String sortField) {
        return "amount".equals(sortField)
                ? Comparator.comparing(Transaction::getAmount)
                : Comparator.comparing(Transaction::getTxnDate);
    }

    private static <T> Comparator<T> inDirection(String sortDir, Comparator<T> ascending) {
        return "desc".equals(sortDir) ? ascending.reversed() : ascending;
    }

    @ParameterizedTest(name = "sortField={0} sortDir={1}")
    @CsvSource({"date,desc", "date,asc", "amount,desc", "amount,asc"})
    void everyRowAppearsExactlyOnceAcrossPages(String sortField, String sortDir) throws Exception {
        Seeded s = seed();

        List<UUID> returned = idsAcrossAllPages(s.user(), sortField, sortDir);

        assertThat(returned).as("no row is repeated on a later page").doesNotHaveDuplicates();
        assertThat(returned).as("no row is skipped").containsExactlyInAnyOrderElementsOf(ids(s.rows()));
    }

    /**
     * The list is rarely read in one go: someone scrolls, recategorises a row, scrolls on, and
     * background sweeps write to rows in the meantime. A write that leaves the date and the amount
     * alone must not move the row -- Postgres stores the new version somewhere else in the table,
     * and with nothing but the date to sort on, that was enough to change the row's place among the
     * others of its day.
     */
    @ParameterizedTest(name = "sortField={0} sortDir={1}")
    @CsvSource({"date,desc", "date,asc", "amount,desc", "amount,asc"})
    void everyRowAppearsExactlyOnceWhenRowsAreWrittenToBetweenPageRequests(String sortField, String sortDir)
            throws Exception {
        Seeded s = seed();

        List<UUID> returned = idsAcrossAllPages(s.user(), sortField, sortDir, idsOnThatPage -> {
            for (UUID id : idsOnThatPage) {
                // counterparty_key is indexed, so Postgres cannot update the row in place.
                jdbc.update("UPDATE transactions SET counterparty_key = ? WHERE id = ?", "touched:" + id, id);
            }
        });

        assertThat(returned).as("no row is repeated on a later page").doesNotHaveDuplicates();
        assertThat(returned).as("no row is skipped").containsExactlyInAnyOrderElementsOf(ids(s.rows()));
    }

    /**
     * Rows that tie come back in the order they were added: newest-added first when the list is
     * descending, oldest-added first when it is ascending. The primary sort is untouched.
     */
    @ParameterizedTest(name = "sortField={0} sortDir={1}")
    @CsvSource({"date,desc", "date,asc", "amount,desc", "amount,asc"})
    void rowsThatTieAreOrderedByWhenTheyWereAdded(String sortField, String sortDir) throws Exception {
        Seeded s = seed();
        // The database keeps microseconds. Every seeded row is expected to have its own creation
        // instant at that precision; if two ever collide this says so, rather than the ordering
        // assertion below failing confusingly.
        assertThat(s.rows().stream().map(t -> t.getCreatedAt().truncatedTo(ChronoUnit.MICROS)).distinct().count())
                .as("each seeded row has its own creation instant").isEqualTo(ROWS);
        List<Transaction> expected = s.rows().stream()
                .sorted(inDirection(sortDir, bySortField(sortField).thenComparing(Transaction::getCreatedAt)))
                .toList();

        assertThat(idsAcrossAllPages(s.user(), sortField, sortDir)).containsExactlyElementsOf(ids(expected));
    }

    /**
     * Rows written by one SQL statement (a migration, a backfill) share a creation instant too, so
     * the creation time cannot be the last word: the id settles whatever is still tied.
     */
    @ParameterizedTest(name = "sortField={0} sortDir={1}")
    @CsvSource({"date,desc", "date,asc", "amount,desc", "amount,asc"})
    void rowsThatAlsoShareACreationInstantAreOrderedById(String sortField, String sortDir) throws Exception {
        Seeded s = seed();
        int updated = jdbc.update(
                "UPDATE transactions SET created_at = TIMESTAMPTZ '2026-07-10 09:00:00+00' WHERE user_id = ?",
                s.user().getId());
        assertThat(updated).isEqualTo(ROWS);
        // Postgres orders a uuid by its bytes, which is the order of its text form. UUID.compareTo
        // compares two signed longs instead and disagrees with it.
        List<Transaction> expected = s.rows().stream()
                .sorted(inDirection(sortDir, bySortField(sortField).thenComparing(t -> t.getId().toString())))
                .toList();

        assertThat(idsAcrossAllPages(s.user(), sortField, sortDir)).containsExactlyElementsOf(ids(expected));
    }

    /**
     * Pages the way the mobile list does once it knows the server understands {@code after}: each
     * request names the last row it already holds, and also still sends the page number, which a
     * server that does not know {@code after} falls back on.
     */
    private List<UUID> idsAcrossAllPagesFollowingTheLastRow(User user, String sortField, String sortDir,
                                                            BetweenPages between) throws Exception {
        List<UUID> ids = new ArrayList<>();
        UUID after = null;
        for (int p = 0; p < 4 * ROWS / PAGE_SIZE; p++) {
            JsonNode data = page(user, sortField, sortDir, p, after);
            List<UUID> onThisPage = new ArrayList<>();
            data.get("content").forEach(row -> onThisPage.add(UUID.fromString(row.get("id").asText())));
            ids.addAll(onThisPage);
            if (onThisPage.isEmpty() || data.get("page").asInt() + 1 >= data.get("totalPages").asInt()) {
                return ids;
            }
            after = onThisPage.get(onThisPage.size() - 1);
            between.afterPage(onThisPage);
        }
        throw new AssertionError("the list never reported a last page");
    }

    private Transaction newRow(Seeded s, LocalDate date, BigDecimal amount, String description) {
        Transaction t = new Transaction();
        t.setUserId(s.user().getId());
        t.setAccountId(s.rows().get(0).getAccountId());
        t.setAmount(amount);
        t.setTxnType(Transaction.Type.EXPENSE);
        t.setTxnDate(date);
        t.setDescription(description);
        return transactionRepository.save(t);
    }

    /**
     * A page number counts rows from the top of the list, so a row that arrives above the reader
     * (an import finishing, a payment added on another device) pushes every later row down by one,
     * and the next page starts with a row the reader already has. Asking for "the rows after this
     * one" does not count from the top, so nothing shifts.
     */
    @ParameterizedTest(name = "sortField={0} sortDir={1}")
    @CsvSource({"date,desc", "date,asc", "amount,desc", "amount,asc"})
    void followingTheLastRow_everyRowAppearsExactlyOnceWhenRowsAreAddedBetweenPageRequests(
            String sortField, String sortDir) throws Exception {
        Seeded s = seed();

        List<UUID> returned = idsAcrossAllPagesFollowingTheLastRow(s.user(), sortField, sortDir, idsOnThatPage -> {
            // One row that sorts before everything seeded and one that sorts after it, so whichever
            // way the list runs, one of them lands above the reader.
            newRow(s, LocalDate.of(2020, 1, 1), BigDecimal.ONE, "ADDED EARLY AND SMALL");
            newRow(s, LocalDate.of(2030, 1, 1), BigDecimal.valueOf(999_999), "ADDED LATE AND LARGE");
        });

        assertThat(returned).as("no row is repeated on a later page").doesNotHaveDuplicates();
        assertThat(returned).as("no row that was there from the start is skipped").containsAll(ids(s.rows()));
    }

    /**
     * The mirror case: a row the reader already has is deleted, every later row moves up by one,
     * and a page number would step over the row that moved into the gap.
     */
    @ParameterizedTest(name = "sortField={0} sortDir={1}")
    @CsvSource({"date,desc", "date,asc", "amount,desc", "amount,asc"})
    void followingTheLastRow_everyRowAppearsExactlyOnceWhenRowsAreDeletedBetweenPageRequests(
            String sortField, String sortDir) throws Exception {
        Seeded s = seed();

        List<UUID> returned = idsAcrossAllPagesFollowingTheLastRow(s.user(), sortField, sortDir,
                idsOnThatPage -> transactionRepository.deleteById(idsOnThatPage.get(0)));

        assertThat(returned).as("no row is repeated on a later page").doesNotHaveDuplicates();
        assertThat(returned).as("no row is skipped").containsExactlyInAnyOrderElementsOf(ids(s.rows()));
    }

    /** The row the reader names may itself be the one that was deleted. Its place still stands. */
    @ParameterizedTest(name = "sortField={0} sortDir={1}")
    @CsvSource({"date,desc", "date,asc", "amount,desc", "amount,asc"})
    void followingTheLastRow_stillWorksWhenThatRowHasBeenDeleted(String sortField, String sortDir)
            throws Exception {
        Seeded s = seed();

        List<UUID> returned = idsAcrossAllPagesFollowingTheLastRow(s.user(), sortField, sortDir,
                idsOnThatPage -> transactionRepository.deleteById(idsOnThatPage.get(idsOnThatPage.size() - 1)));

        assertThat(returned).as("no row is repeated on a later page").doesNotHaveDuplicates();
        assertThat(returned).as("no row is skipped").containsExactlyInAnyOrderElementsOf(ids(s.rows()));
    }

    /**
     * {@code after} only ever names a place in the caller's own list. Another customer's
     * transaction, or an id that is no transaction at all, is not a place in it, so the request is
     * answered by its page number as if {@code after} had not been sent.
     */
    @Test
    void anAfterThatIsNotTheCallersOwnTransactionIsIgnored() throws Exception {
        Seeded mine = seed();
        Seeded someoneElse = seed();
        JsonNode plain = page(mine.user(), "date", "desc", 1, null);

        JsonNode withAnothersRow = page(mine.user(), "date", "desc", 1, someoneElse.rows().get(0).getId());
        JsonNode withNoSuchRow = page(mine.user(), "date", "desc", 1, UUID.randomUUID());

        assertThat(withAnothersRow).isEqualTo(plain);
        assertThat(withNoSuchRow).isEqualTo(plain);
        assertThat(plain.get("page").asInt()).isEqualTo(1);
    }

    /**
     * Continued from a row, the response describes what is left rather than the whole list: it is
     * always the first page of the remainder. "More to fetch" stays {@code page + 1 < totalPages}.
     */
    @Test
    void followingTheLastRow_theResponseCountsOnlyWhatIsLeft() throws Exception {
        Seeded s = seed();
        JsonNode first = page(s.user(), "date", "desc", 0, null);
        UUID lastOnFirstPage = UUID.fromString(first.get("content").get(PAGE_SIZE - 1).get("id").asText());

        JsonNode rest = page(s.user(), "date", "desc", 1, lastOnFirstPage);

        assertThat(first.get("totalElements").asInt()).isEqualTo(ROWS);
        assertThat(rest.get("page").asInt()).isEqualTo(0);
        assertThat(rest.get("totalElements").asInt()).isEqualTo(ROWS - PAGE_SIZE);
        assertThat(rest.get("totalPages").asInt()).isEqualTo((ROWS - PAGE_SIZE + PAGE_SIZE - 1) / PAGE_SIZE);
        assertThat(rest.get("content")).hasSize(PAGE_SIZE);
        // And it is the same page the page number would have given, nothing having changed between.
        assertThat(rest.get("content")).isEqualTo(page(s.user(), "date", "desc", 1, null).get("content"));
    }

    /**
     * "After this row" has to mean the same thing the sort means, all the way down to the id: with
     * every row sharing a date, an amount and a creation instant, the id alone says which rows
     * follow the one named.
     */
    @ParameterizedTest(name = "sortField={0} sortDir={1}")
    @CsvSource({"date,desc", "date,asc", "amount,desc", "amount,asc"})
    void followingTheLastRow_agreesWithTheSortWhenOnlyTheIdTellsRowsApart(String sortField, String sortDir)
            throws Exception {
        Seeded s = seed();
        jdbc.update("UPDATE transactions SET created_at = TIMESTAMPTZ '2026-07-10 09:00:00+00', "
                + "txn_date = DATE '2026-07-10', amount = 250 WHERE user_id = ?", s.user().getId());
        List<UUID> expected = s.rows().stream().map(Transaction::getId)
                .sorted(inDirection(sortDir, Comparator.comparing(UUID::toString)))
                .toList();

        List<UUID> returned = idsAcrossAllPagesFollowingTheLastRow(s.user(), sortField, sortDir, idsOnThatPage -> { });

        assertThat(returned).containsExactlyElementsOf(expected);
    }
}
