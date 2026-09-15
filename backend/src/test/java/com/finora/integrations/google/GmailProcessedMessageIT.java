package com.finora.integrations.google;

import com.finora.AbstractIntegrationTest;
import com.finora.entity.User;
import com.finora.repository.UserRepository;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.data.domain.PageRequest;
import org.springframework.jdbc.core.JdbcTemplate;

import java.sql.Timestamp;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * The C4 storage guarantees, against a real Postgres — because every one of them is enforced by the
 * database rather than by Java, and none of them can be observed anywhere else.
 *
 * <p>{@code GmailMessageDiscoveryServiceTest} proves the service asks for the right things. This
 * proves the schema actually delivers them: the unique index that makes at-least-once safe, the
 * CHECK constraints that keep the provenance table answerable, and the due-query ordering that
 * decides whose mailbox gets looked at.
 */
class GmailProcessedMessageIT extends AbstractIntegrationTest {

    @Autowired private GmailProcessedMessageRepository processedMessages;
    @Autowired private GmailConnectionRepository connections;
    @Autowired private JdbcTemplate jdbc;
    @Autowired private UserRepository userRepository;

    // ---------------------------------------------------------------------------------------
    // Idempotency
    // ---------------------------------------------------------------------------------------

    /**
     * The guarantee the whole resume story rests on. Discovery is at-least-once by design: a run
     * that dies halfway is re-run, and an overlapping window re-lists messages on purpose. Without
     * this index the second pass would write a second row, and the provenance table would report a
     * message decided twice with no way to tell which decision was current.
     */
    @Test
    @DisplayName("one message can be decided at most once per connection")
    void aMessageCannotBeRecordedTwiceForTheSameConnection() {
        UUID connectionId = persistConnection(GmailConnection.Status.CONNECTED, null).getId();
        processedMessages.saveAndFlush(GmailProcessedMessage.trusted(connectionId, "msg-1",
                GmailProcessedMessage.Outcome.DETECTED_NOT_STAGED, "merchant.example"));

        assertThatThrownBy(() -> processedMessages.saveAndFlush(
                GmailProcessedMessage.trusted(connectionId, "msg-1",
                        GmailProcessedMessage.Outcome.DETECTED_NOT_STAGED, "merchant.example")))
                .isInstanceOf(DataIntegrityViolationException.class);
    }

    /** Two mailboxes can legitimately both receive the same merchant message id -- ids are unique
     *  per mailbox, not globally -- so the constraint must be scoped to the connection. */
    @Test
    void thesameMessageIdInADifferentMailboxIsAllowed() {
        UUID first = persistConnection(GmailConnection.Status.CONNECTED, null).getId();
        UUID second = persistConnection(GmailConnection.Status.CONNECTED, null).getId();

        processedMessages.saveAndFlush(GmailProcessedMessage.trusted(first, "shared-id",
                GmailProcessedMessage.Outcome.DETECTED_NOT_STAGED, "merchant.example"));
        processedMessages.saveAndFlush(GmailProcessedMessage.trusted(second, "shared-id",
                GmailProcessedMessage.Outcome.DETECTED_NOT_STAGED, "merchant.example"));

        assertThat(processedMessages.countByConnectionId(first)).isEqualTo(1);
        assertThat(processedMessages.countByConnectionId(second)).isEqualTo(1);
    }

    /**
     * The subtraction that saves the expensive call. It must be scoped to the connection: returning
     * another mailbox's ids would make discovery skip messages it had never actually examined, and
     * a skipped message leaves no trace to notice.
     */
    @Test
    void alreadyProcessedIdsAreScopedToTheConnection() {
        UUID mine = persistConnection(GmailConnection.Status.CONNECTED, null).getId();
        UUID theirs = persistConnection(GmailConnection.Status.CONNECTED, null).getId();
        processedMessages.saveAndFlush(GmailProcessedMessage.trusted(mine, "mine-1",
                GmailProcessedMessage.Outcome.DETECTED_NOT_STAGED, "merchant.example"));
        processedMessages.saveAndFlush(GmailProcessedMessage.trusted(theirs, "theirs-1",
                GmailProcessedMessage.Outcome.DETECTED_NOT_STAGED, "merchant.example"));

        assertThat(processedMessages.findAlreadyProcessedIds(mine,
                List.of("mine-1", "theirs-1", "never-seen")))
                .containsExactly("mine-1");
    }

