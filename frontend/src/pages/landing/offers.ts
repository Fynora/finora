import { useEffect, useState } from 'react';
import { billingApi, type BillingOffer } from '../../api/endpoints';
import { withSep } from '../../utils/date';
import type { BillingCycle } from './plans';

/** One request shared by every component that asks within five minutes. */
const CACHE_MS = 5 * 60 * 1000;
let cached: { at: number; request: Promise<BillingOffer[]> } | null = null;

function loadOffers(): Promise<BillingOffer[]> {
  if (!cached || Date.now() - cached.at > CACHE_MS) {
    // Promise.resolve().then so a synchronous throw (no network layer at all) also lands in the
    // catch and degrades to regular prices.
    const request = Promise.resolve()
      .then(() => billingApi.offers())
      .then((o) => (Array.isArray(o) ? o : []))
      .catch(() => [] as BillingOffer[]);
    cached = { at: Date.now(), request };
  }
  return cached.request;
}

/** Test seam: forget the shared request. */
export function resetBillingOffersCache() {
  cached = null;
}

/**
 * Live first-period offers (backend V297, GET /billing/offers). Every price shown here comes from
 * the backend, the same rows checkout charges from, so a page can never advertise an offer the
 * checkout won't honour or keep showing one after it ends. When the request fails, pages just
 * show regular prices: the customer is then charged no more than what they read.
 *
 * A plain effect rather than React Query: the public landing page renders outside the app's
 * QueryClientProvider in tests and in places that render it on its own.
 */
export function useBillingOffers(): BillingOffer[] {
  const [offers, setOffers] = useState<BillingOffer[]>([]);
  useEffect(() => {
    let live = true;
    void loadOffers().then((o) => {
      if (live) setOffers(o);
    });
    return () => {
      live = false;
    };
  }, []);
  const now = Date.now();
  return offers.filter((o) => Date.parse(o.endsAt) > now);
}

/** One line announcing the live offers on a plan, for the marketing banner and the in-app pop-up:
 *  "Plus at ₹125 for your first month (then ₹249/month), or ₹999 for your first year". Null when
 *  the plan has no live offer. */
export function offerHeadline(offers: BillingOffer[], planCode: string, planName: string): {
  label: string; text: string; lastDay: string; code: string;
} | null {
  const monthly = offers.find((o) => o.planCode === planCode && o.billingCycle === 'MONTHLY');
  const yearly = offers.find((o) => o.planCode === planCode && o.billingCycle === 'YEARLY');
  const lead = monthly ?? yearly;
  if (!lead) return null;
  const parts: string[] = [];
  if (monthly) {
    parts.push(`${planName} at ${rupees(monthly.firstPeriodPrice)} for your first month (then ${rupees(monthly.regularPrice)}/month)`);
  }
  if (yearly) {
    parts.push(monthly
      ? `or ${rupees(yearly.firstPeriodPrice)} for your first year`
      : `${planName} at ${rupees(yearly.firstPeriodPrice)} for your first year (then ${rupees(yearly.regularPrice)}/year)`);
  }
  return { label: lead.label, text: parts.join(', '), lastDay: offerLastDay(lead), code: lead.code };
}

/** The offer for this plan card and billing cycle, if one is live. */
export function offerFor(offers: BillingOffer[], planId: string, cycle: BillingCycle): BillingOffer | undefined {
  const billingCycle = cycle === 'yearly' ? 'YEARLY' : 'MONTHLY';
  return offers.find((o) => o.planCode === planId.toUpperCase() && o.billingCycle === billingCycle);
}

const rupees = (amount: number) => `₹${amount.toLocaleString('en-IN')}`;

/** The last day an offer can be taken, in India: endsAt is exclusive (the first moment it no
 *  longer applies), so midnight 8 Nov reads as 7 Nov. */
export function offerLastDay(offer: BillingOffer): string {
  return withSep(new Date(Date.parse(offer.endsAt) - 1).toLocaleDateString('en-IN', { day: 'numeric', month: 'short', timeZone: 'Asia/Kolkata' }));
}

/** Card text for a live offer: the offer price where the price goes, the regular price struck
 *  through beside it, and one line saying how long it lasts and what follows. */
export function offerDisplay(offer: BillingOffer): {
  amount: string; regular: string; cadence: string; note: string; badge: string;
} {
  const yearly = offer.billingCycle === 'YEARLY';
  const cadence = yearly ? '/year' : '/month';
  return {
    amount: rupees(offer.firstPeriodPrice),
    regular: rupees(offer.regularPrice),
    cadence,
    note: `${rupees(offer.firstPeriodPrice)} for your first ${yearly ? 'year' : 'month'}, then ${rupees(offer.regularPrice)}${cadence}`,
    badge: `${offer.label} · ends ${offerLastDay(offer)}`,
  };
}
