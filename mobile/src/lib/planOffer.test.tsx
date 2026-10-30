import type { ReactNode } from 'react';
import { renderHook, waitFor } from '@testing-library/react-native';
import { QueryClient, QueryClientProvider } from '@tanstack/react-query';
import { billingApi, type BillingOffer } from '../api/endpoints';
import { playIntroOffer } from './revenueCat';
import { offerLastDay, usePlusOffer } from './planOffer';

jest.mock('../api/endpoints', () => ({ billingApi: { offers: jest.fn() } }));
jest.mock('./revenueCat', () => ({ playIntroOffer: jest.fn() }));

const mockedOffers = billingApi.offers as jest.MockedFunction<typeof billingApi.offers>;
const mockedPlay = playIntroOffer as jest.MockedFunction<typeof playIntroOffer>;

// Synthetic campaign rows, shaped like GET /billing/offers.
const plusMonthly: BillingOffer = {
  planCode: 'PLUS', billingCycle: 'MONTHLY', code: 'DIWALI2026', label: 'Diwali offer',
  firstPeriodPrice: 125, regularPrice: 249, endsAt: '2099-11-07T18:30:00Z',
};

function wrapper({ children }: { children: ReactNode }) {
  const client = new QueryClient({ defaultOptions: { queries: { retry: false, gcTime: 0 } } });
  return <QueryClientProvider client={client}>{children}</QueryClientProvider>;
}

describe('offerLastDay', () => {
  it('reads an exclusive midnight end in India as the day before', () => {
    // 2026-11-08 00:00 IST, the seeded Diwali end.
    expect(offerLastDay('2026-11-07T18:30:00Z')).toBe('7 Nov');
  });

  it('keeps the same day for an end one minute after midnight in India', () => {
    expect(offerLastDay('2026-11-07T18:31:00Z')).toBe('8 Nov');
  });

  it('works out the day in India, not in UTC, across a month end', () => {
    // 2026-12-01 03:00 IST is still 30 Nov in UTC, but the last day in India is 1 Dec.
    expect(offerLastDay('2026-11-30T21:30:00Z')).toBe('1 Dec');
  });

  it('gives nothing for an unreadable date', () => {
    expect(offerLastDay('not a date')).toBeNull();
  });
});

describe('usePlusOffer', () => {
  beforeEach(() => {
    mockedOffers.mockReset().mockResolvedValue([plusMonthly]);
    mockedPlay.mockReset().mockResolvedValue({ offerPrice: '₹125', regularPrice: '₹249' });
  });

  it("puts Play's prices together with the live campaign's name and last day", async () => {
    const { result } = renderHook(() => usePlusOffer(), { wrapper });

    await waitFor(() => expect(result.current?.label).toBe('Diwali offer'));
    expect(result.current).toEqual({
      offerPrice: '₹125', regularPrice: '₹249', label: 'Diwali offer', lastDay: '7 Nov', code: 'DIWALI2026',
    });
  });

  it('is nothing when Play offers no first-month price, whatever the backend says', async () => {
    mockedPlay.mockResolvedValue(null);
    const { result } = renderHook(() => usePlusOffer(), { wrapper });

    await waitFor(() => expect(mockedPlay).toHaveBeenCalled());
    expect(result.current).toBeNull();
    // Nothing to name, so the campaign is not even asked for.
    expect(mockedOffers).not.toHaveBeenCalled();
  });

  it("uses Play's price, not the backend's, when the two differ", async () => {
    mockedPlay.mockResolvedValue({ offerPrice: '₹129', regularPrice: '₹249' });
    const { result } = renderHook(() => usePlusOffer(), { wrapper });

    await waitFor(() => expect(result.current?.label).toBe('Diwali offer'));
    expect(result.current?.offerPrice).toBe('₹129');
  });

  it.each([
    ['no campaign is live', []],
    ['the campaign has ended', [{ ...plusMonthly, endsAt: '2020-01-01T00:00:00Z' }]],
    ['the campaign is for the yearly plan only', [{ ...plusMonthly, billingCycle: 'YEARLY' }]],
  ])("keeps Play's price but names no campaign when %s", async (_why, offers) => {
    mockedOffers.mockResolvedValue(offers);
    const { result } = renderHook(() => usePlusOffer(), { wrapper });

    await waitFor(() => expect(mockedOffers).toHaveBeenCalled());
    await waitFor(() => expect(result.current).not.toBeNull());
    expect(result.current).toEqual({ offerPrice: '₹125', regularPrice: '₹249', label: null, lastDay: null, code: null });
  });

  it("keeps Play's price when the campaign request fails", async () => {
    mockedOffers.mockRejectedValue(new Error('offline'));
    const { result } = renderHook(() => usePlusOffer(), { wrapper });

    await waitFor(() => expect(mockedOffers).toHaveBeenCalled());
    await waitFor(() => expect(result.current?.offerPrice).toBe('₹125'));
    expect(result.current?.label).toBeNull();
  });
});