    // ---------------------------------------------------------------------------------------
    // The CHECK constraints
    // ---------------------------------------------------------------------------------------

    /**
     * The provenance table is what support reads to answer "why did nothing appear for this
     * receipt?". A typo'd outcome would not break anything loudly — it would quietly produce a row
     * nobody can interpret, which is worse than a rejected write.
     */
    @Test
    void anUnknownOutcomeIsRejectedByTheDatabase() {
        UUID connectionId = persistConnection(GmailConnection.Status.CONNECTED, null).getId();

        assertThatThrownBy(() -> jdbc.update("""
                insert into gmail_processed_messages (id, connection_id, gmail_message_id, outcome)
                values (?, ?, ?, ?)
                """, UUID.randomUUID(), connectionId, "msg-typo", "SKIPPED_UNTRUSTED"))
                .isInstanceOf(DataIntegrityViolationException.class);
    }

    /** A row cannot claim both that a message was trusted and that it was skipped for a reason --
     *  the two together are unanswerable, and the constraint is what makes that unrepresentable. */
    @Test
    void anUnknownSkipReasonIsRejectedByTheDatabase() {
        UUID connectionId = persistConnection(GmailConnection.Status.CONNECTED, null).getId();

        assertThatThrownBy(() -> jdbc.update("""
                insert into gmail_processed_messages
                    (id, connection_id, gmail_message_id, outcome, skip_reason)
                values (?, ?, ?, ?, ?)
                """, UUID.randomUUID(), connectionId, "msg-bad-reason",
                "SKIPPED_UNTRUSTED_SENDER", "TRUSTED"))
                .isInstanceOf(DataIntegrityViolationException.class);
    }

    /** Every verdict the gate can actually produce must be storable. A constraint that rejected one
     *  of them would turn a routine skip into a failed run. */
    @Test
    void everyRefusalVerdictTheGateProducesIsStorable() {
        UUID connectionId = persistConnection(GmailConnection.Status.CONNECTED, null).getId();
        int stored = 0;
        for (SenderAuthenticationService.Verdict verdict : SenderAuthenticationService.Verdict.values()) {
            if (verdict == SenderAuthenticationService.Verdict.TRUSTED) continue;
            processedMessages.saveAndFlush(GmailProcessedMessage.skipped(connectionId,
                    "msg-" + verdict, new SenderAuthenticationService.Result(verdict, null)));
            stored++;
        }
        assertThat(processedMessages.countByConnectionId(connectionId)).isEqualTo(stored);
    }

    // ---------------------------------------------------------------------------------------
    // Who gets looked at, and in what order
    // ---------------------------------------------------------------------------------------

    /**
     * Never-checked connections sort first, so a mailbox connected moments ago is picked up on the
     * next tick instead of queueing behind every established one. {@code nulls first} is not the
     * default in Postgres for ascending order, so this is exactly the kind of thing that works in
     * HQL and silently does the opposite in SQL.
     */
    @Test
    @DisplayName("a never-checked mailbox is looked at before a recently-checked one")
    void neverCheckedConnectionsComeFirst() {
        GmailConnection checkedRecently = persistConnection(GmailConnection.Status.CONNECTED,
                Instant.now().minus(Duration.ofHours(6)));
        GmailConnection neverChecked = persistConnection(GmailConnection.Status.CONNECTED, null);

        List<GmailConnection> due = connections.findDueForDiscovery(
                Instant.now().minus(Duration.ofHours(1)), Instant.now(), PageRequest.of(0, 10));

        assertThat(due).extracting(GmailConnection::getId)
                .containsSubsequence(neverChecked.getId(), checkedRecently.getId());
    }

    /** A dead grant costs a token-refresh request to rediscover. Excluding it at the query is what
     *  keeps REAUTH_REQUIRED from being a standing per-tick tax that nothing ever clears. */
    @Test
    void connectionsNeedingReauthAreNotDue() {
        GmailConnection needsReauth = persistConnection(GmailConnection.Status.REAUTH_REQUIRED, null);

        List<GmailConnection> due = connections.findDueForDiscovery(
                Instant.now().minus(Duration.ofHours(1)), Instant.now(), PageRequest.of(0, 10));

        assertThat(due).extracting(GmailConnection::getId).doesNotContain(needsReauth.getId());
    }

