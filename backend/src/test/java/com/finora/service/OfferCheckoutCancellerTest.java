package com.finora.service;

import com.finora.entity.BillingOffer;
import com.finora.entity.SubscriptionOrder;
import com.finora.integrations.razorpay.RazorpaySubscriptionDto;
import com.finora.integrations.razorpay.RazorpaySubscriptionGateway;
import com.finora.integrations.razorpay.RazorpaySubscriptionGateway.AuthorisationPayment;
import com.finora.repository.BillingOfferRepository;
import com.finora.repository.SubscriptionOrderRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.SimpleTransactionStatus;
import org.springframework.transaction.support.TransactionTemplate;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Cancelling the checkouts left open when an offer ends early. The rule that matters most: an
 * order is marked ABANDONED only when Razorpay cancelled it and no payment landed, because the
 * activation webhook ignores an ABANDONED order and a customer who paid would get nothing.
 */
class OfferCheckoutCancellerTest {

    private final BillingOfferRepository offers = mock(BillingOfferRepository.class);
    private final SubscriptionOrderRepository orders = mock(SubscriptionOrderRepository.class);
    private final RazorpaySubscriptionGateway gateway = mock(RazorpaySubscriptionGateway.class);
    private final AuditService audit = mock(AuditService.class);
    private final PlatformTransactionManager txManager = mock(PlatformTransactionManager.class);
    private final OfferCheckoutCanceller canceller =
            new OfferCheckoutCanceller(offers, orders, gateway, new TransactionTemplate(txManager), audit);

    private final UUID admin = UUID.randomUUID();
    private final UUID planId = UUID.randomUUID();
    private BillingOffer offer;

    @BeforeEach
    void setUp() {
        when(txManager.getTransaction(any())).thenReturn(new SimpleTransactionStatus());
        offer = new BillingOffer();
        ReflectionTestUtils.setField(offer, "id", UUID.randomUUID());
        offer.setCode("HOLI2027");
        offer.setPlanId(planId);
        offer.setBillingCycle("MONTHLY");
        offer.setFirstPeriodPrice(new BigDecimal("99.00"));
        offer.setLabel("Holi offer");
        offer.setStartsAt(Instant.parse("2027-03-19T18:30:00Z"));
        offer.setEndsAt(Instant.parse("2027-03-20T10:00:00Z"));
        when(offers.findById(offer.getId())).thenReturn(Optional.of(offer));
        when(gateway.isConfigured()).thenReturn(true);
        when(gateway.findAuthorisationPayment(anyString())).thenReturn(Optional.empty());
    }

    private SubscriptionOrder pending(String subscriptionId) {
        SubscriptionOrder order = new SubscriptionOrder();
        order.setUserId(UUID.randomUUID());
        order.setPlanId(planId);
        order.setBillingCycle("MONTHLY");
        order.setRazorpaySubscriptionId(subscriptionId);
        order.setStatus(SubscriptionOrder.STATUS_PENDING);
        order.setAmount(new BigDecimal("99.00"));
        order.setOfferCode("HOLI2027");
        when(orders.findByRazorpaySubscriptionId(subscriptionId)).thenReturn(Optional.of(order));
        return order;
    }

    private void open(SubscriptionOrder... list) {
        when(orders.findByStatusAndOfferCodeAndPlanIdAndBillingCycle(
                SubscriptionOrder.STATUS_PENDING, "HOLI2027", planId, "MONTHLY")).thenReturn(List.of(list));
    }

