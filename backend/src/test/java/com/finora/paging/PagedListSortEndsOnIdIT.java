package com.finora.paging;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import com.finora.AbstractIntegrationTest;
import com.finora.controller.AdminController;
import com.finora.integrations.google.merchant.MerchantTemplateAdminService;
import com.finora.service.AdminHeldImportService;
import com.finora.service.AdminLearningQueueService;
import com.finora.service.AdminNotificationService;
import com.finora.service.AdminUserService;
import com.finora.service.HeldStatementFilter;
import com.finora.service.HeldStatementService;
import com.finora.service.MerchantReviewService;
import com.finora.service.ReferralService;
import com.finora.service.SubscriptionService;
import com.finora.support.FeedbackService;
import com.finora.support.SupportTicketService;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.domain.Sort;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;

import java.util.List;
import java.util.UUID;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Every list a client pages through must sort on something unique in the end.
 *
 * <p>A sort that stops at a timestamp leaves rows with the same timestamp in no defined order.
 * Postgres may then arrange them differently for each page request, so one row comes back on two
 * pages and another on none. The transaction search did exactly that on its date (see
 * {@code TransactionSearchPagingStabilityIT}, which proves the effect row by row). The lists here
 * sort on a creation time, where a tie needs two rows from the same microsecond: rare for rows
 * made one at a time, ordinary for rows written by one SQL statement.
 *
 * <p>Rather than seed a dozen kinds of row into tables the whole suite shares, each test reads the
 * SQL the list actually sends and checks that its ORDER BY ends on the primary key, in the
 * direction the list is shown in. The primary key is unique, so an ORDER BY that ends on it leaves
 * nothing for Postgres to choose.
 */
class PagedListSortEndsOnIdIT extends AbstractIntegrationTest {

    private static final Pattern ORDER_BY_OF_A_PAGED_SELECT =
            Pattern.compile("order by (.+?) (?:offset \\?|fetch first \\?)");
    private static final Pattern ID_TERM = Pattern.compile("\\w+\\.id(?: (asc|desc))?");
    private static final Pattern POSITION_TERM = Pattern.compile("(\\d+)(?: (asc|desc))?");
    private static final Pattern SELECT_LIST = Pattern.compile("^select (.+?) from ");

    @Autowired private SupportTicketService supportTicketService;
    @Autowired private FeedbackService feedbackService;
    @Autowired private HeldStatementService heldStatementService;
    @Autowired private AdminHeldImportService adminHeldImportService;
    @Autowired private SubscriptionService subscriptionService;
    @Autowired private ReferralService referralService;
    @Autowired private MerchantReviewService merchantReviewService;
    @Autowired private MerchantTemplateAdminService merchantTemplateAdminService;
    @Autowired private AdminLearningQueueService adminLearningQueueService;
    @Autowired private AdminNotificationService adminNotificationService;
    @Autowired private AdminUserService adminUserService;
    @Autowired private AdminController adminController;

    private static final HeldStatementFilter NO_FILTER = new HeldStatementFilter(null, null, null, null);

    @AfterEach
    void clearSecurityContext() {
        SecurityContextHolder.clearContext();
    }

    /** The SQL statements Hibernate sent while {@code call} ran, each on one line, lower case. */
    private static List<String> sqlSentBy(Runnable call) {
        Logger sqlLogger = (Logger) LoggerFactory.getLogger("org.hibernate.SQL");
        Level levelBefore = sqlLogger.getLevel();
        boolean additiveBefore = sqlLogger.isAdditive();
        ListAppender<ILoggingEvent> captured = new ListAppender<>();
        captured.start();
        sqlLogger.setLevel(Level.DEBUG);
        sqlLogger.setAdditive(false);
        sqlLogger.addAppender(captured);
        try {
            call.run();
        } finally {
            sqlLogger.detachAppender(captured);
            sqlLogger.setAdditive(additiveBefore);
            sqlLogger.setLevel(levelBefore);
        }
        return captured.list.stream()
                .map(event -> event.getFormattedMessage().replaceAll("\\s+", " ").trim().toLowerCase())
                .toList();
    }

    private static void assertSortEndsOnId(Sort.Direction shownIn, Runnable list) {
        List<String> statements = sqlSentBy(list);
        List<String> pagedSelects = statements.stream()
                .filter(sql -> ORDER_BY_OF_A_PAGED_SELECT.matcher(sql).find())
                .toList();
        assertThat(pagedSelects)
                .as("exactly one paged, sorted select among the statements sent: %s", statements)
                .hasSize(1);
        String sql = pagedSelects.get(0);
        Matcher orderByMatcher = ORDER_BY_OF_A_PAGED_SELECT.matcher(sql);
        orderByMatcher.find();
        String orderBy = orderByMatcher.group(1);
        String[] terms = orderBy.split(",");
        String lastTerm = terms[terms.length - 1].trim();

        Matcher id = ID_TERM.matcher(lastTerm);
        Matcher position = POSITION_TERM.matcher(lastTerm);
        String directionWord;
        if (position.matches()) {
            // A query that selects named columns is sorted by a column's position in the select
            // list ("order by 3, 1"), so the position has to be looked up there.
            Matcher selectList = SELECT_LIST.matcher(sql);
            assertThat(selectList.find()).as("a select list in: %s", sql).isTrue();
            String[] columns = selectList.group(1).split(",");
            String sortedColumn = columns[Integer.parseInt(position.group(1)) - 1].trim();
            assertThat(sortedColumn)
                    .as("the sort must end on the primary key, but was: order by %s, where the last"
                            + " position is the column %s", orderBy, sortedColumn)
                    .matches("\\w+\\.id");
            directionWord = position.group(2);
        } else {
            assertThat(id.matches())
                    .as("the sort must end on the primary key, but was: order by %s", orderBy)
                    .isTrue();
            directionWord = id.group(1);
        }
        Sort.Direction direction = "desc".equals(directionWord) ? Sort.Direction.DESC : Sort.Direction.ASC;
        assertThat(direction)
                .as("the id must run the same way as the rest of the sort: order by %s", orderBy)
                .isEqualTo(shownIn);
    }