    /** The rest interval. A mailbox checked a minute ago is not due, which is what stops one tick's
     *  slice from being the same mailboxes forever while the tail never comes up. */
    @Test
    void aRecentlyCheckedConnectionIsNotDue() {
        GmailConnection justChecked = persistConnection(GmailConnection.Status.CONNECTED,
                Instant.now().minus(Duration.ofMinutes(1)));

        List<GmailConnection> due = connections.findDueForDiscovery(
                Instant.now().minus(Duration.ofHours(1)), Instant.now(), PageRequest.of(0, 10));

        assertThat(due).extracting(GmailConnection::getId).doesNotContain(justChecked.getId());
    }

    /**
     * The whole point of the backoff columns: a connection whose last discovery run failed (so
     * {@code lastDiscoveryAt} never moved, and it would otherwise sort at the very front of this
     * query forever) is excluded until {@code discoveryRetryAfter} passes, even though it is
     * otherwise indistinguishable from the never-checked case above.
     */
    @Test
    @DisplayName("a connection backing off from a discovery failure is not due until its retry time passes")
    void aConnectionInDiscoveryBackoffIsNotDueYet() {
        GmailConnection backingOff = persistConnection(GmailConnection.Status.CONNECTED, null);
        backingOff.recordDiscoveryFailure(Instant.now());
        connections.saveAndFlush(backingOff);

        List<GmailConnection> due = connections.findDueForDiscovery(
                Instant.now().minus(Duration.ofHours(1)), Instant.now(), PageRequest.of(0, 10));

        assertThat(due).extracting(GmailConnection::getId).doesNotContain(backingOff.getId());
    }

    /** Once {@code discoveryRetryAfter} is in the past, the connection is due again like any other. */
    @Test
    @DisplayName("a connection whose discovery backoff has elapsed is due again")
    void aConnectionPastItsDiscoveryBackoffIsDueAgain() {
        GmailConnection recovered = persistConnection(GmailConnection.Status.CONNECTED, null);
        // A raw update rather than recordDiscoveryFailure(): that method only ever computes a
        // retry time in the future, so backdating it is the only way to put a row on the "backoff
        // already elapsed" side of the predicate under test.
        jdbc.update("update gmail_connections set discovery_retry_after = ? where id = ?",
                Timestamp.from(Instant.now().minus(Duration.ofMinutes(1))), recovered.getId());

        List<GmailConnection> due = connections.findDueForDiscovery(
                Instant.now().minus(Duration.ofHours(1)), Instant.now(), PageRequest.of(0, 10));

        assertThat(due).extracting(GmailConnection::getId).contains(recovered.getId());
    }

    // ---------------------------------------------------------------------------------------
    // Who still gets extracted, backoff or not
    // ---------------------------------------------------------------------------------------

    /** The base case: a connection carrying an unprocessed receipt is found. */
    @Test
    @DisplayName("a connection with a DETECTED_NOT_STAGED message is found for extraction")
    void aConnectionWithAPendingMessageIsFoundForExtraction() {
        UUID connectionId = persistConnection(GmailConnection.Status.CONNECTED, null).getId();
        processedMessages.saveAndFlush(GmailProcessedMessage.trusted(connectionId, "msg-1",
                GmailProcessedMessage.Outcome.DETECTED_NOT_STAGED, "merchant.example"));

        List<GmailConnection> pending = connections.findWithPendingExtraction(PageRequest.of(0, 10));

        assertThat(pending).extracting(GmailConnection::getId).contains(connectionId);
    }

    /**
     * The fix this whole class of test exists for: {@code findDueForDiscovery} excludes a backed-off
     * connection entirely, but its {@code DETECTED_NOT_STAGED} backlog is still real and should
     * still be drained. This is the one assertion that actually proves the two queries are
     * independent, not the same predicate with the backoff clause dropped by accident.
     */
    @Test
    @DisplayName("a connection backing off from discovery is still found for extraction")
    void aConnectionInDiscoveryBackoffIsStillFoundForExtraction() {
        GmailConnection backingOff = persistConnection(GmailConnection.Status.CONNECTED, null);
        backingOff.recordDiscoveryFailure(Instant.now());
        connections.saveAndFlush(backingOff);
        processedMessages.saveAndFlush(GmailProcessedMessage.trusted(backingOff.getId(), "msg-1",
                GmailProcessedMessage.Outcome.DETECTED_NOT_STAGED, "merchant.example"));

        // Sanity half of the proof: confirm it really is excluded from the discovery-due query --
        // otherwise this test would not actually be exercising the backoff case it claims to.
        List<GmailConnection> due = connections.findDueForDiscovery(
                Instant.now().minus(Duration.ofHours(1)), Instant.now(), PageRequest.of(0, 10));
        assertThat(due).extracting(GmailConnection::getId).doesNotContain(backingOff.getId());

        List<GmailConnection> pending = connections.findWithPendingExtraction(PageRequest.of(0, 10));
        assertThat(pending).extracting(GmailConnection::getId).contains(backingOff.getId());
    }

