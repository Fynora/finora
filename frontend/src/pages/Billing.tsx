import { useEffect, useRef, useState, type ReactNode } from 'react';
import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query';
import { Link } from 'react-router-dom';
import { motion, useReducedMotion } from 'framer-motion';
import {
  Receipt, CreditCard, Crown, ShieldCheck, Sparkles, Gift, Target, PiggyBank, UploadCloud,
  Wallet, ArrowLeftRight, Check, PauseCircle, PlayCircle, Users, Eye, Download, type LucideIcon,
} from 'lucide-react';
import {
  billingApi, entitlementsApi, referralsApi, accountsApi, goalsApi, budgetsApi, analyticsApi, userApi, usageApi,
} from '../api/endpoints';
import { openRazorpayCheckout } from '../lib/razorpayCheckout';
import { downloadBlob } from '../lib/download';
import { formatDate } from '../utils/date';
import { FinoraCard, EmptyState, Button, ConfirmDialog, Skeleton } from '../design-system';
import { PLANS } from './landing/plans';
import { SettingsTabs } from './SettingsTabs';

function fmt(amount: number, currency: string) {
  const symbol = currency === 'INR' ? '₹' : currency + ' ';
  return symbol + Math.round(amount).toLocaleString('en-IN');
}

function statusLabel(status: string) {
  switch (status) {
    case 'SUCCESS': return { text: 'Paid', className: 'text-success bg-success-bg' };
    case 'REFUNDED': return { text: 'Refunded', className: 'text-muted bg-bg' };
    case 'FAILED': return { text: 'Failed', className: 'text-danger bg-danger-bg' };
    default: return { text: 'Pending', className: 'text-warning bg-warning-bg' };
  }
}

const CHECKOUT_CYCLES = [
  { code: 'MONTHLY', label: 'Monthly' },
  { code: 'YEARLY', label: 'Yearly' },
] as const;
const TIER_RANK: Record<string, number> = { FREE: 0, PLUS: 1, PREMIUM: 2 };

// FeatureEntitlement's real key set (backend entity, V99/V161/V163 seeds) -- every key
// `entitlementsApi.mine()` can return, so "X / Y unlocked" below is a real count over a real
// total, not a made-up denominator.
const FEATURE_LABELS: Record<string, string> = {
  BASIC_DASHBOARD: 'Financial Dashboard',
  ADVANCED_REPORTS: 'Advanced Reports',
  EXTENDED_HISTORY: 'Extended History',
  UNLIMITED_ACCOUNTS: 'Unlimited Accounts',
  GMAIL_SYNC: 'Gmail Sync',
  INVESTMENT_INSIGHTS: 'Investment Insights',
  FINO_AI: 'Fino AI Assistant',
  PRIORITY_SUPPORT: 'Priority Support',
};

/** Polls `mySubscription` after a successful checkout until the plan actually flips, or 30
 *  seconds pass -- activation only ever comes from the backend's verified webhook, never from
 *  Checkout's own success callback. */
function useActivationPoll(expectedPlanCode: string | null, onSettled: () => void) {
  const onSettledRef = useRef(onSettled);
  onSettledRef.current = onSettled;

  useEffect(() => {
    if (!expectedPlanCode) return;
    const deadline = Date.now() + 30_000;
    const interval = setInterval(() => {
      void (async () => {
        const current = await billingApi.mySubscription();
        if (current.planCode === expectedPlanCode || Date.now() > deadline) {
          clearInterval(interval);
          onSettledRef.current();
        }
      })();
    }, 2000);
    return () => clearInterval(interval);
  }, [expectedPlanCode]);
}

function KpiEntrance({ index, reduceMotion, children }: { index: number; reduceMotion: boolean | null; children: ReactNode }) {
  if (reduceMotion) return <div className="h-full">{children}</div>;
  return (
    <motion.div
      className="h-full"
      initial={{ opacity: 0, y: 12 }}
      animate={{ opacity: 1, y: 0 }}
      transition={{ duration: 0.35, delay: index * 0.06, ease: [0.16, 1, 0.3, 1] }}
    >
      {children}
    </motion.div>
  );
}

function KpiCard({
  label, value, icon: Icon, iconBg, iconColor, footer, valueTestId,
}: {
  label: string; value: string; icon: LucideIcon; iconBg: string; iconColor: string; footer: ReactNode; valueTestId?: string;
}) {
  return (
    <FinoraCard className="h-full flex flex-col justify-between transition-[transform,box-shadow] duration-150 hover:-translate-y-0.5 hover:shadow-soft">
      <div>
        <div className="flex items-start justify-between mb-4">
          <p className="text-sm font-semibold text-muted">{label}</p>
          <div className={`w-10 h-10 rounded-xl ${iconBg} flex items-center justify-center flex-shrink-0`}>
            <Icon size={18} className={iconColor} />
          </div>
        </div>
        <p data-testid={valueTestId} className="font-display text-[22px] font-extrabold tracking-tight text-ink truncate">{value}</p>
      </div>
      {footer}
    </FinoraCard>
  );
}

/** Decorative, aria-hidden -- no billing PNG hero asset exists yet (unlike Transactions/Import/
 *  Statement History), so this composes the same "premium membership" motifs (crown, shield,
 *  stacked card) from existing tokens/icons rather than a bespoke illustration file. */
function MembershipIllustration() {
  return (
    <div className="relative w-40 h-28 flex-shrink-0" aria-hidden="true">
      <div className="absolute inset-0 translate-x-3 translate-y-3 rounded-2xl bg-primary/10 border border-primary/20" />
      <div className="absolute inset-0 rounded-2xl bg-sidebar shadow-soft flex flex-col justify-between p-4 overflow-hidden">
        <div className="flex items-center justify-between">
          <Crown size={20} className="text-warning" />
          <ShieldCheck size={15} className="text-white/40" />
        </div>
        <div>
          <div className="h-1.5 w-16 bg-white/25 rounded-full mb-1.5" />
          <div className="h-1.5 w-10 bg-white/15 rounded-full" />
        </div>
      </div>
      <div className="absolute -bottom-2 -right-2 w-9 h-9 rounded-full bg-primary flex items-center justify-center shadow-card">
        <Sparkles size={15} className="text-on-primary" />
      </div>
    </div>
  );
}

