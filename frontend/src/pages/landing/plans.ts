/**
 * Plan configuration — the single source of truth for what Fynora offers and what can actually be
 * bought today.
 *
 * Separated from the components so enabling Premium later is a data change, not a UI rewrite:
 * flip `availability` to 'available', give it a `price`, and both the pricing cards and the
 * "Growing with you" ladder update together. They read from this same array precisely so they
 * cannot drift into describing different products, which is what happened when each had its own
 * hardcoded list.
 *
 * THE RULE THIS FILE ENFORCES: `price` may only be set on a plan whose availability is
 * 'available'. Anything else shows its status where the price would go. A number beside a small
 * "coming soon" tag still reads as a price, and an invented one is remembered by whoever
 * screenshotted it.
 *
 * Plus and Premium are real, purchasable plans now (subscription billing V1/V2, PRs #1008/#1016)
 * -- these four numbers (₹399/₹3,500/₹799/₹8,000) are the design spec's own §2 pricing decision
 * table and match the already-seeded `billing_prices` rows exactly, not invented for this page.
 * Checkout itself happens inside the app's Billing Portal (frontend/src/pages/Billing.tsx), never
 * on this public page -- see Pricing.tsx's own doc comment for why.
 *
 * landing-claims.test.tsx asserts the price/availability rule rather than trusting this comment.
 */

export type Availability = 'available' | 'coming-soon' | 'planned' | 'exploring';

export interface Plan {
  id: string;
  name: string;
  /** Only ever set when availability is 'available'. See the rule above. */
  price: string | null;
  cadence?: string;
  /** A second cadence worth mentioning below the primary price (e.g. the yearly price, when the
   *  primary price shown is monthly) -- purely informational, never a second buyable price on its
   *  own; checking out at a specific cycle happens inside the app's Billing Portal, not here. */
  secondaryPriceNote?: string;
  /** True for Plus/Premium: the sticker price above is GST-exclusive -- checkout charges this
   *  amount plus 18% GST (InvoiceService's own default gst-rate-percent), which is also what the
   *  invoice PDF itemizes. Unset for Free, where no payment (and so no GST) ever applies. */
  priceExcludesGst?: boolean;
  availability: Availability;
  blurb: string;
  features: string[];
  /**
   * What this plan is FOR, in one line. The card leads with this rather than a feature list --
   * people don't buy "unlimited accounts", they buy going deeper into their own finances.
   */
  promise: string;
  /** The outcome this stage unlocks, for the "Growing with you" ladder. Progress, not features. */
  stage: { when: string; outcome: string };
  /** Kept for the comparison table, which is where feature-by-feature belongs. */
  ladder: string[];
}

export const AVAILABILITY_LABEL: Record<Availability, string> = {
  available: 'Available today',
  'coming-soon': 'Coming soon',
  planned: 'Planned',
  exploring: 'Exploring',
};

export const AVAILABILITY_STYLE: Record<Availability, { background: string; color: string }> = {
  available: { background: '#DCFCE7', color: '#166534' },
  'coming-soon': { background: 'var(--m-brand-wash)', color: 'var(--m-brand-deep)' },
  planned: { background: '#F1F5F9', color: '#64748B' },
  exploring: { background: '#F1F5F9', color: '#94A3B8' },
};

// Free/Plus/Premium — Product's Billing Plan Taxonomy Decision, 2026-08-12 (see
// docs/proposals/billing-subscription-entitlements-proposal.md §3.1/§3.2). Family and Future
// were dropped, not renamed; Plus and Premium's feature lists below follow that same decision's
// entitlement mapping (§3.2), not invented copy — Plus gets deeper analysis of a user's own data,
// Premium adds investment insights on top of it.
//
// Fino (a financial assistant) and Priority support were part of the original §3.2 proposal and
// shipped as seeded FeatureEntitlement keys (FINO_AI, PRIORITY_SUPPORT — see that entity's own
// comment), but neither has any enforcing call site or, in Fino's case, any implementation at
// all. Removed from this page 2026-09-09 rather than sold as included: see landing audit findings.
// Re-add only once each is actually built and gated, not before.
export const PLANS: Plan[] = [
  {
    id: 'free',
    name: 'Free',
    price: '₹0',
    cadence: '/month',
    availability: 'available',
    blurb: 'Everything you need to organize your money.',
    promise: 'Get your money in order.',
    stage: { when: 'Today', outcome: 'Organize your money.' },
    features: [
      'Import statements (PDF & CSV)',
      'Password-protected and multi-account files',
      // Billing Plan Taxonomy Decision, 2026-08-12's "Unlimited accounts" / "Extended financial
      // history" Plus differentiators only mean something if Free has a stated limit to grow out
      // of -- this is that limit, enforced by AccountService.create/ImportController (backend),
      // not just descriptive copy.
      'Up to 2 accounts, one month per statement',
      'Automatic categorization that learns',
      'Budgets, goals and reports',
      'Financial dashboard and insights',
    ],
    ladder: ['Import statements', 'Automatic categorization', 'Budgets and goals', 'Spending analysis'],
  },
  {
    id: 'plus',
    name: 'Plus',
    price: '₹399',
    cadence: '/month',
    secondaryPriceNote: 'or ₹3,500/year',
    priceExcludesGst: true,
    availability: 'available',
    blurb: 'For people who want deeper financial intelligence.',
    promise: 'For people who simply want to go deeper.',
    stage: { when: 'Tomorrow', outcome: 'Understand your spending patterns.' },
    features: [
      'Unlimited accounts',
      'Advanced reports and analytics',
      'Extended financial history',
      'Long-term trends',
    ],
    ladder: ['Unlimited accounts', 'Advanced reports', 'Extended history', 'Long-term trends'],
  },
  {
    id: 'premium',
    name: 'Premium',
    price: '₹799',
    cadence: '/month',
    secondaryPriceNote: 'or ₹8,000/year',
    priceExcludesGst: true,
    availability: 'available',
    blurb: 'For people who want their investments in the same picture as everything else.',
    promise: 'For people who want the full picture, investments included.',
    stage: { when: 'Later', outcome: 'See your investments alongside everything else.' },
    features: [
      'Everything in Plus',
      'Investment insights',
    ],
    ladder: ['Investment insights'],
  },
];

