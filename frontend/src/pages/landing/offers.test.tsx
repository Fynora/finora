import { render, screen, waitFor } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { MemoryRouter } from 'react-router-dom';
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import { billingApi, type BillingOffer } from '../../api/endpoints';
import { Pricing } from './Pricing';
import { offerDisplay, offerFor, offerHeadline, offerLastDay, resetBillingOffersCache } from './offers';
import { OfferBanner } from './OfferBanner';

// Synthetic offer fixtures, shaped like GET /billing/offers. endsAt is midnight 8 Nov in India,
// the exclusive end of an offer whose last day is 7 Nov.
const monthly: BillingOffer = {
  planCode: 'PLUS', billingCycle: 'MONTHLY', code: 'DIWALI2026', label: 'Diwali offer',
  firstPeriodPrice: 125, regularPrice: 249, endsAt: '2099-11-07T18:30:00Z',
};
const yearly: BillingOffer = { ...monthly, billingCycle: 'YEARLY', firstPeriodPrice: 999, regularPrice: 1999 };

describe('offer helpers', () => {
  it('reads the last day in India time from the exclusive end', () => {
    expect(offerLastDay({ ...monthly, endsAt: '2026-11-07T18:30:00Z' })).toBe('7 Nov');
    // en-IN writes September as "Sept"; the app shows "Sep" everywhere (withSep).
    expect(offerLastDay({ ...monthly, endsAt: '2026-09-07T18:30:00Z' })).toBe('7 Sep');
  });

  it('matches the card by plan and the toggle by cycle', () => {
    expect(offerFor([monthly, yearly], 'plus', 'monthly')).toBe(monthly);
    expect(offerFor([monthly, yearly], 'plus', 'yearly')).toBe(yearly);
    expect(offerFor([monthly], 'plus', 'yearly')).toBeUndefined();
    expect(offerFor([monthly], 'premium', 'monthly')).toBeUndefined();
  });

  it('says what the first period costs and what follows', () => {
    expect(offerDisplay(monthly).note).toBe('₹125 for your first month, then ₹249/month');
    expect(offerDisplay(yearly).note).toBe('₹999 for your first year, then ₹1,999/year');
    expect(offerDisplay({ ...monthly, endsAt: '2026-11-07T18:30:00Z' }).badge).toBe('Diwali offer · ends 7 Nov');
  });
});

describe('offerHeadline', () => {
  it('names the monthly price, what follows, and the yearly price', () => {
    expect(offerHeadline([monthly, yearly], 'PLUS', 'Plus')?.text)
      .toBe('Plus at ₹125 for your first month (then ₹249/month), or ₹999 for your first year');
  });

  it('works with only a yearly offer', () => {
    expect(offerHeadline([yearly], 'PLUS', 'Plus')?.text)
      .toBe('Plus at ₹999 for your first year (then ₹1,999/year)');
  });

  it('is null with no offer on the plan', () => {
    expect(offerHeadline([monthly], 'PREMIUM', 'Premium')).toBeNull();
  });
});

describe('OfferBanner', () => {
  beforeEach(() => resetBillingOffersCache());
  afterEach(() => {
    vi.restoreAllMocks();
    resetBillingOffersCache();
  });

  it('announces the live offer and links to pricing', async () => {
    vi.spyOn(billingApi, 'offers').mockResolvedValue([monthly]);
    render(<OfferBanner />);

    const banner = await screen.findByTestId('offer-banner');
    expect(banner).toHaveTextContent('Diwali offer: Plus at ₹125 for your first month (then ₹249/month).');
    expect(screen.getByRole('link', { name: /see pricing/i })).toHaveAttribute('href', '#pricing');
  });

  it('renders nothing without a live offer', async () => {
    const offers = vi.spyOn(billingApi, 'offers').mockResolvedValue([]);
    const { container } = render(<OfferBanner />);

    await waitFor(() => expect(offers).toHaveBeenCalled());
    expect(container).toBeEmptyDOMElement();
  });
});

describe('Pricing with a live offer', () => {
  beforeEach(() => {
    resetBillingOffersCache();
    localStorage.clear();
  });
  afterEach(() => {
    vi.restoreAllMocks();
    resetBillingOffersCache();
  });

  function renderPricing() {
    return render(<MemoryRouter><Pricing /></MemoryRouter>);
  }

  it('shows the offer price on Plus, with the regular price struck through', async () => {
    vi.spyOn(billingApi, 'offers').mockResolvedValue([monthly, yearly]);
    const user = userEvent.setup();
    renderPricing();

    expect(await screen.findByText('₹125 for your first month, then ₹249/month')).toBeInTheDocument();
    expect(screen.getByTestId('offer-badge-plus')).toHaveTextContent('Diwali offer · ends 7 Nov');

    await user.click(screen.getByRole('button', { name: 'Yearly' }));
    expect(await screen.findByText('₹999 for your first year, then ₹1,999/year')).toBeInTheDocument();
  });

  it('shows the regular prices when the offers request fails', async () => {
    const offers = vi.spyOn(billingApi, 'offers').mockRejectedValue(new Error('network'));
    renderPricing();

    await waitFor(() => expect(offers).toHaveBeenCalled());
    expect(screen.queryByTestId('offer-badge-plus')).toBeNull();
    expect(screen.getByText('₹249')).toBeInTheDocument();
  });

  it('drops an offer whose end has already passed', async () => {
    vi.spyOn(billingApi, 'offers').mockResolvedValue([{ ...monthly, endsAt: '2000-01-01T00:00:00Z' }]);
    renderPricing();

    await waitFor(() => expect(billingApi.offers).toHaveBeenCalled());
    expect(screen.queryByTestId('offer-badge-plus')).toBeNull();
  });
});
