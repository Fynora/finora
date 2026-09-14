package com.finora.repository;

import com.finora.entity.Payment;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;
import java.util.UUID;

public interface PaymentRepository extends JpaRepository<Payment, UUID> {

    List<Payment> findByUserIdOrderByCreatedAtDesc(UUID userId);

    List<Payment> findBySubscriptionIdOrderByCreatedAtDesc(UUID subscriptionId);

    /** RazorpayWebhookDispatcher.handleCharged's own idempotency guard -- see that method's doc for
     *  why a duplicate {@code subscription.charged} dispatch (a genuine Razorpay retry, or a
     *  WebhookEventRecoverySweepService re-dispatch of a row that had actually already completed)
     *  must not insert a second Payment row for the same charge. */
    boolean existsByProviderTransactionId(String providerTransactionId);

    /** AccountPurgeSweepService. Native, bypassing Hibernate entirely -- same naming discipline as
     *  {@code SubscriptionRepository.hardDeleteByUserId}. Unlike subscription_events/plan_changes,
     *  payments has its own user_id column, so it gets its own explicit purge call rather than
     *  relying on a cascade off subscriptions -- ordered before that call in the sweep so a payment
     *  row is never left referencing a subscription_id that no longer exists. */
    @Modifying
    @Query(value = "DELETE FROM payments WHERE user_id = :userId", nativeQuery = true)
    void hardDeleteByUserId(@Param("userId") UUID userId);
}
