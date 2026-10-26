package com.finora.service;

import com.finora.AbstractIntegrationTest;
import com.finora.accounts.AccountDto;
import com.finora.accounts.AccountService;
import com.finora.dto.DashboardRangeSummaryDto;
import com.finora.dto.DashboardRangeType;
import com.finora.dto.DashboardSummaryDto;
import com.finora.dto.ImportDto.ConfirmRequest;
import com.finora.dto.ImportDto.ConfirmedRow;
import com.finora.dto.NetWorthDto;
import com.finora.entity.Account;
import com.finora.entity.User;
import com.finora.imports.ImportService;
import com.finora.repository.AccountRepository;
import com.finora.repository.MerchantLearningEventRepository;
import com.finora.repository.NetWorthSnapshotRepository;
import com.finora.repository.UserRepository;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.mock.web.MockMultipartFile;

import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * A card whose amount owed is not known (summed from zero, see AmountOwedRule) is left out of
 * every total until the user states it, and the totals say how many cards they left out.
 * Design: docs/superpowers/specs/2026-10-26-card-amount-owed-prompt-design.md.
 */
class UnknownAmountOwedTotalsIT extends AbstractIntegrationTest {

    @Autowired private DashboardService dashboardService;
    @Autowired private DashboardRangeService dashboardRangeService;
    @Autowired private NetWorthService netWorthService;
    @Autowired private NetWorthSnapshotRepository snapshotRepository;
    @Autowired private ImportService importService;
    @Autowired private AccountService accountService;
    @Autowired private AccountRepository accountRepository;
    @Autowired private UserRepository userRepository;
    @Autowired private MerchantLearningEventRepository learningEventRepository;

    private final List<UUID> createdUserIds = new ArrayList<>();

    @AfterEach
    void removeQueuedLearningEvents() {
        if (createdUserIds.isEmpty()) return;
        learningEventRepository.deleteAll(learningEventRepository.findAll().stream()
                .filter(e -> createdUserIds.contains(e.getUserId())).toList());
        createdUserIds.clear();
    }

    private UUID user() {
        User user = new User();
        user.setEmail("unknown-owed-totals-it-" + UUID.randomUUID() + "@example.com");
        user.setPasswordHash("irrelevant-for-this-test");
        user.setFullName("Unknown Owed Totals IT User");
        user.setPhoneVerified(true);
        UUID id = userRepository.save(user).getId();
        createdUserIds.add(id);
        return id;
    }

    private UUID account(UUID userId, Account.Type type, String balance) {
        Account account = new Account();
        account.setUserId(userId);
        account.setName("Totals IT " + type);
        account.setAccountType(type);
        account.setBalance(new BigDecimal(balance));
        return accountRepository.save(account).getId();
    }

    private ConfirmedRow row(LocalDate date, String description, String amount, String type) {
        return new ConfirmedRow(date, description, new BigDecimal(amount), type,
                "Other", true, "rule", null, false, null, null, false);
    }

    /** A payment of 2,000 and spends of 1,500, no balance printed: the card sums to -500.00. */
    private void importWithoutClosing(UUID userId, UUID cardId) throws Exception {
        LocalDate start = LocalDate.of(2025, 4, 1);
        importService.confirm(userId, new MockMultipartFile("file", "card.csv", "text/csv",
                        "irrelevant".getBytes(StandardCharsets.UTF_8)),
                new ConfirmRequest(null, List.of(
                        row(start.plusDays(2), "PAYMENT RECEIVED", "2000.00", "INCOME"),
                        row(start.plusDays(5), "SAMPLE GROCER", "1000.00", "EXPENSE"),
                        row(start.plusDays(9), "SAMPLE FUEL", "500.00", "EXPENSE")),
                        cardId, null, null, null, null, start, start.plusMonths(1).minusDays(1)));
    }

    @Test
    void anUnknownCardIsLeftOutOfEveryTotal_untilTheAmountOwedIsStated() throws Exception {
        UUID user = user();
        account(user, Account.Type.SAVINGS, "10000.00");
        UUID card = account(user, Account.Type.CREDIT_CARD, "0.00");
        Account withDueDate = accountRepository.findById(card).orElseThrow();
        withDueDate.setDueDate(LocalDate.now(java.time.ZoneId.of("Asia/Kolkata")).plusDays(3));
        accountRepository.save(withDueDate);
        importWithoutClosing(user, card);
        assertThat(accountRepository.findById(card).orElseThrow().getBalance()).isEqualByComparingTo("-500.00");

        DashboardSummaryDto summary = dashboardService.summarize(user);
        assertThat(summary.netWorth()).isEqualByComparingTo("10000.00");
        assertThat(summary.totalLiabilities()).isEqualByComparingTo("0.00");
        assertThat(summary.cardsWithUnknownAmountOwed()).isEqualTo(1);
        assertThat(summary.notifications()).as("the due date still matters; the summed figure does not")
                .contains("Totals IT CREDIT_CARD payment is due in 3 day(s).")
                .noneMatch(n -> n.contains("-500"));

        NetWorthDto netWorth = netWorthService.saveSnapshotForToday(user);
        assertThat(netWorth.netWorth()).isEqualByComparingTo("10000.00");
        assertThat(netWorth.totalLiabilities()).isEqualByComparingTo("0.00");
        assertThat(netWorth.cardsWithUnknownAmountOwed()).isEqualTo(1);
        assertThat(snapshotRepository.findTopByUserIdOrderBySnapshotDateDesc(user).orElseThrow().getNetWorth())
                .isEqualByComparingTo("10000.00");

        DashboardRangeSummaryDto range = dashboardRangeService.summarize(user, DashboardRangeType.LAST_3_MONTHS, null, null);
        assertThat(range.cardsWithUnknownAmountOwed()).isEqualTo(1);

        accountService.update(user, card, new AccountDto.CreateRequest("Totals IT card", "CREDIT_CARD",
                new BigDecimal("3000.00"), null, null, null, null, null, null, null, null), user);

        DashboardSummaryDto after = dashboardService.summarize(user);
        assertThat(after.netWorth()).isEqualByComparingTo("7000.00");
        assertThat(after.totalLiabilities()).isEqualByComparingTo("3000.00");
        assertThat(after.cardsWithUnknownAmountOwed()).isZero();
        assertThat(netWorthService.current(user).netWorth()).isEqualByComparingTo("7000.00");
        assertThat(netWorthService.current(user).cardsWithUnknownAmountOwed()).isZero();
    }
}
