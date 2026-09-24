import type { ReactNode } from 'react';
import { act, renderHook, waitFor } from '@testing-library/react-native';
import { QueryClient, QueryClientProvider, useQuery } from '@tanstack/react-query';
import { useChangePolling } from './useChangePolling';
import { changesApi } from '../api/endpoints';
import { invalidateFinancialData, onLocalFinancialWrite } from './invalidateFinancialData';

jest.mock('../api/endpoints', () => ({ changesApi: { stamp: jest.fn() } }));

const mockedStamp = changesApi.stamp as jest.MockedFunction<typeof changesApi.stamp>;

/**
 * The app asks the backend, about every 30s while it is in front, whether anything changed on
 * another device, and re-runs its screens' queries only when the answer differs from last time.
 * What must hold: nothing is refetched merely because the poll ran, the FIRST answer is a baseline
 * rather than a "change", and a different answer refreshes what is on screen.
 */

const INTERVAL = 25;

function setup(enabled = true) {
  const queryClient = new QueryClient({ defaultOptions: { queries: { retry: false, gcTime: Infinity } } });
  const screenFetch = jest.fn(async () => 'data');
  const wrapper = ({ children }: { children: ReactNode }) => (
    <QueryClientProvider client={queryClient}>{children}</QueryClientProvider>
  );
  const view = renderHook(
    ({ on }: { on: boolean }) => {
      useChangePolling(on, INTERVAL);
      // A mounted screen: an active observer whose data the poll should refresh.
      useQuery({ queryKey: ['dashboard-summary'], queryFn: screenFetch, staleTime: Infinity });
    },
    { wrapper, initialProps: { on: enabled } }
  );
  return { queryClient, screenFetch, ...view };
}

const stamps = (...values: (string | Error)[]) => {
  let i = 0;
  mockedStamp.mockImplementation(async () => {
    const next = values[Math.min(i++, values.length - 1)];
    if (next instanceof Error) throw next;
    return { stamp: next };
  });
};

const polls = () => mockedStamp.mock.calls.length;
const tick = (ms: number) => act(async () => { await new Promise((resolve) => setTimeout(resolve, ms)); });

beforeEach(() => mockedStamp.mockReset());

describe('useChangePolling', () => {
  it('treats the first answer as a baseline, not a change', async () => {
    stamps('a');
    const { screenFetch } = setup();
    await waitFor(() => expect(polls()).toBeGreaterThanOrEqual(1));
    await waitFor(() => expect(screenFetch).toHaveBeenCalledTimes(1)); // its own first load only
    await tick(INTERVAL * 2);
    expect(screenFetch).toHaveBeenCalledTimes(1);
  });

  it('does nothing while the answer stays the same', async () => {
    stamps('a', 'a', 'a', 'a');
    const { screenFetch } = setup();
    await waitFor(() => expect(polls()).toBeGreaterThanOrEqual(3));
    expect(screenFetch).toHaveBeenCalledTimes(1);
  });

  it('refreshes what is on screen when the answer changes', async () => {
    stamps('a', 'a', 'b');
    const { screenFetch } = setup();
    await waitFor(() => expect(screenFetch).toHaveBeenCalledTimes(2));
  });

  it('refreshes once per change, not once per poll after it', async () => {
    stamps('a', 'b', 'b', 'b', 'b');
    const { screenFetch } = setup();
    await waitFor(() => expect(polls()).toBeGreaterThanOrEqual(5));
    expect(screenFetch).toHaveBeenCalledTimes(2);
  });

  it('never polls while disabled (signed out, or the app tabs are not showing)', async () => {
    stamps('a', 'b');
    setup(false);
    await tick(INTERVAL * 4);
    expect(polls()).toBe(0);
  });

  it('keeps comparing with what it last saw across a switch off and on', async () => {
    // Changes made while it was off are still changes -- the cached stamp is the baseline.
    stamps('a', 'b');
    const { rerender, screenFetch } = setup();
    await waitFor(() => expect(polls()).toBe(1));
    rerender({ on: false });
    rerender({ on: true });
    await waitFor(() => expect(screenFetch).toHaveBeenCalledTimes(2));
  });

  it('survives a failed poll and keeps the baseline it had', async () => {
    stamps('a', new Error('network'), 'a', 'b');
    const { screenFetch } = setup();
    await waitFor(() => expect(screenFetch).toHaveBeenCalledTimes(2));
  });

  it('refreshes the profile and categories as well as the financial screens', async () => {
    stamps('a', 'b');
    const queryClient = new QueryClient({ defaultOptions: { queries: { retry: false, gcTime: Infinity } } });
    const profileFetch = jest.fn(async () => ({ fullName: 'Fynora' }));
    const categoriesFetch = jest.fn(async () => ['Dining']);
    const unrelatedFetch = jest.fn(async () => 'tickets');
    renderHook(
      () => {
        useChangePolling(true, INTERVAL);
        useQuery({ queryKey: ['user-settings'], queryFn: profileFetch, staleTime: Infinity });
        useQuery({ queryKey: ['categories'], queryFn: categoriesFetch, staleTime: Infinity });
        useQuery({ queryKey: ['support-tickets-mine'], queryFn: unrelatedFetch, staleTime: Infinity });
      },
      { wrapper: ({ children }: { children: ReactNode }) => <QueryClientProvider client={queryClient}>{children}</QueryClientProvider> }
    );
    await waitFor(() => expect(profileFetch).toHaveBeenCalledTimes(2));
    await waitFor(() => expect(categoriesFetch).toHaveBeenCalledTimes(2));
    // Not something another device's edits can change, so it is left alone.
    expect(unrelatedFetch).toHaveBeenCalledTimes(1);
  });
});

