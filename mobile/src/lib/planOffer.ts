import { useQuery } from '@tanstack/react-query';
import { billingApi, type BillingOffer } from '../api/endpoints';
import { playIntroOffer } from './revenueCat';

const MONTHS = ['Jan', 'Feb', 'Mar', 'Apr', 'May', 'Jun', 'Jul', 'Aug', 'Sep', 'Oct', 'Nov', 'Dec'];
// India has one time zone and no daylight saving, so a fixed offset is exact.
const IST_OFFSET_MS = (5 * 60 + 30) * 60_000;

/**
 * The last day an offer can be taken, in India, e.g. "7 Nov". endsAt is exclusive (the first
 * moment the offer no longer applies), so midnight 8 Nov IST reads as 7 Nov. Worked out by hand
 * rather than with toLocaleDateString's timeZone option, so it does not depend on the time-zone
 * data the phone's JavaScript engine carries. Null for an unreadable date.
 */
export function offerLastDay(endsAt: string): string | null {
  const ends = Date.parse(endsAt);
  if (Number.isNaN(ends)) return null;
  const india = new Date(ends - 1 + IST_OFFSET_MS);
  return `${india.getUTCDate()} ${MONTHS[india.getUTCMonth()]}`;
}

/** Plus monthly's first-month offer as this customer would be charged it on Google Play. */
export interface PlusOffer {
  /** e.g. "₹125": Play's own price for the first month. */
  offerPrice: string;
  /** e.g. "₹249": Play's own price for every month after it. */
  regularPrice: string;
  /** The live campaign's name ("Diwali offer"), or null when no campaign is live on the backend. */
  label: string | null;
  /** The campaign's last day ("7 Nov"), or null with no live campaign. */
  lastDay: string | null;
  /** The campaign's code ("DIWALI2026"), or null with no live campaign. */
  code: string | null;
}

function livePlusMonthly(offers: BillingOffer[], now: number): BillingOffer | undefined {
  return offers.find((o) => o.planCode === 'PLUS' && o.billingCycle === 'MONTHLY' && Date.parse(o.endsAt) > now);
}

/**
 * The Plus offer to show on mobile, or null.
 *
 * The PRICE always comes from Google Play (playIntroOffer), never from the backend: Play is what
 * charges, so the app can never show a price Play would not take. The backend's live offer
 * (GET /billing/offers) only supplies the campaign's name and last day. With no Play offer there is
 * nothing to show, whatever the backend says -- that is iOS, a customer who has had Plus before,
 * and every phone once the Play Console offer is deactivated.
 */
export function usePlusOffer(): PlusOffer | null {
  const play = useQuery({
    queryKey: ['play-intro-offer', 'PLUS', 'MONTHLY'],
    queryFn: () => playIntroOffer('PLUS', 'MONTHLY'),
    staleTime: 5 * 60_000,
    retry: false,
  });
  // The backend already lists only offers live right now; the end is checked again here so a
  // response from just before the end can't name a campaign that has since closed.
  const campaign = useQuery({
    queryKey: ['billing-offers'],
    queryFn: async () => livePlusMonthly(await billingApi.offers(), Date.now()) ?? null,
    staleTime: 5 * 60_000,
    retry: false,
    enabled: play.data != null,
  });

  if (!play.data) return null;
  const live = campaign.data;
  return {
    ...play.data,
    label: live?.label ?? null,
    lastDay: live ? offerLastDay(live.endsAt) : null,
    code: live?.code ?? null,
  };
}
