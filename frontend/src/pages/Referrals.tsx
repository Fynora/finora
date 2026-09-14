import { useEffect, useState } from 'react';
import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query';
import { Gift, Copy, Check, Users } from 'lucide-react';
import { referralsApi } from '../api/endpoints';
import { formatDate } from '../utils/date';
import { FinoraCard, EmptyState } from '../design-system';
import { UpgradeCelebration } from '../components/UpgradeCelebration';
import { safeStorage } from '../lib/safeStorage';

// A grant activates asynchronously via the backend's nightly sweep (design spec section 6.4:
// "fires ... at the moment a grant activates ... not at redemption"), so there's no synchronous
// "just redeemed" moment to hang the animation off of -- this page has to notice an ACTIVE grant
// it hasn't shown the celebration for yet, whenever it happens to load. Persisted per-browser (not
// per-account server-side) so a user who checks Refer & Earn from a second device still sees it
// there once, independently -- an acceptable trade-off for a one-time celebratory moment, not
// something requiring cross-device dedup.
const SEEN_ACTIVE_GRANTS_KEY = 'finora_seen_active_referral_grants';

function fmt(amount: number) {
  return '₹' + Math.round(amount).toLocaleString('en-IN');
}

function statusLabel(status: string) {
  switch (status) {
    case 'REWARDED': return { text: 'Rewarded', className: 'text-success bg-success-bg' };
    case 'SUBSCRIBED': return { text: 'Subscribed', className: 'text-primary bg-primary-light' };
    default: return { text: 'Registered', className: 'text-muted bg-bg' };
  }
}

/** Small reusable piece for one tier's row -- either a progress bar (below threshold) or a
 *  redeem card (at/above threshold). Both tiers render independently and simultaneously: reaching
 *  Premium's threshold never hides or replaces Plus's row, and vice versa (design spec section
 *  6.1, revised after product review -- progress is persistent, nothing is ever forfeited). */
function MilestoneRow({
  label, counter, threshold, onRedeem, redeeming,
}: {
  label: string; counter: number; threshold: number;
  onRedeem: () => void; redeeming: boolean;
}) {
  if (counter >= threshold) {
    return (
      <FinoraCard padding="lg">
        <p className="text-sm font-semibold text-ink mb-2">You&apos;ve unlocked a reward!</p>
        <p className="text-xs text-muted mb-3">Redeem 1 month of {label}, free.</p>
        <button
          type="button"
          className="text-sm font-semibold px-4 py-2 rounded-lg bg-primary text-on-primary disabled:opacity-50"
          disabled={redeeming}
          onClick={onRedeem}
        >
          Redeem {label}
        </button>
      </FinoraCard>
    );
  }
  const pct = Math.min(100, Math.round((counter / threshold) * 100));
  return (
    <FinoraCard padding="lg">
      <p className="text-sm font-semibold text-ink mb-2">
        {counter} / {threshold} toward {label}
      </p>
      <div className="h-2 rounded-full bg-bg overflow-hidden">
        <div className="h-full rounded-full bg-primary" style={{ width: `${pct}%` }} />
      </div>
    </FinoraCard>
  );
}

/**
 * Refer & Earn -- a user's own shareable code, their referrals, and their wallet balance. The
 * reward AMOUNT a referral eventually earns is set by an admin (ReferralService.creditReward's
 * own doc comment explains why), so this page shows whatever REWARDED referrals actually earned
 * -- it never predicts or advertises a number up front.
 */
