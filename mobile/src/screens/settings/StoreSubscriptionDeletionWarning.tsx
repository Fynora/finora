import { useState } from 'react';
import { Linking, Platform, Text, View } from 'react-native';
import { useQuery } from '@tanstack/react-query';
import { Button } from '../../components/Button';
import { billingApi } from '../../api/endpoints';
import { useTheme } from '../../theme';
import { sheetStyles } from './AccountActionSheet';

// Same addresses MySubscriptionScreen's "Manage subscription" opens.
const IOS_MANAGE_SUBSCRIPTIONS_URL = 'itms-apps://apps.apple.com/account/subscriptions';
const ANDROID_MANAGE_SUBSCRIPTIONS_URL = 'https://play.google.com/store/account/subscriptions';

/**
 * Shown at the top of DeleteAccountSheet when the account has a Plus subscription bought through
 * the App Store or Google Play that is still set to renew. Deleting the account cannot cancel it:
 * only Apple or Google can, and only when the person asks them. Without this, the store keeps
 * charging after the account is gone (the purge records that case as
 * STORE_SUBSCRIPTION_STILL_RENEWING). Apple also requires an app to say this before deletion.
 *
 * Warns rather than blocks: the person may want their data gone now and cancel afterwards.
 * Reads the same ['my-subscription'] query MySubscriptionScreen does. While it loads nothing is
 * shown; if it fails, a general reminder is shown instead, so a failed read never hides the
 * warning from someone it applies to.
 */
export function StoreSubscriptionDeletionWarning() {
  const c = useTheme();
  const [openError, setOpenError] = useState<string | null>(null);
  const subscriptionQ = useQuery({ queryKey: ['my-subscription'], queryFn: () => billingApi.mySubscription() });

  if (subscriptionQ.isLoading) return null;

  if (!subscriptionQ.data) {
    return (
      <View style={[sheetStyles.notice, { backgroundColor: c.primaryLight }]}>
        <Text style={[sheetStyles.noticeText, { color: c.ink }]}>
          If you subscribed to Plus through the App Store or Google Play, deleting your account
          doesn&apos;t cancel it. Cancel it in the store, or you&apos;ll keep being charged.
        </Text>
      </View>
    );
  }

  const s = subscriptionQ.data;
  if (s.paymentProvider !== 'REVENUECAT' || !s.autoRenew) return null;

  const apple = s.storePlatform === 'IOS';
  const storeName = apple ? 'the App Store' : 'Google Play';
  const company = apple ? 'Apple' : 'Google';
  // A link only works on the store's own platform: an iPhone cannot open Google Play's page.
  const canOpenStore = apple ? Platform.OS === 'ios' : Platform.OS === 'android';

  async function openStore() {
    setOpenError(null);
    try {
      await Linking.openURL(apple ? IOS_MANAGE_SUBSCRIPTIONS_URL : ANDROID_MANAGE_SUBSCRIPTIONS_URL);
    } catch {
      setOpenError('Could not open your subscription settings. Try again.');
    }
  }

  return (
    <View
      style={[sheetStyles.notice, { backgroundColor: c.warningBg }]}
      testID="store-subscription-deletion-warning"
    >
      <Text style={[sheetStyles.noticeText, { color: c.ink, fontWeight: '600' }]}>
        Cancel your Plus subscription first
      </Text>
      <Text style={[sheetStyles.noticeText, { color: c.ink }]}>
        You subscribed through {storeName}. Deleting your account doesn&apos;t cancel it —{' '}
        {company} will keep charging you until you cancel it there.
      </Text>
      {canOpenStore ? (
        <Button
          label={apple ? 'Open App Store subscriptions' : 'Open Google Play subscriptions'}
          variant="link"
          onPress={() => void openStore()}
        />
      ) : null}
      {openError ? <Text style={[sheetStyles.error, { color: c.dangerInk }]}>{openError}</Text> : null}
    </View>
  );
}
