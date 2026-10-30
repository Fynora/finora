import { ArrowRight } from 'lucide-react';
import { offerHeadline, useBillingOffers } from './offers';

/**
 * A strip above the marketing page's nav while a Plus offer is live (backend V297). Renders
 * nothing otherwise -- including when the offers request fails -- so the page never advertises a
 * price checkout won't charge. The link jumps to the pricing section, where the card carries the
 * same offer.
 */
export function OfferBanner() {
  const headline = offerHeadline(useBillingOffers(), 'PLUS', 'Plus');
  if (!headline) return null;
  return (
    <div
      data-testid="offer-banner"
      className="px-4 py-2.5 text-center text-[13px] leading-snug"
      style={{ background: '#FEF3C7', color: '#78350F' }}
    >
      <span className="font-semibold">{headline.label}:</span> {headline.text}. Ends {headline.lastDay}.{' '}
      <a href="#pricing" className="inline-flex items-center gap-1 font-semibold underline underline-offset-2">
        See pricing <ArrowRight size={13} aria-hidden="true" />
      </a>
    </div>
  );
}