    private void onRazorpay(String subscriptionId, String status) {
        when(gateway.fetchSubscription(subscriptionId)).thenReturn(new RazorpaySubscriptionDto(subscriptionId, status));
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> auditMetadata() {
        ArgumentCaptor<Map<String, Object>> metadata = ArgumentCaptor.forClass(Map.class);
        verify(audit).record(eq(admin), eq("BILLING_OFFER_CHECKOUTS_CANCELLED"), eq("BillingOffer"),
                eq(offer.getId()), metadata.capture());
        return metadata.getValue();
    }

    @Test
    void anUnpaidCheckout_isCancelledImmediatelyOnRazorpay_andAbandoned() {
        SubscriptionOrder order = pending("sub_unpaid");
        open(order);
        onRazorpay("sub_unpaid", "created");

        OfferCheckoutCanceller.Result result = canceller.cancelOpenCheckouts(admin, offer.getId());

        assertThat(result).isEqualTo(new OfferCheckoutCanceller.Result(1, 0));
        verify(gateway).cancelSubscription("sub_unpaid", false);
        assertThat(order.getStatus()).isEqualTo(SubscriptionOrder.STATUS_ABANDONED);
        assertThat(auditMetadata()).containsEntry("cancelled", 1).containsEntry("openCheckouts", 1)
                .containsEntry("code", "HOLI2027");
    }

    @Test
    void aPaidCheckout_isLeftAlone_andStaysPendingForTheWebhook() {
        SubscriptionOrder authenticated = pending("sub_paid");
        SubscriptionOrder active = pending("sub_active");
        open(authenticated, active);
        onRazorpay("sub_paid", "authenticated");
        onRazorpay("sub_active", "active");

        OfferCheckoutCanceller.Result result = canceller.cancelOpenCheckouts(admin, offer.getId());

        assertThat(result).isEqualTo(new OfferCheckoutCanceller.Result(0, 0));
        verify(gateway, never()).cancelSubscription(anyString(), anyBoolean());
        assertThat(authenticated.getStatus()).isEqualTo(SubscriptionOrder.STATUS_PENDING);
        assertThat(active.getStatus()).isEqualTo(SubscriptionOrder.STATUS_PENDING);
        assertThat(auditMetadata()).containsEntry("notOpen", 2);
    }

    @Test
    void aPaymentThatLandsDuringTheCancel_keepsTheOrderPending_soTheCustomerStillGetsThePlan() {
        SubscriptionOrder order = pending("sub_race");
        open(order);
        onRazorpay("sub_race", "created");
        when(gateway.findAuthorisationPayment("sub_race")).thenReturn(Optional.of(new AuthorisationPayment("pay_1", 9900)));

        OfferCheckoutCanceller.Result result = canceller.cancelOpenCheckouts(admin, offer.getId());

        assertThat(result).isEqualTo(new OfferCheckoutCanceller.Result(0, 1));
        assertThat(order.getStatus()).isEqualTo(SubscriptionOrder.STATUS_PENDING);
    }

    @Test
    void aRefusedCancel_aFailedFetch_orAFailedPaymentCheck_leaveTheOrderPending_andTheRestStillRun() {
        SubscriptionOrder refused = pending("sub_refused");
        SubscriptionOrder unreachable = pending("sub_unreachable");
        SubscriptionOrder unchecked = pending("sub_unchecked");
        SubscriptionOrder fine = pending("sub_fine");
        open(refused, unreachable, unchecked, fine);
        onRazorpay("sub_refused", "created");
        onRazorpay("sub_unchecked", "created");
        onRazorpay("sub_fine", "created");
        when(gateway.fetchSubscription("sub_unreachable")).thenThrow(new IllegalStateException("timeout"));
        doThrow(new IllegalStateException("not cancellable")).when(gateway).cancelSubscription("sub_refused", false);
        when(gateway.findAuthorisationPayment("sub_unchecked")).thenThrow(new IllegalStateException("timeout"));

        OfferCheckoutCanceller.Result result = canceller.cancelOpenCheckouts(admin, offer.getId());

        assertThat(result).isEqualTo(new OfferCheckoutCanceller.Result(1, 3));
        assertThat(refused.getStatus()).isEqualTo(SubscriptionOrder.STATUS_PENDING);
        assertThat(unreachable.getStatus()).isEqualTo(SubscriptionOrder.STATUS_PENDING);
        assertThat(unchecked.getStatus()).isEqualTo(SubscriptionOrder.STATUS_PENDING);
        assertThat(fine.getStatus()).isEqualTo(SubscriptionOrder.STATUS_ABANDONED);
        assertThat(auditMetadata()).containsEntry("cancelled", 1).containsEntry("notCancelled", 3);
    }

    @Test
    void withoutRazorpayConfigured_nothingIsCalled_andEveryOpenCheckoutIsReportedAsNotCancelled() {
        SubscriptionOrder order = pending("sub_unpaid");
        open(order);
        when(gateway.isConfigured()).thenReturn(false);

        OfferCheckoutCanceller.Result result = canceller.cancelOpenCheckouts(admin, offer.getId());

        assertThat(result).isEqualTo(new OfferCheckoutCanceller.Result(0, 1));
        verify(gateway, never()).fetchSubscription(anyString());
        assertThat(order.getStatus()).isEqualTo(SubscriptionOrder.STATUS_PENDING);
    }

    @Test
    void noOpenCheckouts_callsNothing_andWritesNoAudit() {
        open();
        assertThat(canceller.cancelOpenCheckouts(admin, offer.getId())).isEqualTo(new OfferCheckoutCanceller.Result(0, 0));
        verify(gateway, never()).fetchSubscription(anyString());
        verify(audit, never()).record(any(), anyString(), anyString(), any(), any());
    }
}