    @Test
    void aCustomersOwnSupportTickets() {
        assertSortEndsOnId(Sort.Direction.DESC, () -> supportTicketService.listOwn(UUID.randomUUID(), 0, 20));
    }

    @Test
    void theAdminSupportTicketQueue() {
        assertSortEndsOnId(Sort.Direction.ASC, () -> supportTicketService.adminList(null, null, null, 0, 20));
    }

    @Test
    void theAdminFeedbackList() {
        assertSortEndsOnId(Sort.Direction.DESC, () -> feedbackService.adminList(null, null, 0, 20));
    }

    @Test
    void openHeldStatements() {
        assertSortEndsOnId(Sort.Direction.ASC, () -> heldStatementService.list(0, 20, NO_FILTER));
    }

    @Test
    void resolvedHeldStatements() {
        assertSortEndsOnId(Sort.Direction.DESC, () -> heldStatementService.listResolved(0, 20, NO_FILTER));
    }

    @Test
    void holdsWithoutAReviewRecord() {
        assertSortEndsOnId(Sort.Direction.ASC, () -> heldStatementService.listHoldsWithoutReviewRecord(0, 20));
    }

    @Test
    void heldImports() {
        assertSortEndsOnId(Sort.Direction.ASC, () -> adminHeldImportService.list(0, 20));
    }

    @Test
    void theAdminSubscriptionList() {
        assertSortEndsOnId(Sort.Direction.DESC, () -> subscriptionService.listAll(0, 20));
    }

    @Test
    void theAdminReferralList() {
        assertSortEndsOnId(Sort.Direction.DESC, () -> referralService.listAll(0, 20));
    }

    @Test
    void theMerchantReviewQueue() {
        assertSortEndsOnId(Sort.Direction.ASC, () -> merchantReviewService.queue(0, 20));
    }

    /**
     * The one list that does not need the id: it sorts on the merchant name and then the merchant
     * domain, and the domain is unique (uq_merchant_template_domain, V85). This pins that it still
     * ends on that column.
     */
    @Test
    void theMerchantTemplateList_endsOnItsUniqueDomain() {
        List<String> orderBys = sqlSentBy(() -> merchantTemplateAdminService.listAll(0, 20)).stream()
                .map(ORDER_BY_OF_A_PAGED_SELECT::matcher)
                .filter(Matcher::find)
                .map(m -> m.group(1))
                .toList();
        assertThat(orderBys).hasSize(1);
        assertThat(orderBys.get(0)).matches("\\w+\\.merchant_name, \\w+\\.merchant_domain");
    }

    @Test
    void theLearningQueue_newestFirst() {
        assertSortEndsOnId(Sort.Direction.DESC, () -> adminLearningQueueService.list(null, 0, 20, "createdAt", "desc"));
    }

    @Test
    void theLearningQueue_sortedOnAnotherColumnAscending() {
        assertSortEndsOnId(Sort.Direction.ASC,
                () -> adminLearningQueueService.list(null, 0, 20, "attemptCount", "asc"));
    }

    @Test
    void theAdminNotificationList() {
        assertSortEndsOnId(Sort.Direction.DESC, () -> adminNotificationService.list(null, 0, 20));
    }

    @Test
    void theAdminNotificationList_filteredByStatus() {
        assertSortEndsOnId(Sort.Direction.DESC, () -> adminNotificationService.list("SENT", 0, 20));
    }

    @Test
    void theAdminUserDirectory() {
        assertSortEndsOnId(Sort.Direction.DESC, () -> adminUserService.list(null, null, 0, 20));
    }

    @Test
    void theAuditLog_newestFirst() {
        signInWith("AUDIT_VIEW");
        assertSortEndsOnId(Sort.Direction.DESC,
                () -> adminController.globalAuditLogs(0, 25, null, null, null, "desc"));
    }

    @Test
    void theAuditLog_oldestFirst() {
        signInWith("AUDIT_VIEW");
        assertSortEndsOnId(Sort.Direction.ASC,
                () -> adminController.globalAuditLogs(0, 25, null, null, null, "asc"));
    }

    private static void signInWith(String authority) {
        SecurityContextHolder.getContext().setAuthentication(new UsernamePasswordAuthenticationToken(
                "paged-list-sort-it", null, List.of(new SimpleGrantedAuthority(authority))));
    }
}