export default function Referrals() {
  const [copied, setCopied] = useState(false);
  const { data: mine, isLoading } = useQuery({
    queryKey: ['referrals-mine'],
    queryFn: () => referralsApi.mine(),
  });
  const queryClient = useQueryClient();
  const redeemMutation = useMutation({
    mutationFn: (tier: 'PLUS' | 'PREMIUM') => referralsApi.redeem(tier),
    onSuccess: () => queryClient.invalidateQueries({ queryKey: ['referrals-mine'] }),
  });

  const [celebratingTier, setCelebratingTier] = useState<'PLUS' | 'PREMIUM' | null>(null);
  useEffect(() => {
    if (!mine) return;
    let seen: string[] = [];
    try {
      seen = JSON.parse(safeStorage.getItem(SEEN_ACTIVE_GRANTS_KEY) ?? '[]');
    } catch {
      seen = [];
    }
    const newlyActive = mine.grants.find((g) => g.status === 'ACTIVE' && !seen.includes(g.id));
    if (!newlyActive) return;
    setCelebratingTier(newlyActive.tier);
    safeStorage.setItem(SEEN_ACTIVE_GRANTS_KEY, JSON.stringify([...seen, newlyActive.id]));
    const timer = setTimeout(() => setCelebratingTier(null), 3000);
    return () => clearTimeout(timer);
  }, [mine]);

  const shareLink = mine ? `${window.location.origin}/register?ref=${mine.code}` : '';

  async function handleCopy() {
    try {
      await navigator.clipboard.writeText(shareLink);
      setCopied(true);
      setTimeout(() => setCopied(false), 2000);
    } catch {
      // Clipboard API unavailable (older browser, insecure context) -- the link is already
      // visible in the field below for a manual select-and-copy, so there's nothing more useful
      // to do than leave it there.
    }
  }

  const referrals = mine?.referrals ?? [];

  return (
    <div className="space-y-4">
      <div className="mb-2">
        <h1 className="text-xl font-bold text-ink">Refer & Earn</h1>
        <p className="text-sm text-muted">Share Fynora with friends and earn rewards when they join.</p>
      </div>

      {celebratingTier && (
        <div className="flex justify-center py-2">
          <UpgradeCelebration tier={celebratingTier} />
        </div>
      )}

      <FinoraCard padding="lg">
        <div className="flex items-center gap-2.5 mb-3">
          <div className="w-9 h-9 rounded-full bg-primary-light flex items-center justify-center">
            <Gift size={16} className="text-primary" />
          </div>
          <div>
            <p className="text-sm font-semibold text-ink">Your referral link</p>
            <p className="text-xs text-muted">Anyone who signs up with this link is credited to you.</p>
          </div>
        </div>
        {isLoading ? (
          <p className="text-sm text-muted">Loading…</p>
        ) : (
          <div className="flex items-center gap-2">
            <input
              readOnly
              value={shareLink}
              className="flex-1 min-w-0 border border-border rounded-lg px-3 py-2 text-sm bg-bg text-ink"
              onFocus={(e) => e.target.select()}
            />
            <button
              type="button"
              onClick={handleCopy}
              className="flex items-center gap-1.5 bg-primary text-white text-xs font-semibold rounded-lg px-3 py-2 flex-shrink-0"
            >
              {copied ? <Check size={14} /> : <Copy size={14} />}
              {copied ? 'Copied' : 'Copy'}
            </button>
          </div>
        )}
      </FinoraCard>

      <FinoraCard padding="lg">
        <p className="text-xs uppercase text-muted mb-1">Wallet balance</p>
        <p className="text-2xl font-bold text-ink">{isLoading ? '—' : fmt(mine?.walletBalance ?? 0)}</p>
      </FinoraCard>

      {mine && mine.grants.some((g) => g.status === 'ACTIVE' || g.status === 'PENDING') && (
        <FinoraCard padding="lg">
          <p className="text-xs uppercase text-muted mb-2">Your rewards</p>
          <div className="space-y-2">
            {mine.grants
              .filter((g) => g.status === 'ACTIVE')
              .map((g) => (
                <div key={g.id} className="flex items-center justify-between text-sm">
                  <span className="text-ink font-medium">{g.tier === 'PREMIUM' ? 'Premium' : 'Plus'} active</span>
                  {g.expiresAt && <span className="text-xs text-muted">until {formatDate(g.expiresAt)}</span>}
                </div>
              ))}
            {/* Oldest-first among PENDING grants -- mine.grants itself comes back newest-first,
                but the sweep activates queued grants FIFO (oldest first), so this order matches
                which one actually activates next. */}
            {[...mine.grants].filter((g) => g.status === 'PENDING').reverse().map((g) => (
              <div key={g.id} className="flex items-center justify-between text-sm">
                <span className="text-ink font-medium">{g.tier === 'PREMIUM' ? 'Premium' : 'Plus'} queued</span>
                <span className="text-xs text-muted">activates automatically</span>
              </div>
            ))}
          </div>
        </FinoraCard>
      )}

      {mine && (
        <>
          <MilestoneRow
            label="Plus" counter={mine.plusMilestoneCounter} threshold={3}
            onRedeem={() => redeemMutation.mutate('PLUS')} redeeming={redeemMutation.isPending}
          />
          <MilestoneRow
            label="Premium" counter={mine.premiumMilestoneCounter} threshold={7}
            onRedeem={() => redeemMutation.mutate('PREMIUM')} redeeming={redeemMutation.isPending}
          />
        </>
      )}

      {!isLoading && referrals.length === 0 ? (
        <FinoraCard padding="lg">
          <EmptyState
            icon={Users}
            iconBg="bg-accent-blue-bg"
            iconColor="text-accent-blue"
            title="No referrals yet"
            desc="Share your link above — when a friend signs up with it, they'll show up here."
          />
        </FinoraCard>
      ) : (
        <div className="bg-card rounded-xl2 shadow-card border border-border overflow-hidden">
          <div className="divide-y divide-border">
            {referrals.map((r) => {
              const status = statusLabel(r.status);
              return (
                <div key={r.referralId} className="px-5 py-3.5 flex items-center justify-between gap-4 flex-wrap">
                  <div className="min-w-0">
                    <p className="text-sm font-medium text-ink truncate">{r.referredUserFullName ?? 'A new user'}</p>
                    <p className="text-xs text-muted">
                      Joined {formatDate(r.createdAt)}{r.reward != null ? ` · Earned ${fmt(r.reward)}` : ''}
                    </p>
                  </div>
                  <span className={`text-2xs uppercase font-semibold rounded px-2 py-1 ${status.className}`}>
                    {status.text}
                  </span>
                </div>
              );
            })}
          </div>
        </div>
      )}
    </div>
  );
}
