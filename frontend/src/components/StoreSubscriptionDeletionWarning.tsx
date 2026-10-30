import { useQuery } from '@tanstack/react-query';
import { billingApi } from '../api/endpoints';

// Apple's and Google's own pages for managing subscriptions; both open in a browser.
const APPLE_MANAGE_SUBSCRIPTIONS_URL = 'https://apps.apple.com/account/subscriptions';
const GOOGLE_MANAGE_SUBSCRIPTIONS_URL = 'https://play.google.com/store/account/subscriptions';

/**
 * Shown in DeleteAccountModal when the account has a Plus subscription bought through the App
 * Store or Google Play that is still set to renew. Deleting the account cannot cancel it: only
 * Apple or Google can, and only when the person asks them. Without this the store keeps charging
 * after the account is gone (the purge records that case as STORE_SUBSCRIPTION_STILL_RENEWING).
 * A web (Razorpay) subscription needs no warning: the purge cancels it itself.
 *
 * Warns rather than blocks, and reads the same ['my-subscription'] query the Billing page does.
 * Nothing shows while it loads; if it fails, a general reminder is shown instead, so a failed read
 * never hides the warning from someone it applies to. Mobile has its own copy of this component
 * (mobile/src/screens/settings/StoreSubscriptionDeletionWarning.tsx).
 */
export function StoreSubscriptionDeletionWarning() {
  const subscriptionQ = useQuery({ queryKey: ['my-subscription'], queryFn: () => billingApi.mySubscription() });

  if (subscriptionQ.isLoading) return null;

  if (!subscriptionQ.data) {
    return (
      <p className="mb-3 rounded-lg bg-warning-bg p-3 text-xs text-ink">
        If you subscribed to Plus through the App Store or Google Play, deleting your account doesn't
        cancel it. Cancel it in the store, or you'll keep being charged.
      </p>
    );
  }

  const s = subscriptionQ.data;
  if (s.paymentProvider !== 'REVENUECAT' || !s.autoRenew) return null;

  const apple = s.storePlatform === 'IOS';
  return (
    <div className="mb-3 rounded-lg bg-warning-bg p-3 text-xs text-ink space-y-1" data-testid="store-subscription-deletion-warning">
      <p className="font-semibold">Cancel your Plus subscription first</p>
      <p>
        You subscribed through {apple ? 'the App Store' : 'Google Play'}. Deleting your account doesn't
        cancel it — {apple ? 'Apple' : 'Google'} will keep charging you until you cancel it there.
      </p>
      <a
        href={apple ? APPLE_MANAGE_SUBSCRIPTIONS_URL : GOOGLE_MANAGE_SUBSCRIPTIONS_URL}
        target="_blank"
        rel="noopener noreferrer"
        className="text-primary hover:underline"
      >
        {apple ? 'Manage App Store subscriptions' : 'Manage Google Play subscriptions'}
      </a>
    </div>
  );
}