/**
 * Free vs Plus vs Premium, for the comparison table. Rows and tier columns follow the same
 * entitlement mapping PLANS' Plus/Premium feature lists do (billing proposal §3.2) — only
 * capabilities already committed to appear here, no invented rows padding a column to make the
 * table look worth reading.
 */
export const COMPARISON: { label: string; free: boolean; plus: boolean; premium: boolean }[] = [
  { label: 'Statement import', free: true, plus: true, premium: true },
  { label: 'Financial dashboard', free: true, plus: true, premium: true },
  { label: 'Transaction categorization', free: true, plus: true, premium: true },
  { label: 'Budget tracking', free: true, plus: true, premium: true },
  { label: 'Learning engine', free: true, plus: true, premium: true },
  // Was missing here even though it's the first item in Plus's own `features` list above --
  // this is the Free-tier 2-account cap (AccountService.create, FeatureEntitlement
  // .UNLIMITED_ACCOUNTS), confirmed final 2026-09-06.
  { label: 'Unlimited accounts', free: false, plus: true, premium: true },
  { label: 'Advanced analytics', free: false, plus: true, premium: true },
  { label: 'Extended financial history', free: false, plus: true, premium: true },
  { label: 'Long-term trends', free: false, plus: true, premium: true },
  { label: 'Investment insights', free: false, plus: false, premium: true },
];

/** The plans shown as cards. Every current tier is real and committed, so this is just an alias
 *  for PLANS today -- kept as its own export (rather than importing PLANS directly in Pricing.tsx)
 *  in case a future tier is added that belongs in the ladder but not the buyable card grid, the
 *  same distinction `future` used to draw. */
export const PRICING_CARDS = PLANS;

/**
 * Shared Monthly/Yearly display logic for anywhere a plan's price is shown -- both the public
 * Pricing.tsx cards and the in-app Billing.tsx cards need this, and living here once means they
 * cannot drift into showing different numbers for the same toggle state the way they did before
 * this was factored out (Billing.tsx's own cycle toggle changed what a checkout charged without
 * ever changing what the card displayed).
 */
export type BillingCycle = 'monthly' | 'yearly';

/** Pulls the leading ₹ amount out of a price string ("₹3,500/year" -> 3500). Returns null rather
 *  than throwing on a shape it doesn't recognize, since a null just falls back to the plain
 *  monthly price below -- there's no scenario where a parse miss should break either page. */
export function parseRupees(text: string | null | undefined): number | null {
  const match = text?.match(/₹([\d,]+)/);
  return match ? Number(match[1].replace(/,/g, '')) : null;
}

/** What to show as the primary price/cadence and the smaller note beneath it, for the selected
 *  billing cycle. Free (no secondaryPriceNote) is unaffected by the toggle -- it only has one
 *  price to show either way. Every number here is still exactly `plan.price` or a figure pulled
 *  straight out of `plan.secondaryPriceNote` -- the same two sources landing-claims.test.tsx
 *  audits -- so no new price is ever invented by toggling. */
export function priceForCycle(plan: Plan, cycle: BillingCycle): { amount: string; cadence: string; note?: string } {
  const monthly = { amount: plan.price ?? '', cadence: plan.cadence ?? '', note: plan.secondaryPriceNote };
  if (cycle === 'monthly' || !plan.secondaryPriceNote) return monthly;

  const yearlyAmount = parseRupees(plan.secondaryPriceNote);
  if (yearlyAmount == null) return monthly;

  return {
    amount: `₹${yearlyAmount.toLocaleString('en-IN')}`,
    cadence: '/year',
    note: `or ${plan.price}${plan.cadence ?? ''}`,
  };
}

/** Percentage saved by paying yearly instead of 12x the monthly price -- computed from the same
 *  two real numbers above, never a hardcoded figure. Plus and Premium save different amounts
 *  (27% vs 17%), so this is per-plan rather than one banner claim covering both. */
export function yearlySavingsPct(plan: Plan): number | null {
  if (plan.cadence !== '/month' || !plan.secondaryPriceNote) return null;
  const monthly = parseRupees(plan.price);
  const yearly = parseRupees(plan.secondaryPriceNote);
  if (!monthly || !yearly) return null;
  const pct = Math.round((1 - yearly / (monthly * 12)) * 100);
  return pct > 0 ? pct : null;
}