function Hero() {
  return (
    <div className="relative overflow-hidden bg-card rounded-xl2 border border-border shadow-card px-6 py-7 lg:pr-4 flex items-center justify-between gap-6 flex-wrap">
      <div className="max-w-xl relative z-10">
        <p className="text-[11px] font-semibold uppercase tracking-widest text-muted mb-1">Billing &amp; Membership</p>
        <h1 className="text-2xl md:text-3xl font-bold text-ink font-display">
          Your financial future <span className="text-primary">is worth investing in</span>
        </h1>
        <p className="text-sm text-muted mt-1 max-w-md">
          Manage your subscription, rewards, premium benefits, and billing preferences in one place.
        </p>
      </div>
      <div className="hidden lg:flex items-center gap-5 flex-shrink-0">
        <div className="relative">
          <div className="hidden xl:block absolute -top-9 -left-24 w-40 -rotate-3" aria-hidden="true">
            <p className="font-handwriting text-xl leading-none text-primary">Invest in your financial future</p>
            <svg width="46" height="30" viewBox="0 0 46 30" fill="none" className="text-primary ml-8 mt-1">
              <path d="M2 3C16 2 32 9 41 22" stroke="currentColor" strokeWidth="1.5" strokeLinecap="round" />
              <path d="M32 19L42 24L42 13" stroke="currentColor" strokeWidth="1.5" strokeLinecap="round" strokeLinejoin="round" />
            </svg>
          </div>
          <MembershipIllustration />
        </div>
        <div className="hidden lg:flex items-start gap-2.5 bg-card border border-border rounded-xl2 shadow-card px-4 py-3.5 max-w-[200px]">
          <ShieldCheck size={16} className="text-primary flex-shrink-0 mt-0.5" />
          <div>
            <p className="text-sm text-ink font-medium leading-snug">"Every rupee, working harder for you."</p>
            <p className="text-xs text-muted mt-1">— Fynora</p>
          </div>
        </div>
      </div>
    </div>
  );
}

