package com.finora.service;

import com.finora.AbstractIntegrationTest;
import com.finora.entity.Transaction;
import com.finora.entity.TransactionRelationship;
import com.finora.repository.TransactionRelationshipRepository;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.util.ReflectionTestUtils;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Postgres accepts at most 65,535 bind parameters per statement, and a query that receives every
 * transaction id of a large account as one IN list exceeds it -- the dashboard threw "PreparedStatement
 * can have at most 65,535 parameters" for a user with 100,000 transactions, and deleting such an
 * account would have hit the same wall. These run against the real database: a mocked repository
 * cannot reproduce a driver limit.
 *
 * <p>The edges' from/to columns are plain UUIDs (no foreign key), so the ids here need no rows.
 */
class TransactionGraphServiceLargeBatchIT extends AbstractIntegrationTest {

    @Autowired private TransactionGraphService graphService;
    @Autowired private TransactionRelationshipRepository relationships;

    private static List<UUID> manyIds(int count) {
        List<UUID> ids = new ArrayList<>(count);
        for (int i = 0; i < count; i++) ids.add(UUID.randomUUID());
        return ids;
    }

    private TransactionRelationship edge(UUID user, UUID from, UUID to, TransactionRelationship.RelationshipType type) {
        return graphService.linkAll(List.of(new TransactionGraphService.PendingEdge(
                user, from, to, type, BigDecimal.TEN, 90, 1,
                TransactionRelationship.Status.AUTO_CONFIRMED,
                TransactionRelationship.DetectionMethod.RULE_ENGINE, null))).get(0);
    }

    private static List<Transaction> asTransactions(List<UUID> ids) {
        List<Transaction> out = new ArrayList<>(ids.size());
        for (UUID id : ids) {
            Transaction t = new Transaction();
            ReflectionTestUtils.setField(t, "id", id);
            out.add(t);
        }
        return out;
    }

    @Test
    void ccPaymentLookupWorksForMoreTransactionsThanOneStatementCanBind() {
        UUID user = UUID.randomUUID();
        UUID payment = UUID.randomUUID();
        edge(user, payment, UUID.randomUUID(), TransactionRelationship.RelationshipType.CC_PAYMENT);
        List<UUID> ids = manyIds(70_000);
        ids.add(payment);
        Collections.shuffle(ids);

        Set<UUID> found = graphService.ccPaymentFromTransactionIds(asTransactions(ids));

        assertThat(found).containsExactly(payment);
    }

    @Test
    void rejectingEdgesWorksForMoreTransactionsThanOneStatementCanBind() {
        // An account with this many transactions being deleted. Each id appears twice in the query
        // (from side and to side), so half as many already overflows.
        UUID user = UUID.randomUUID();
        UUID from = UUID.randomUUID();
        UUID to = UUID.randomUUID();
        TransactionRelationship edge = edge(user, from, to, TransactionRelationship.RelationshipType.TRANSFER);
        List<UUID> ids = manyIds(40_000);
        ids.add(to);
        Collections.shuffle(ids);

        int rejected = graphService.rejectEdgesTouchingTransactions(ids);

        assertThat(rejected).isEqualTo(1);
        assertThat(relationships.findById(edge.getId()).orElseThrow().getStatus())
                .isEqualTo(TransactionRelationship.Status.REJECTED);
    }

    @Test
    void anEdgeWhoseTwoEndsFallInDifferentChunksIsStillFoundOnce() {
        // Enough ids that the two ends are (almost certainly) queried in different chunks: the edge
        // must be seen, and only once, so it is rejected exactly once and counted exactly once.
        UUID user = UUID.randomUUID();
        UUID from = UUID.randomUUID();
        UUID to = UUID.randomUUID();
        edge(user, from, to, TransactionRelationship.RelationshipType.TRANSFER);
        List<UUID> ids = manyIds(40_000);
        ids.add(0, from);
        ids.add(to); // first and last: two chunks apart

        assertThat(graphService.rejectEdgesTouchingTransactions(ids)).isEqualTo(1);
    }
}
