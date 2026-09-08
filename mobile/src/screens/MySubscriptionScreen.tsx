import { View, Text, StyleSheet, Pressable, Platform, Linking, Alert } from 'react-native';
import { useState } from 'react';
import { useQuery, useQueryClient } from '@tanstack/react-query';
import { billingApi } from '../api/endpoints';
import { restorePurchases } from '../lib/revenueCat';
import { fmtDate } from '../lib/format';
import { toUserMessage } from '../lib/apiError';
import { useSingleFlight } from '../lib/useSingleFlight';
import { useTheme } from '../theme';

const IOS_MANAGE_SUBSCRIPTIONS_URL = 'itms-apps://apps.apple.com/account/subscriptions';
const ANDROID_MANAGE_SUBSCRIPTIONS_URL = 'https://play.google.com/store/account/subscriptions';

/** Mobile equivalent of frontend/src/pages/Billing.tsx, structurally different by design (spec
 *  §2/§8): neither App Store nor Play Store policy allows an in-app cancel button for an IAP
 *  subscription, so this only ever deep-links out to the OS's own subscription management. A
 *  Razorpay-owned subscription is otherwise read-only here for the same reason mobile never
 *  offers the Paywall to one -- design spec §6.3/§6.4's ownership-source rule (§2.1, invariant 2).
 *
 *  <p>Pause/Resume are the one exception (2026-09-08): unlike checkout/cancel, neither creates a
 *  subscription nor moves ownership between providers -- the ownership-source rule doesn't cover
 *  them -- so they're real, working actions here too, calling the same backend endpoints
 *  frontend/src/pages/Billing.tsx does. */
export function MySubscriptionScreen() {
  const c = useTheme();
  const queryClient = useQueryClient();
  const singleFlight = useSingleFlight();
  const [error, setError] = useState<string | null>(null);
  const { data: subscription, isLoading } = useQuery({
    queryKey: ['my-subscription'],
    queryFn: () => billingApi.mySubscription(),
  });

  async function handleManageSubscription() {
    const url = Platform.OS === 'ios' ? IOS_MANAGE_SUBSCRIPTIONS_URL : ANDROID_MANAGE_SUBSCRIPTIONS_URL;
    await Linking.openURL(url);
  }

  async function handleRestore() {
    await restorePurchases();
    await queryClient.invalidateQueries({ queryKey: ['my-subscription'] });
    await queryClient.invalidateQueries({ queryKey: ['entitlements'] });
  }

  async function pause() {
    await singleFlight(async () => {
      setError(null);
      try {
        await billingApi.pause();
        await queryClient.invalidateQueries({ queryKey: ['my-subscription'] });
        await queryClient.invalidateQueries({ queryKey: ['entitlements'] });
      } catch (e) {
        setError(toUserMessage(e, 'Could not pause this subscription. Try again.'));
      }
    });
  }

  function confirmPause() {
    // Alert.alert replaces the web's ConfirmDialog, same substitution as every other confirm on
    // these screens (GoalsScreen.confirmDelete, MoreScreen.confirmSignOut).
    Alert.alert(
      'Pause subscription?',
      'Billing stops right away and Premium features turn off until you resume. Your plan and ' +
        'payment setup stay put, so resuming needs no new checkout.',
      [
        { text: 'Cancel', style: 'cancel' },
        { text: 'Pause', style: 'default', onPress: () => void pause() },
      ]
    );
  }

  async function handleResume() {
    await singleFlight(async () => {
      setError(null);
      try {
        await billingApi.resume();
        await queryClient.invalidateQueries({ queryKey: ['my-subscription'] });
        await queryClient.invalidateQueries({ queryKey: ['entitlements'] });
      } catch (e) {
        setError(toUserMessage(e, 'Could not resume this subscription. Try again.'));
      }
    });
  }

  if (isLoading || !subscription) return null;

  const canPause = subscription.hasBillingSubscription && subscription.paymentProvider === 'RAZORPAY' &&
    subscription.status === 'ACTIVE' && subscription.autoRenew;
  const canResume = subscription.paymentProvider === 'RAZORPAY' && subscription.status === 'PAUSED';

  return (
    <View style={[styles.container, { backgroundColor: c.bg }]}>
      <Text style={[styles.planName, { color: c.ink }]}>{subscription.planName ?? subscription.planCode}</Text>

      {error ? <Text style={[styles.note, { color: c.danger }]}>{error}</Text> : null}

      {subscription.status === 'PAUSED' ? (
        // Razorpay's charge_at goes null while paused, so renewalDate is stale until resume --
        // same fix as frontend/src/pages/Billing.tsx's own paused-state message.
        <Text style={[styles.note, { color: c.warning }]}>
          Paused — billing on hold. Resume anytime to pick up where you left off.
        </Text>
      ) : subscription.renewalDate && (
        <Text style={[styles.note, { color: c.muted }]}>
          {subscription.hasBillingSubscription && !subscription.autoRenew
            ? `Ends ${fmtDate(subscription.renewalDate)} — won't renew`
            : `Renews ${fmtDate(subscription.renewalDate)}`}
        </Text>
      )}

      {subscription.hasBillingSubscription && subscription.paymentProvider === 'RAZORPAY' && !canPause && !canResume && (
        <Text style={[styles.note, { color: c.muted }]}>
          This subscription is managed on web. Open the Billing page in a browser to make changes.
        </Text>
      )}

      {canResume && (
        <Pressable accessibilityRole="button" onPress={() => void handleResume()} style={[styles.button, { borderColor: c.border }]}>
          <Text style={{ color: c.ink }}>Resume subscription</Text>
        </Pressable>
      )}

      {canPause && (
        <Pressable accessibilityRole="button" onPress={confirmPause} style={[styles.button, { borderColor: c.border }]}>
          <Text style={{ color: c.ink }}>Pause subscription</Text>
        </Pressable>
      )}

      {subscription.hasBillingSubscription && subscription.paymentProvider === 'REVENUECAT' && (
        <Pressable accessibilityRole="button" onPress={handleManageSubscription} style={[styles.button, { borderColor: c.border }]}>
          <Text style={{ color: c.ink }}>Manage subscription</Text>
        </Pressable>
      )}

      {/* Not shown for a Razorpay-owned subscription -- there is nothing an App Store/Play Store
          restore could do for a web-purchased plan, and showing it would just invite a confusing
          no-op tap. Shown both for FREE (no billing subscription -- a lapsed or not-yet-synced IAP
          purchase) and REVENUECAT-owned. */}
      {subscription.paymentProvider !== 'RAZORPAY' && (
        <Pressable accessibilityRole="button" onPress={handleRestore} style={[styles.button, { borderColor: c.border }]}>
          <Text style={{ color: c.ink }}>Restore Purchases</Text>
        </Pressable>
      )}
    </View>
  );
}

const styles = StyleSheet.create({
  container: { flex: 1, padding: 16, gap: 12 },
  planName: { fontSize: 20, fontWeight: '700' },
  note: { fontSize: 13 },
  button: { borderWidth: 1, borderRadius: 12, paddingVertical: 12, alignItems: 'center', marginTop: 8 },
});