export default function Billing() {
  const queryClient = useQueryClient();
  const prefersReducedMotion = useReducedMotion();
  const [error, setError] = useState<string | null>(null);
  const [confirmingCancel, setConfirmingCancel] = useState(false);
  const [confirmingPause, setConfirmingPause] = useState(false);
  const [confirmingCancelPendingOrder, setConfirmingCancelPendingOrder] = useState(false);
  const [targetCycle, setTargetCycle] = useState<'MONTHLY' | 'YEARLY'>('MONTHLY');
  const [activatingPlanCode, setActivatingPlanCode] = useState<string | null>(null);
  // Which payment rows' View/Download are in flight -- a Set, not a single id, so fetching one
  // row's invoice doesn't block a click on a different row (bug found on review: an earlier
  // single-id version disabled only the busy row's own buttons but still no-op'd a click on any
  // OTHER row via the same "one thing at a time" guard, silently swallowing the click).
  const [invoiceBusyIds, setInvoiceBusyIds] = useState<Set<string>>(new Set());
  const [isSubmitting, setIsSubmitting] = useState(false);

  const { data: subscription, isLoading: subLoading } = useQuery({
    queryKey: ['my-subscription'],
    queryFn: () => billingApi.mySubscription(),
  });
  const { data: entries, isLoading: historyLoading } = useQuery({
    queryKey: ['billing-history'],
    queryFn: () => billingApi.history(),
  });
  const { data: entitlements } = useQuery({
    queryKey: ['entitlements'],
    queryFn: () => entitlementsApi.mine(),
  });
  const { data: referrals } = useQuery({
    queryKey: ['referrals-mine'],
    queryFn: () => referralsApi.mine(),
  });
  const { data: accounts } = useQuery({ queryKey: ['accounts'], queryFn: () => accountsApi.list() });
  const { data: goals } = useQuery({ queryKey: ['goals'], queryFn: () => goalsApi.list() });
  const { data: budgets } = useQuery({ queryKey: ['budgets'], queryFn: () => budgetsApi.list() });
  const { data: importStats } = useQuery({
    queryKey: ['import-statistics'],
    queryFn: () => analyticsApi.importStatistics(),
  });
  const { data: insightsUsage } = useQuery({
    queryKey: ['insights-view-count'],
    queryFn: () => usageApi.viewCount('insights'),
  });
  // Bug found in review (pre-redesign): openRazorpayCheckout was never given a `prefill`, so
  // Razorpay's widget always asked for contact details fresh even though Fynora already has the
  // user's verified email and phone. 'user-settings' matches the queryKey Dashboard.tsx already
  // uses for the same GET /users/me call.
  const { data: userSettings } = useQuery({
    queryKey: ['user-settings'],
    queryFn: () => userApi.get(),
  });
  const checkoutPrefill = userSettings
    ? {
        email: userSettings.email,
        name: userSettings.fullName,
        // Google sign-in accounts can have no phone number at all -- omit rather than send "null".
        ...(userSettings.phoneNumber ? { contact: userSettings.phoneNumber } : {}),
      }
    : undefined;

  useActivationPoll(activatingPlanCode, () => {
    setActivatingPlanCode(null);
    void queryClient.invalidateQueries({ queryKey: ['my-subscription'] });
    void queryClient.invalidateQueries({ queryKey: ['entitlements'] });
  });

  const cancelMutation = useMutation({
    mutationFn: () => billingApi.cancel(),
    onSuccess: () => {
      setConfirmingCancel(false);
      void queryClient.invalidateQueries({ queryKey: ['my-subscription'] });
    },
    onError: (e: any) => {
      setConfirmingCancel(false);
      setError(e.response?.data?.message ?? 'Could not cancel this subscription. Try again.');
    },
  });

  const pauseMutation = useMutation({
    mutationFn: () => billingApi.pause(),
    onSuccess: () => {
      setConfirmingPause(false);
      void queryClient.invalidateQueries({ queryKey: ['my-subscription'] });
      void queryClient.invalidateQueries({ queryKey: ['entitlements'] });
    },
    onError: (e: any) => {
      setConfirmingPause(false);
      setError(e.response?.data?.message ?? 'Could not pause this subscription. Try again.');
    },
  });

  const resumeMutation = useMutation({
    mutationFn: () => billingApi.resume(),
    onSuccess: () => {
      void queryClient.invalidateQueries({ queryKey: ['my-subscription'] });
      void queryClient.invalidateQueries({ queryKey: ['entitlements'] });
    },
    onError: (e: any) => setError(e.response?.data?.message ?? 'Could not resume this subscription. Try again.'),
  });

  // design spec at docs/superpowers/specs/2026-09-08-billing-auto-renew-resume-design.md.
  // Deliberately a separate mutation/name from resumeMutation above -- that one un-pauses a
  // PAUSED subscription (a real, separate Razorpay feature); this one undoes a pending,
  // not-yet-dispatched cancellation. Never touches entitlements: access is untouched either way
  // until the subscription actually reaches its period end.
  const undoCancellationMutation = useMutation({
    mutationFn: () => billingApi.undoCancellation(),
    onSuccess: () => {
      void queryClient.invalidateQueries({ queryKey: ['my-subscription'] });
    },
    onError: (e: any) => {
      setError(e.response?.data?.message ?? 'Could not resume auto-renewal. Try again.');
    },
  });

  const cancelPendingOrderMutation = useMutation({
    mutationFn: () => billingApi.cancelPendingOrder(),
    onSuccess: () => {
      setConfirmingCancelPendingOrder(false);
      void queryClient.invalidateQueries({ queryKey: ['my-subscription'] });
    },
    onError: (e: any) => {
      setConfirmingCancelPendingOrder(false);
      setError(e.response?.data?.message ?? 'Could not cancel this pending checkout. Try again.');
    },
  });

  async function resumePendingOrder() {
    if (!subscription?.pendingOrder || isSubmitting) return;
    setError(null);
    setIsSubmitting(true);
    try {
      const result = await openRazorpayCheckout({
        key: subscription.pendingOrder.keyId,
        subscription_id: subscription.pendingOrder.razorpaySubscriptionId,
        name: 'Fynora',
        description: `${subscription.pendingOrder.planCode} — ${subscription.pendingOrder.billingCycle}`,
        prefill: checkoutPrefill,
      });
      if (result) setActivatingPlanCode(subscription.pendingOrder.planCode);
    } catch (e: any) {
      setError(e.response?.data?.message ?? 'Could not resume this checkout. Try again.');
    } finally {
      setIsSubmitting(false);
    }
  }

  // Razorpay's documented way to update the card on an already-active subscription: reopen
  // Standard Checkout with the SAME subscription_id (identical mechanism to resumePendingOrder
  // above) rather than any separate "save card" API. The resulting webhook (subscription.activated
  // or subscription.charged, whichever fires next) is what actually persists the new card --
  // invalidating my-subscription just gives that a chance to show up once it lands.
  //
  // Shares isSubmitting with subscribeToPlan/resumePendingOrder rather than its own flag --
  // that state's own comment says it guards those two against opening two Razorpay widgets at
  // once, and this is a third flow that opens the same widget, so it joins the same guard rather
  // than racing it with an independent one.
  async function updatePaymentMethod() {
    if (!subscription?.paymentMethod || isSubmitting) return;
    setError(null);
    setIsSubmitting(true);
    try {
      const result = await openRazorpayCheckout({
        key: subscription.paymentMethod.keyId,
        subscription_id: subscription.paymentMethod.razorpaySubscriptionId,
        name: 'Fynora',
        description: 'Update payment method',
        prefill: checkoutPrefill,
      });
      // Matches subscribeToPlan/resumePendingOrder's own convention -- openRazorpayCheckout
      // resolves `null` on a dismiss or a failed authentication, in which case nothing changed
      // server-side and refetching would just be a wasted round-trip.
      if (result) void queryClient.invalidateQueries({ queryKey: ['my-subscription'] });
    } catch (e: any) {
      setError(e.response?.data?.message ?? 'Could not update your payment method. Try again.');
    } finally {
      setIsSubmitting(false);
    }
  }

  // Takes planCode/cycle as explicit arguments (rather than reading targetPlan/targetCycle state)
  // so each plan card's own button can call this directly without a set-state-then-read-stale-
  // state race.
  async function subscribeToPlan(planCode: string, cycle: string) {
    // Same billingCycle-can-be-null-while-already-on-this-plan reasoning as the plan grid's own
    // isCurrent check below -- an admin-granted plan has no billingCycle to match, so this must not
    // require an exact cycle match to recognize "already on this plan" and bail.
    const alreadyOnThisPlan = planCode === subscription?.planCode
      && (subscription?.billingCycle == null || cycle === subscription.billingCycle);
    if (isSubmitting || alreadyOnThisPlan) return;
    setError(null);
    setIsSubmitting(true);
    try {
      if (!subscription?.hasBillingSubscription) {
        const checkout = await billingApi.checkout(planCode, cycle);
        const result = await openRazorpayCheckout({
          key: checkout.keyId,
          subscription_id: checkout.razorpaySubscriptionId,
          name: 'Fynora',
          description: `${planCode} — ${cycle}`,
          prefill: checkoutPrefill,
        });
        if (result) setActivatingPlanCode(planCode);
        return;
      }
      const isUpgrade = TIER_RANK[planCode] > TIER_RANK[subscription.planCode];
      const checkout = await billingApi.changePlan(planCode, cycle);
      if (isUpgrade && checkout) {
        const result = await openRazorpayCheckout({
          key: checkout.keyId,
          subscription_id: checkout.razorpaySubscriptionId,
          name: 'Fynora',
          description: `${planCode} — ${cycle}`,
          prefill: checkoutPrefill,
        });
        if (result) setActivatingPlanCode(planCode);
      } else {
        void queryClient.invalidateQueries({ queryKey: ['my-subscription'] });
      }
    } catch (e: any) {
      if (e.response?.status === 409) {
        setError('You already have a checkout in progress for a different plan. Use Cancel below to start a new one.');
        void queryClient.invalidateQueries({ queryKey: ['my-subscription'] });
      } else {
        setError(e.response?.data?.message ?? 'Could not change your plan. Try again.');
      }
    } finally {
      setIsSubmitting(false);
    }
  }

  // View opens the PDF in a new tab (a blob: URL, so the browser's own viewer renders it inline
  // regardless of the response's Content-Disposition: attachment header -- that header only
  // governs a direct HTTP navigation, not a client-fetched blob). Download saves it via the same
  // shared helper every other file download in this app uses.
  async function viewInvoice(paymentId: string) {
    if (invoiceBusyIds.has(paymentId)) return;
    setError(null);
    setInvoiceBusyIds((prev) => new Set(prev).add(paymentId));
    try {
      const blob = await billingApi.invoicePdf(paymentId);
      const url = URL.createObjectURL(blob);
      window.open(url, '_blank');
      setTimeout(() => URL.revokeObjectURL(url), 10_000);
    } catch (e: any) {
      setError(e.response?.data?.message ?? 'Could not open this invoice. Try again.');
    } finally {
      setInvoiceBusyIds((prev) => {
        const next = new Set(prev);
        next.delete(paymentId);
        return next;
      });
    }
  }

  async function downloadInvoice(paymentId: string) {
    if (invoiceBusyIds.has(paymentId)) return;
    setError(null);
    setInvoiceBusyIds((prev) => new Set(prev).add(paymentId));
    try {
      const blob = await billingApi.invoicePdf(paymentId);
      downloadBlob(blob, `fynora-invoice-${paymentId.slice(0, 8)}.pdf`);
    } catch (e: any) {
      setError(e.response?.data?.message ?? 'Could not download this invoice. Try again.');
    } finally {
      setInvoiceBusyIds((prev) => {
        const next = new Set(prev);
        next.delete(paymentId);
        return next;
      });
    }
  }

  if (subLoading || historyLoading || !subscription) {
    return (
      <div className="space-y-6">
        <SettingsTabs active="billing" />
        <Skeleton.Region label="Loading your billing and membership details" className="space-y-6">
          <Skeleton.Block className="h-40 w-full" />
          <div className="grid md:grid-cols-2 xl:grid-cols-4 gap-4">
            {[0, 1, 2, 3].map((i) => <Skeleton.Card key={i} />)}
          </div>
          <Skeleton.Block className="h-64 w-full" />
        </Skeleton.Region>
      </div>
    );
  }

  const payments = entries ?? [];
  const isFree = subscription.planCode === 'FREE';
  const planMeta = PLANS.find((p) => p.id.toUpperCase() === subscription.planCode);
  // Design spec §2's "Option 2" (disabled controls, not hidden) -- a subscription bought through
  // the App Store/Play Store is managed by RevenueCat, not Razorpay. Neither store allows an
  // app-side cancel/change-plan button for an IAP subscription, so every billing action below
  // gates on this the same way the pre-redesign Billing.tsx already did.
  const isRevenueCat = subscription.paymentProvider === 'REVENUECAT';

  const featureKeys = Object.keys(entitlements?.features ?? {});
  const unlockedCount = featureKeys.filter((k) => entitlements!.features[k]).length;
  const totalFeatures = featureKeys.length;
  const utilizationPct = totalFeatures > 0 ? Math.round((unlockedCount / totalFeatures) * 100) : 0;

  const successfulPayments = payments.filter((p) => p.status === 'SUCCESS');
  const firstPaymentDate = successfulPayments.length > 0
    ? successfulPayments.reduce((earliest, p) => (p.createdAt < earliest ? p.createdAt : earliest), successfulPayments[0].createdAt)
    : null;

  return (
    <div className="space-y-6">
      <SettingsTabs active="billing" />
      <Hero />

      {error && (
        <div className="text-sm text-danger bg-danger-bg rounded-lg px-4 py-2.5">{error}</div>
      )}
      {activatingPlanCode && (
        <div className="text-sm text-ink bg-bg border border-border rounded-lg px-4 py-2.5">
          Activating your {activatingPlanCode} plan… this can take a few seconds.
        </div>
      )}

      <div className="grid md:grid-cols-2 xl:grid-cols-4 gap-4">
        <KpiEntrance index={0} reduceMotion={prefersReducedMotion}>
          <KpiCard
            label="Current Plan"
            value={subscription.planName}
            valueTestId="current-plan-name"
            icon={Crown}
            iconBg="bg-primary-light"
            iconColor="text-primary"
            footer={
              <div className="flex items-center justify-between mt-3 pt-3 border-t border-border">
                {/* PLANS' Free entry has a real (truthy) price of '₹0', not an empty price -- checked
                    against `isFree` directly rather than price-string truthiness so this fallback is
                    actually reachable. */}
                {/* A paid-tier plan with hasBillingSubscription false is an admin-granted
                    (complimentary) plan, not a real ₹X/month charge -- same gap as the Payment
                    method rows below, caught on a second review pass. */}
                <span className="text-xs text-muted">
                  {isFree
                    ? 'Free forever'
                    : !subscription.hasBillingSubscription
                      ? 'Complimentary'
                      : planMeta?.price
                        ? `${planMeta.price}${planMeta.cadence ?? ''}`
                        : ''}
                </span>
                {/* Bug found in review: this badge ignored PAUSED entirely, so the top-of-page KPI
                    card kept claiming "Active" (green) while the membership card just below it
                    correctly said "Paused" -- two elements on the same page disagreeing about the
                    same subscription's status. */}
                <span className={`text-[10px] uppercase font-semibold rounded px-1.5 py-0.5 ${
                  isFree ? 'text-muted bg-bg' : subscription.status === 'PAUSED' ? 'text-warning bg-warning-bg' : 'text-success bg-success-bg'
                }`}>
                  {isFree ? 'Free' : subscription.status === 'PAUSED' ? 'Paused' : 'Active'}
                </span>
              </div>
            }
          />
        </KpiEntrance>
        <KpiEntrance index={1} reduceMotion={prefersReducedMotion}>
          <KpiCard
            label="Next Renewal"
            // Razorpay's charge_at goes null while paused -- renewalDate is stale until resume, so
            // this KPI must not present it as a real upcoming date.
            value={subscription.status === 'PAUSED' ? 'Paused' : subscription.renewalDate ? formatDate(subscription.renewalDate) : '—'}
            icon={Receipt}
            iconBg="bg-blue-100"
            iconColor="text-blue-600"
            footer={
              <p className="text-xs text-muted mt-3 pt-3 border-t border-border">
                {subscription.status === 'PAUSED'
                  ? 'Billing on hold'
                  : !subscription.hasBillingSubscription
                    ? 'No active subscription'
                    : subscription.autoRenew
                      ? '● Auto-renew enabled'
                      : "Won't renew — ends on this date"}
              </p>
            }
          />
        </KpiEntrance>
        <KpiEntrance index={2} reduceMotion={prefersReducedMotion}>
          <KpiCard
            label="Referral Rewards"
            // No reward-amount ledger exists on the backend yet (referralsApi.mine() returns only
            // a code + a count) -- this ₹ figure is a static illustrative placeholder matching the
            // requested design, not a computed value. See the PR description's gap list.
            value="₹1,250 earned"
            icon={Gift}
            iconBg="bg-purple-100"
            iconColor="text-purple-600"
            footer={
              <p className="text-xs text-muted mt-3 pt-3 border-t border-border">
                {referrals?.referralCount ?? 0} successful referral{referrals?.referralCount === 1 ? '' : 's'}
              </p>
            }
          />
        </KpiEntrance>
        <KpiEntrance index={3} reduceMotion={prefersReducedMotion}>
          <KpiCard
            label="Premium Features"
            value={`${unlockedCount} / ${totalFeatures || '—'}`}
            icon={Sparkles}
            iconBg="bg-green-100"
            iconColor="text-green-600"
            footer={
              <div className="mt-3 pt-3 border-t border-border">
                <div className="h-1.5 bg-bg rounded-full overflow-hidden mb-1.5">
                  <div className="h-full bg-primary rounded-full" style={{ width: `${utilizationPct}%` }} />
                </div>
                <p className="text-xs text-muted">{utilizationPct}% unlocked</p>
              </div>
            }
          />
        </KpiEntrance>
      </div>

      {subscription.pendingOrder && (
        <FinoraCard padding="lg">
          <div className="flex items-center justify-between gap-4 flex-wrap">
            <div>
              <p className="text-sm font-semibold text-ink">
                You started upgrading to {subscription.pendingOrder.planName} but didn't finish payment.
              </p>
              <p className="text-xs text-muted mt-0.5">{subscription.pendingOrder.billingCycle} billing</p>
            </div>
            <div className="flex gap-2">
              <Button size="sm" hoverScale onClick={resumePendingOrder} disabled={isSubmitting || !!activatingPlanCode}>
                Resume checkout
              </Button>
              <Button
                variant="secondary" size="sm" disabled={isSubmitting || !!activatingPlanCode}
                onClick={() => setConfirmingCancelPendingOrder(true)}
              >
                Cancel
              </Button>
            </div>
          </div>
        </FinoraCard>
      )}

      {isFree ? (
        <FinoraCard padding="lg" className="bg-sidebar border-none">
          <div className="flex items-center justify-between gap-6 flex-wrap">
            <div className="max-w-lg">
              <div className="flex items-center gap-2 mb-2">
                <Crown size={18} className="text-warning" />
                <span className="text-xs uppercase tracking-wide font-semibold text-white/60">Free Plan</span>
              </div>
              <h2 className="text-xl font-bold text-white">Unlock the full power of Fynora</h2>
              <p className="text-sm text-white/60 mt-1.5">
                Unlimited accounts, advanced analytics, extended history, and priority support — see
                exactly what each plan adds below.
              </p>
            </div>
            <div className="flex gap-2.5 flex-shrink-0">
              <Button hoverScale onClick={() => void subscribeToPlan('PLUS', targetCycle)} disabled={isSubmitting || !!activatingPlanCode}>
                Upgrade to Plus
              </Button>
              <Button
                variant="secondary" hoverScale
                className="!border-white/20 !text-white hover:!bg-white/10"
                onClick={() => void subscribeToPlan('PREMIUM', targetCycle)}
                disabled={isSubmitting || !!activatingPlanCode}
              >
                Upgrade to Premium
              </Button>
            </div>
          </div>
        </FinoraCard>
      ) : (
        <FinoraCard padding="lg">
          <div className="grid lg:grid-cols-2 gap-6">
            <div>
              <div className="flex items-center gap-2.5 mb-4">
                <div className="w-10 h-10 rounded-full bg-primary-light flex items-center justify-center">
                  <Crown size={18} className="text-primary" />
                </div>
                <div>
                  <p className="font-semibold text-ink">{subscription.planName} Membership</p>
                  <span className={`text-[10px] uppercase font-semibold rounded px-1.5 py-0.5 ${subscription.status === 'PAUSED' ? 'text-warning bg-warning-bg' : 'text-success bg-success-bg'}`}>
                    {subscription.status === 'PAUSED' ? 'Paused' : 'Active'}
                  </span>
                </div>
              </div>
              <dl className="space-y-2.5 text-sm">
                {firstPaymentDate && (
                  <div className="flex justify-between"><dt className="text-muted">Member since</dt><dd className="text-ink font-medium">{formatDate(firstPaymentDate)}</dd></div>
                )}
                {subscription.status === 'PAUSED' ? (
                  // Razorpay's charge_at goes null while paused, so renewalDate is stale until
                  // resume -- show that plainly instead of a "Renews <date>" line that's no longer
                  // true.
                  <div className="flex justify-between">
                    <dt className="text-muted">Billing</dt>
                    <dd className="text-warning font-medium">Paused — on hold</dd>
                  </div>
                ) : subscription.renewalDate && (
                  // Cancelling only flips autoRenew -- status/renewalDate stay untouched until the
                  // real subscription.cancelled webhook lands (design spec §6.3, "access continues
                  // untouched"). Without reading autoRenew here, an already-cancelled subscription
                  // shows the exact same "renews" copy as one that isn't, with no sign it took effect.
                  <div className="flex justify-between">
                    <dt className="text-muted">Billing</dt>
                    <dd className="text-ink font-medium">
                      {subscription.hasBillingSubscription && !subscription.autoRenew
                        ? <>Ends {formatDate(subscription.renewalDate)} — won't renew</>
                        : <>Renews {formatDate(subscription.renewalDate)}</>}
                    </dd>
                  </div>
                )}
                <div className="flex justify-between">
                  <dt className="text-muted">Payment method</dt>
                  <dd className="text-ink font-medium">
                    {/* hasBillingSubscription is false for an admin-granted complimentary plan
                        (SubscriptionService.changePlan's ADMIN_GRANT path never sets paymentProvider
                        or a Razorpay mandate) -- without this branch a comped user was told their
                        payment method is Razorpay Checkout, which they never went through. */}
                    {isRevenueCat ? 'App Store / Play Store' : subscription.hasBillingSubscription ? 'Razorpay Checkout' : 'Complimentary (no charge)'}
                  </dd>
                </div>
                <div className="flex justify-between"><dt className="text-muted">Auto renew</dt><dd className={`font-medium ${subscription.autoRenew ? 'text-success' : 'text-muted'}`}>{subscription.autoRenew ? 'Enabled' : 'Disabled'}</dd></div>
                {subscription.pendingChange && (
                  <div className="flex justify-between"><dt className="text-muted">Scheduled change</dt><dd className="text-warning font-medium">to {subscription.pendingChange.toPlanName} on {formatDate(subscription.pendingChange.effectiveAt)}</dd></div>
                )}
              </dl>
              {subscription.hasBillingSubscription && subscription.status === 'ACTIVE' && subscription.autoRenew && !isRevenueCat && (
                <Button variant="danger" size="sm" className="mt-4" onClick={() => setConfirmingCancel(true)}>
                  Cancel subscription
                </Button>
              )}
              {subscription.hasBillingSubscription && isRevenueCat && (
                <div className="text-sm text-ink bg-bg border border-border rounded-lg px-4 py-2.5 mt-4">
                  This subscription is managed through the App Store/Play Store — changes and
                  cancellation happen there, not here.
                </div>
              )}
            </div>
            <div className="border-t lg:border-t-0 lg:border-l border-border pt-6 lg:pt-0 lg:pl-6">
              <p className="text-sm font-semibold text-ink mb-1">Premium Value Received</p>
              {/* No "value unlocked" calculation exists on the backend -- this whole panel is a
                  static illustrative figure matching the requested design, not computed from real
                  usage. See the PR description's gap list. */}
              <p className="font-display text-3xl font-extrabold text-primary mb-3">₹8,450</p>
              <p className="text-xs text-muted mb-3">Estimated value unlocked through:</p>
              <ul className="space-y-1.5">
                {['Financial insights', 'Budget tracking', 'Goal management', 'Smart categorization', 'Referral rewards'].map((item) => (
                  <li key={item} className="flex items-center gap-2 text-sm text-ink">
                    <Check size={14} className="text-success flex-shrink-0" /> {item}
                  </li>
                ))}
              </ul>
            </div>
          </div>
        </FinoraCard>
      )}

      <div>
        <h2 className="text-sm font-semibold text-ink mb-3">How you're using {isFree ? 'Fynora' : 'Premium'}</h2>
        <div className="grid grid-cols-2 md:grid-cols-3 gap-4">
          <UsageTile label="Smart Insights" value={(insightsUsage?.viewCount ?? 0).toLocaleString('en-IN')} desc="insights viewed" icon={Sparkles} iconBg="bg-purple-100" iconColor="text-purple-600" />
          <UsageTile label="Goals Created" value={String(goals?.length ?? 0)} desc={(goals?.length ?? 0) === 1 ? 'goal' : 'goals'} icon={Target} iconBg="bg-primary-light" iconColor="text-primary" />
          <UsageTile label="Budgets Managed" value={String(budgets?.length ?? 0)} desc={(budgets?.length ?? 0) === 1 ? 'budget' : 'budgets'} icon={PiggyBank} iconBg="bg-green-100" iconColor="text-green-600" />
          <UsageTile label="Statement Imports" value={String(importStats?.totalStatements ?? 0)} desc="statements imported" icon={UploadCloud} iconBg="bg-blue-100" iconColor="text-blue-600" />
          <UsageTile label="Connected Accounts" value={String(accounts?.length ?? 0)} desc={(accounts?.length ?? 0) === 1 ? 'account' : 'accounts'} icon={Wallet} iconBg="bg-warning-bg" iconColor="text-warning" />
          <UsageTile label="Transactions Imported" value={(importStats?.totalTransactionsImported ?? 0).toLocaleString('en-IN')} desc="transactions" icon={ArrowLeftRight} iconBg="bg-blue-100" iconColor="text-blue-600" />
        </div>
      </div>

      <div>
        {isRevenueCat && (
          <p className="text-xs text-muted mb-3">
            Your plan is managed through the App Store/Play Store — switch plans there, not here.
          </p>
        )}
        {subscription.status === 'PAUSED' && (
          <p className="text-xs text-muted mb-3">Resume your subscription to change plans.</p>
        )}
        <div className="flex items-center justify-between mb-3 flex-wrap gap-3">
          <h2 className="text-sm font-semibold text-ink">Choose the plan that's right for you</h2>
          <div className="inline-flex items-center gap-1 bg-bg border border-border rounded-lg p-1">
            {CHECKOUT_CYCLES.map((c) => (
              <button
                key={c.code}
                type="button"
                onClick={() => setTargetCycle(c.code)}
                className={`text-xs font-semibold px-3 py-1.5 rounded-md transition-colors ${targetCycle === c.code ? 'bg-card shadow-card text-ink' : 'text-muted'}`}
              >
                {c.label}
              </button>
            ))}
          </div>
        </div>
        <div className="grid md:grid-cols-3 gap-4">
          {PLANS.map((plan) => {
            const code = plan.id.toUpperCase();
            // billingCycle is null for both Free AND an admin-granted complimentary plan
            // (SubscriptionService.changePlan's ADMIN_GRANT path only ever sets planId, never
            // billingCycle) -- comparing cycles only when one actually exists to compare keeps a
            // comped Premium/Plus subscriber's own current-plan card correctly showing "Current
            // Plan" instead of an enabled "Switch to Monthly billing" that would open a real
            // Razorpay checkout for a plan they already have for free.
            const isCurrent = code === subscription.planCode && (subscription.billingCycle === null || targetCycle === subscription.billingCycle);
            const isSameplanDifferentCycle = code === subscription.planCode && subscription.billingCycle !== null && targetCycle !== subscription.billingCycle;
            const isPopular = plan.id === 'premium';
            // Bug found in a second bug-hunt pass, post-merge: changePlan()'s downgrade path can
            // schedule a Razorpay plan-change on a subscription that also has a pending, not-yet-
            // dispatched cancellation (autoRenew=false) -- the backend now refuses this (see
            // BillingCheckoutService.changePlan's own comment), so mirror that here per this page's
            // "Option 2: disabled controls, not hidden" design philosophy rather than let the click
            // round-trip into an error. Upgrade is unaffected -- see the backend's own reasoning.
            const isDowngrade = subscription.hasBillingSubscription && code !== 'FREE' && TIER_RANK[code] < TIER_RANK[subscription.planCode];
            const downgradeBlockedByPendingCancel = isDowngrade && !subscription.autoRenew;
            return (
              <FinoraCard
                key={plan.id}
                padding="lg"
                className={`flex flex-col ${isPopular ? 'ring-2 ring-primary relative' : ''}`}
              >
                {isPopular && (
                  <span className="absolute -top-3 right-5 text-[10px] uppercase font-semibold bg-primary text-on-primary rounded-full px-2.5 py-1">
                    Most Popular
                  </span>
                )}
                <p className="font-semibold text-ink mb-1">{plan.name}</p>
                <p className="font-display text-2xl font-extrabold text-ink mb-1">
                  {plan.price}
                  {plan.cadence && <span className="text-sm font-medium text-muted">{plan.cadence}</span>}
                </p>
                {plan.secondaryPriceNote && <p className="text-xs text-muted mb-3">{plan.secondaryPriceNote}</p>}
                <ul className="space-y-2 mb-6 flex-1">
                  {plan.features.map((f) => (
                    <li key={f} className="flex items-start gap-2 text-sm text-ink">
                      <Check size={14} className="text-success flex-shrink-0 mt-0.5" /> {f}
                    </li>
                  ))}
                </ul>
                <Button
                  variant={isCurrent ? 'secondary' : 'primary'}
                  hoverScale={!isCurrent}
                  disabled={isCurrent || isSubmitting || !!activatingPlanCode || isRevenueCat || subscription.status === 'PAUSED' || downgradeBlockedByPendingCancel}
                  onClick={() => {
                    if (code === 'FREE' && subscription.hasBillingSubscription) { setConfirmingCancel(true); return; }
                    void subscribeToPlan(code, targetCycle);
                  }}
                >
                  {isCurrent
                    ? 'Current Plan'
                    : isSameplanDifferentCycle
                      ? `Switch to ${CHECKOUT_CYCLES.find((c) => c.code === targetCycle)?.label} billing`
                      : code === 'FREE'
                        ? 'Switch to Free'
                        : `${TIER_RANK[code] > TIER_RANK[subscription.planCode] ? 'Choose' : 'Switch to'} ${plan.name}`}
                </Button>
                {downgradeBlockedByPendingCancel && (
                  <p className="text-xs text-muted mt-2">Resume auto-renewal first to downgrade instead.</p>
                )}
              </FinoraCard>
            );
          })}
        </div>
      </div>

      <div className={isFree ? '' : 'grid lg:grid-cols-2 gap-6'}>
        <FinoraCard padding="lg">
          <div className="flex items-center gap-2.5 mb-4">
            <div className="w-9 h-9 rounded-full bg-purple-100 flex items-center justify-center">
              <Gift size={16} className="text-purple-600" />
            </div>
            <p className="font-semibold text-ink">Referral Rewards</p>
          </div>
          <div className="grid grid-cols-2 gap-4 mb-4">
            <div>
              <p className="text-xs uppercase text-muted mb-1">Total Earned</p>
              {/* Static -- see the KPI row's own note on referralsApi.mine() having no reward
                  ledger yet. */}
              <p className="font-display text-xl font-extrabold text-ink">₹1,250</p>
            </div>
            <div>
              <p className="text-xs uppercase text-muted mb-1 flex items-center gap-1"><Users size={12} /> Referrals</p>
              <p className="font-display text-xl font-extrabold text-ink">{referrals?.referralCount ?? 0}</p>
            </div>
            <div>
              <p className="text-xs uppercase text-muted mb-1">Pending Rewards</p>
              <p className="font-display text-xl font-extrabold text-ink">₹250</p>
            </div>
          </div>
          <Link to="/app/referrals">
            <Button hoverScale className="w-full">Invite Friends →</Button>
          </Link>
        </FinoraCard>

        {/* Claims a specific ₹ value "received" from Premium -- wrong to show to a Free user who
            hasn't unlocked any of it, the same reasoning the main membership panel's own "Premium
            Value Received" side already applies via its own isFree branch. */}
        {!isFree && (
          <FinoraCard padding="lg">
            <p className="font-semibold text-ink mb-4">Premium Benefits Summary</p>
            <ul className="space-y-2.5 mb-4">
              {[
                { label: 'Goal insights', value: '₹1,200' },
                { label: 'Advanced analytics', value: '₹2,000' },
                { label: 'Priority support', value: '₹500' },
                { label: 'Referral rewards', value: '₹1,250' },
              ].map((row) => (
                <li key={row.label} className="flex items-center justify-between text-sm">
                  <span className="flex items-center gap-2 text-ink"><Check size={14} className="text-success" /> {row.label}</span>
                  <span className="font-medium text-ink">{row.value}</span>
                </li>
              ))}
            </ul>
            <div className="pt-4 border-t border-border flex items-center justify-between">
              <p className="text-sm font-semibold text-ink">Total Value Received</p>
              <p className="font-display text-xl font-extrabold text-primary">₹8,450</p>
            </div>
            <p className="text-xs text-muted mt-2">Estimated value unlocked with Fynora Premium.</p>
          </FinoraCard>
        )}
      </div>

      <div>
        <h2 className="text-sm font-semibold text-ink mb-2">Billing history</h2>
        {payments.length === 0 ? (
          <FinoraCard padding="lg">
            <EmptyState
              icon={Receipt}
              iconBg="bg-blue-100"
              iconColor="text-blue-600"
              title="No billing history yet"
              desc="Payment records will appear here once you've made your first payment."
            />
          </FinoraCard>
        ) : (
          <div className="bg-card rounded-xl2 shadow-card border border-border overflow-hidden overflow-x-auto">
            <table className="w-full text-sm min-w-[640px]">
              <thead>
                <tr className="border-b border-border">
                  <th className="text-left font-semibold text-muted text-xs uppercase px-5 py-3">Date</th>
                  <th className="text-left font-semibold text-muted text-xs uppercase px-4 py-3">Reference</th>
                  <th className="text-left font-semibold text-muted text-xs uppercase px-4 py-3">Amount</th>
                  <th className="text-left font-semibold text-muted text-xs uppercase px-4 py-3">Status</th>
                  <th className="text-left font-semibold text-muted text-xs uppercase px-4 py-3">Payment Method</th>
                  <th className="text-left font-semibold text-muted text-xs uppercase px-5 py-3">Actions</th>
                </tr>
              </thead>
              <tbody className="divide-y divide-border">
                {payments.map((p) => {
                  const status = statusLabel(p.status);
                  return (
                    <tr key={p.id}>
                      <td className="px-5 py-3.5 text-ink whitespace-nowrap">{formatDate(p.createdAt)}</td>
                      <td className="px-4 py-3.5 text-muted whitespace-nowrap">{p.id.slice(0, 8).toUpperCase()}</td>
                      <td className="px-4 py-3.5 text-ink font-medium whitespace-nowrap">{fmt(p.amount, p.currency)}</td>
                      <td className="px-4 py-3.5">
                        <span className={`text-[10px] uppercase font-semibold rounded px-2 py-1 ${status.className}`}>{status.text}</span>
                      </td>
                      <td className="px-4 py-3.5 text-muted capitalize whitespace-nowrap">{p.provider ?? '—'}</td>
                      <td className="px-5 py-3.5 whitespace-nowrap">
                        {/* Only a completed charge has anything to invoice -- InvoiceService
                            answers 409 for PENDING/FAILED/REFUNDED rows (no credit-note concept
                            in V1), so those states keep the disabled placeholder instead. */}
                        {p.status === 'SUCCESS' ? (
                          <>
                            <button
                              type="button"
                              onClick={() => viewInvoice(p.id)}
                              disabled={invoiceBusyIds.has(p.id)}
                              className="text-xs text-primary hover:underline disabled:opacity-50 disabled:cursor-not-allowed disabled:no-underline"
                            >
                              View
                            </button>
                            <span className="text-border mx-1.5">·</span>
                            <button
                              type="button"
                              onClick={() => downloadInvoice(p.id)}
                              disabled={invoiceBusyIds.has(p.id)}
                              className="text-xs text-primary hover:underline disabled:opacity-50 disabled:cursor-not-allowed disabled:no-underline"
                            >
                              Download
                            </button>
                          </>
                        ) : (
                          <>
                            <button type="button" disabled title="Only available for a completed payment" className="text-xs text-muted opacity-50 cursor-not-allowed">View</button>
                            <span className="text-border mx-1.5">·</span>
                            <button type="button" disabled title="Only available for a completed payment" className="text-xs text-muted opacity-50 cursor-not-allowed">Download</button>
                          </>
                        )}
                      </td>
                    </tr>
                  );
                })}
              </tbody>
            </table>
          </div>
        )}
      </div>

      <div className="grid lg:grid-cols-2 gap-6">
        <FinoraCard padding="lg">
          <div className="flex items-center gap-2.5 mb-3">
            <div className="w-9 h-9 rounded-full bg-primary-light flex items-center justify-center">
              <CreditCard size={16} className="text-primary" />
            </div>
            <div>
              <p className="font-semibold text-ink text-sm">Payment Method</p>
              <p className="text-xs text-muted">How your subscription is billed</p>
            </div>
          </div>
          {isRevenueCat ? (
            <>
              <p className="text-sm text-ink">Billed through the App Store/Play Store, not Razorpay.</p>
              <p className="text-xs text-muted mt-1">Update your card or billing details in your device's own subscription settings.</p>
            </>
          ) : subscription.hasBillingSubscription ? (
            <>
              {/* Razorpay's own subscription.activated/subscription.charged webhooks already carry
                  payment.entity.card (last4/network/type) for a card-authorized mandate -- captured
                  by RazorpayWebhookDispatcher onto the subscription row, not fabricated. Null for a
                  UPI/emandate mandate, or before the first such webhook lands. */}
              <p className="text-sm text-ink">
                {subscription.paymentMethod?.cardLast4
                  ? <>{subscription.paymentMethod.cardNetwork} •••• {subscription.paymentMethod.cardLast4}
                      {subscription.paymentMethod.cardType ? ` (${subscription.paymentMethod.cardType})` : ''}</>
                  : 'Managed securely through Razorpay Checkout at each billing cycle.'}
              </p>
              <p className="text-xs text-muted mt-1">
                Fynora doesn't store your card details — Razorpay authorizes each charge directly with your bank.
              </p>
            </>
          ) : (
            // Same admin-granted-plan gap as the membership card's own Payment method row above --
            // hasBillingSubscription is false for a comped plan, and this card previously claimed
            // "Razorpay authorizes each charge" for an account that was never actually charged.
            <p className="text-sm text-ink">No payment method on file — this plan isn't billed.</p>
          )}
          {/* Only a card-authorized mandate can be updated this way (Razorpay's own limitation --
              UPI/emandate can't) -- hidden rather than shown-disabled when there's no card to
              update, matching how the rest of this page hides an action it can't perform. */}
          {subscription.paymentMethod?.cardLast4 && (
            <Button
              variant="secondary" size="sm" className="mt-4"
              disabled={isSubmitting || !!activatingPlanCode}
              onClick={updatePaymentMethod}
            >
              <CreditCard size={14} /> Update Payment Method
            </Button>
          )}
        </FinoraCard>

        <FinoraCard padding="lg">
          <p className="font-semibold text-ink text-sm mb-4">Account Controls</p>
          {isRevenueCat && (
            <p className="text-xs text-muted mb-3 -mt-1">
              Managed through the App Store/Play Store — controls below are read-only here.
            </p>
          )}
          <div className="flex items-center justify-between py-3 border-b border-border">
            <div>
              <p className="text-sm text-ink font-medium">Auto Renewal</p>
              <p className="text-xs text-muted mt-0.5">
                {subscription.status === 'PAUSED'
                  ? 'Billing is on hold -- resume to pick up your regular renewal schedule again.'
                  : subscription.hasBillingSubscription && subscription.autoRenew
                    ? `Your subscription will automatically renew on ${subscription.renewalDate ? formatDate(subscription.renewalDate) : 'your next billing date'}.`
                    : subscription.hasBillingSubscription && subscription.autoRenewResumable
                      ? "Auto-renewal is off -- turn it back on to keep this subscription."
                      : subscription.hasBillingSubscription
                        ? 'Too close to your renewal date to resume -- you can subscribe again once this period ends.'
                        : 'Auto-renewal is currently off.'}
              </p>
            </div>
            <button
              type="button"
              role="switch"
              aria-checked={!!(subscription.hasBillingSubscription && subscription.autoRenew)}
              aria-label="Auto renewal"
              disabled={isRevenueCat || !subscription.hasBillingSubscription || subscription.status !== 'ACTIVE'
                || (!subscription.autoRenew && !subscription.autoRenewResumable) || undoCancellationMutation.isPending}
              onClick={() => {
                if (subscription.autoRenew) {
                  setConfirmingCancel(true);
                } else {
                  undoCancellationMutation.mutate();
                }
              }}
              className={`w-11 h-6 rounded-full transition-colors relative flex-shrink-0 disabled:opacity-40 ${subscription.hasBillingSubscription && subscription.autoRenew ? 'bg-primary' : 'bg-border'}`}
            >
              <span className={`absolute top-0.5 left-0.5 w-5 h-5 rounded-full bg-white shadow transition-transform ${subscription.hasBillingSubscription && subscription.autoRenew ? 'translate-x-5' : ''}`} />
            </button>
          </div>
          {subscription.status === 'PAUSED' && !isRevenueCat && (
            <div className="flex items-center justify-between py-3 border-b border-border">
              <div>
                <p className="text-sm text-ink font-medium">Resume Subscription</p>
                <p className="text-xs text-muted mt-0.5">Billing and Premium access are on hold until you resume.</p>
              </div>
              <Button size="sm" onClick={() => resumeMutation.mutate()} disabled={resumeMutation.isPending}>
                <PlayCircle size={13} /> Resume
              </Button>
            </div>
          )}
          {subscription.hasBillingSubscription && subscription.status === 'ACTIVE' && subscription.autoRenew && !isRevenueCat && (
            <div className="flex items-center justify-between py-3 border-b border-border">
              <div>
                <p className="text-sm text-ink font-medium">Pause Subscription</p>
                <p className="text-xs text-muted mt-0.5">Stop billing right away; resume anytime with no new checkout.</p>
              </div>
              <Button variant="secondary" size="sm" onClick={() => setConfirmingPause(true)}>
                <PauseCircle size={13} /> Pause
              </Button>
            </div>
          )}
          {subscription.hasBillingSubscription && subscription.status === 'ACTIVE' && subscription.autoRenew && !isRevenueCat && (
            <div className="flex items-center justify-between py-3">
              <div>
                <p className="text-sm text-danger font-medium">Cancel Subscription</p>
                <p className="text-xs text-muted mt-0.5">You'll lose access to Premium features at the end of your billing period.</p>
              </div>
              <Button variant="danger" size="sm" onClick={() => setConfirmingCancel(true)}>Cancel</Button>
            </div>
          )}
        </FinoraCard>
      </div>

      {confirmingCancel && (
        <ConfirmDialog
          title="Cancel subscription?"
          message="Your plan stays active until the end of the current billing period, then moves to Free."
          confirmLabel="Confirm"
          danger
          busy={cancelMutation.isPending}
          onConfirm={() => cancelMutation.mutate()}
          onCancel={() => setConfirmingCancel(false)}
        />
      )}

      {confirmingPause && (
        <ConfirmDialog
          title="Pause subscription?"
          message="Billing stops right away and Premium features turn off until you resume. Your plan and payment setup stay put, so resuming needs no new checkout."
          confirmLabel="Pause"
          busy={pauseMutation.isPending}
          onConfirm={() => pauseMutation.mutate()}
          onCancel={() => setConfirmingPause(false)}
        />
      )}

      {confirmingCancelPendingOrder && (
        <ConfirmDialog
          title="Cancel this pending checkout?"
          message="You'll be able to start a fresh checkout for any plan afterward."
          confirmLabel="Confirm"
          danger
          busy={cancelPendingOrderMutation.isPending}
          onConfirm={() => cancelPendingOrderMutation.mutate()}
          onCancel={() => setConfirmingCancelPendingOrder(false)}
        />
      )}
    </div>
  );
}

function UsageTile({
  label, value, desc, icon: Icon, iconBg, iconColor,
}: {
  label: string; value: string; desc: string; icon: LucideIcon; iconBg: string; iconColor: string;
}) {
  return (
    <FinoraCard className="transition-[transform,box-shadow] duration-150 hover:-translate-y-0.5 hover:shadow-soft">
      <div className="flex items-start justify-between mb-3">
        <div className={`w-10 h-10 rounded-xl ${iconBg} flex items-center justify-center flex-shrink-0`}>
          <Icon size={18} className={iconColor} />
        </div>
      </div>
      <p className="font-display text-xl font-extrabold text-ink">{value}</p>
      <p className="text-xs text-muted mt-0.5">{label} · {desc}</p>
    </FinoraCard>
  );
}