describe('useChangePolling and edits made on this device', () => {
  it('does not refresh the screens a second time for its own edit', async () => {
    // 'a' is the baseline; the edit moves the stamp to 'b'; the next poll sees 'b' again.
    stamps('a', 'b', 'b', 'b', 'b');
    const { queryClient, screenFetch } = setup();
    await waitFor(() => expect(polls()).toBe(1));

    act(() => invalidateFinancialData(queryClient)); // what every local write calls
    await waitFor(() => expect(screenFetch).toHaveBeenCalledTimes(2)); // the edit's own refresh

    await waitFor(() => expect(polls()).toBeGreaterThanOrEqual(5));
    expect(screenFetch).toHaveBeenCalledTimes(2);
  });

  it('still refreshes for a change made elsewhere after the edit', async () => {
    stamps('a', 'b', 'c');
    const { queryClient, screenFetch } = setup();
    await waitFor(() => expect(polls()).toBe(1));

    act(() => invalidateFinancialData(queryClient));
    await waitFor(() => expect(screenFetch).toHaveBeenCalledTimes(2));
    // The next poll returns 'c': something else changed after the edit.
    await waitFor(() => expect(screenFetch).toHaveBeenCalledTimes(3));
  });

  it('falls back to one extra refresh when it cannot learn the stamp for the edit', async () => {
    stamps('a', new Error('offline'), 'b', 'b');
    const { queryClient, screenFetch } = setup();
    await waitFor(() => expect(polls()).toBe(1));

    act(() => invalidateFinancialData(queryClient));
    await waitFor(() => expect(screenFetch).toHaveBeenCalledTimes(3));
  });

  it('asks for the stamp before the screens start refetching', async () => {
    stamps('a', 'b');
    const { queryClient } = setup();
    await waitFor(() => expect(polls()).toBe(1));
    const order: string[] = [];
    mockedStamp.mockImplementation(async () => {
      order.push('stamp');
      return { stamp: 'b' };
    });
    const unsubscribe = queryClient.getQueryCache().subscribe((event) => {
      if (event.type === 'updated' && event.action.type === 'fetch' && event.query.queryKey[0] === 'dashboard-summary') {
        order.push('screen');
      }
    });

    act(() => invalidateFinancialData(queryClient));
    await waitFor(() => expect(order).toContain('screen'));

    expect(order[0]).toBe('stamp');
    unsubscribe();
  });

  it('refreshes the profile and categories on an edit too, so a change it absorbs is not lost', async () => {
    // The fresh reading may already include a rename made elsewhere a moment ago; the financial
    // cascade does not cover those two, so without this nothing would ever refetch them for it.
    stamps('a', 'b');
    const queryClient = new QueryClient({ defaultOptions: { queries: { retry: false, gcTime: Infinity } } });
    const profileFetch = jest.fn(async () => ({ fullName: 'Fynora' }));
    const categoriesFetch = jest.fn(async () => ['Dining']);
    renderHook(
      () => {
        useChangePolling(true, 10_000);
        useQuery({ queryKey: ['user-settings'], queryFn: profileFetch, staleTime: Infinity });
        useQuery({ queryKey: ['categories'], queryFn: categoriesFetch, staleTime: Infinity });
      },
      { wrapper: ({ children }: { children: ReactNode }) => <QueryClientProvider client={queryClient}>{children}</QueryClientProvider> }
    );
    await waitFor(() => expect(polls()).toBe(1));

    act(() => invalidateFinancialData(queryClient));

    await waitFor(() => expect(profileFetch).toHaveBeenCalledTimes(2));
    await waitFor(() => expect(categoriesFetch).toHaveBeenCalledTimes(2));
  });

  it('is not triggered by its own refresh (that would loop)', async () => {
    stamps('a', 'b', 'b');
    const listener = jest.fn();
    const off = onLocalFinancialWrite(listener);
    const { screenFetch } = setup();
    await waitFor(() => expect(screenFetch).toHaveBeenCalledTimes(2)); // detected the change from 'a' to 'b'
    expect(listener).not.toHaveBeenCalled();
    off();
  });

  it('does nothing about edits while it is switched off', async () => {
    stamps('a', 'b');
    const { queryClient } = setup(false);
    act(() => invalidateFinancialData(queryClient));
    await tick(INTERVAL * 2);
    expect(polls()).toBe(0);
  });
});