    /** A connection with nothing outstanding -- no messages at all -- must not show up and cost a
     *  wasted extraction attempt every tick forever. */
    @Test
    @DisplayName("a connection with no messages at all is not found for extraction")
    void aConnectionWithNoMessagesIsNotFoundForExtraction() {
        GmailConnection empty = persistConnection(GmailConnection.Status.CONNECTED, null);

        List<GmailConnection> pending = connections.findWithPendingExtraction(PageRequest.of(0, 10));

        assertThat(pending).extracting(GmailConnection::getId).doesNotContain(empty.getId());
    }

    /** A connection whose only messages already reached a terminal outcome (parsed, parse-failed,
     *  skipped) has nothing left for extraction to do -- it must not keep appearing forever just
     *  because it once had mail. */
    @Test
    @DisplayName("a connection whose messages are all already resolved is not found for extraction")
    void aConnectionWithOnlyResolvedMessagesIsNotFoundForExtraction() {
        UUID connectionId = persistConnection(GmailConnection.Status.CONNECTED, null).getId();
        GmailProcessedMessage parsed = GmailProcessedMessage.trusted(connectionId, "msg-parsed",
                GmailProcessedMessage.Outcome.DETECTED_NOT_STAGED, "merchant.example");
        parsed.markParsed();
        processedMessages.saveAndFlush(parsed);
        processedMessages.saveAndFlush(GmailProcessedMessage.skipped(connectionId, "msg-skipped",
                new SenderAuthenticationService.Result(
                        SenderAuthenticationService.Verdict.DOMAIN_NOT_TRUSTED, "untrusted.example")));

        List<GmailConnection> pending = connections.findWithPendingExtraction(PageRequest.of(0, 10));

        assertThat(pending).extracting(GmailConnection::getId).doesNotContain(connectionId);
    }

    /** A dead grant means extraction's own access-token fetch fails identically to discovery's --
     *  same reasoning {@code findDueForDiscovery} excludes REAUTH_REQUIRED for, so a backlog left
     *  behind by a connection that has since died must not be attempted forever. */
    @Test
    @DisplayName("a connection needing reauth is not found for extraction, even with a backlog")
    void aConnectionNeedingReauthIsNotFoundForExtraction() {
        GmailConnection needsReauth = persistConnection(GmailConnection.Status.REAUTH_REQUIRED, null);
        processedMessages.saveAndFlush(GmailProcessedMessage.trusted(needsReauth.getId(), "msg-1",
                GmailProcessedMessage.Outcome.DETECTED_NOT_STAGED, "merchant.example"));

        List<GmailConnection> pending = connections.findWithPendingExtraction(PageRequest.of(0, 10));

        assertThat(pending).extracting(GmailConnection::getId).doesNotContain(needsReauth.getId());
    }

    private GmailConnection persistConnection(GmailConnection.Status status, Instant lastDiscoveryAt) {
        GmailConnection connection = new GmailConnection();
        connection.setUserId(newUser().getId());
        connection.setGoogleUserId("google-sub-" + UUID.randomUUID());
        connection.setGoogleEmail("mailbox-" + UUID.randomUUID() + "@example.test");
        connection.setGrantedScopes(GmailApiClient.GMAIL_READONLY_SCOPE);
        connection.setStatus(status);
        connection.setLastDiscoveryAt(lastDiscoveryAt);
        return connections.saveAndFlush(connection);
    }

    /** gmail_connections.user_id is a real foreign key, so a connection needs a real owner. */
    private User newUser() {
        User user = new User();
        user.setEmail("gmail-c4-it-" + UUID.randomUUID() + "@example.test");
        user.setPasswordHash("irrelevant-for-this-test");
        user.setFullName("Gmail C4 IT User");
        user.setPhoneVerified(true);
        return userRepository.save(user);
    }
}
