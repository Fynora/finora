const TONE = {
  primary: 'bg-primary/15 text-primary',
  neutral: 'bg-bg text-muted border border-border',
  success: 'bg-success-bg text-success',
  warning: 'bg-warning-bg text-warning',
  danger: 'bg-danger-bg text-danger',
  // For a badge sitting on one of the app's existing dark surfaces (`bg-sidebar` -- see
  // MembershipIllustration/Billing's payment-method card art), where every other tone's
  // light-surface background would be invisible or low-contrast.
  onDark: 'bg-white/15 text-white',
  // Design-track Phase 4: the one badge tone allowed to carry the premium brand accent --
  // reserved for a paid-tier indicator (Billing's plan name), same gating discipline as
  // Button's `premium` variant. Not a general-purpose "nice" tone.
  premium: 'bg-premium-bg text-premium',
} as const;

/**
 * Extracted from Dashboard's one-off "Beta" pill and its recurring-item cadence pill (both the
 * same primary-tinted style) -- the only badges anywhere in the app today. "primary" names the
 * tone, not the word "Beta" -- this is also what Recurring's "Monthly"/"Weekly" labels use.
 * success/warning/danger added for Budgets' status pills (On track / Almost there / Over budget).
 * Deliberately just a visual primitive: no tier/entitlement logic here -- the billing
 * entitlement track's own PR4 (Premium Layer, gated on D-7) decides which features are actually
 * locked. The `premium` tone below is a separate, later addition (design-track Phase 4): it
 * only supplies the color for a paid-tier label a caller has already decided to show.
 */
export function Badge({ tone = 'primary', label, className = '' }: { tone?: keyof typeof TONE; label: string; className?: string }) {
  return (
    <span className={`text-2xs uppercase font-semibold px-1.5 py-0.5 rounded ${TONE[tone]} ${className}`}>
      {label}
    </span>
  );
}
