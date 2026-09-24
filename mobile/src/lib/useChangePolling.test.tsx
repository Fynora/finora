import type { ReactNode } from 'react';
import { act, renderHook, waitFor } from '@testing-library/react-native';
import { QueryClientProvider, focusManager, onlineManager, useQuery } from '@tanstack/react-query';
import { useChangePolling } from './useChangePolling';
import { changesApi, type ChangeStamp } from '../api/endpoints';
import { GatedQueryClient, __resetChangeSyncForTests } from './changeSync';
import { invalidateFinancialData } from './invalidateFinancialData';
import { shouldRefetchOnFocus } from './changeWatch';

jest.mock('../api/endpoints', () => ({ changesApi: { stamp: jest.fn() } }));

const mockedStamp = changesApi.stamp as jest.MockedFunction<typeof changesApi.stamp>;

/**
 * The app asks the backend, about every 30s while it is in front, whether anything changed on
 * another device, and refreshes only what a moved section names. What must hold: nothing is
 * refetched merely because the poll ran, the FIRST answer is a baseline rather than a "change",
 * only the kind of data that moved is refreshed, and the app's own edits are not mistaken for
 * changes made elsewhere (lib/changeSync.test.ts covers those rules in isolation; here they are
 * exercised through the real hook, client and focus handling).
 */

const INTERVAL = 25;

const SECTION_NAMES = ['transactions', 'accounts', 'statementImports', 'budgets', 'goals', 'categories', 'profile'];

function reading(value: string, overrides: Partial<ChangeStamp> = {}): ChangeStamp {
  const all: Record<string, string> = {};
  SECTION_NAMES.forEach((name) => {
    all[name] = value;
  });
  return { ...all, ...overrides } as unknown as ChangeStamp;
}

/**
 * The backend, as the tests see it: a value per section that a test changes at the moment "someone"
 * makes a change, so an answer reflects when it was asked (an answer given before an edit never
 * contains it), plus a way to make the next requests fail.
 */
let server: ChangeStamp;
let failures = 0;
const serve = (value: string, overrides: Partial<ChangeStamp> = {}) => {
  server = reading(value, overrides);
};
const failNext = (count: number) => {
  failures = count;
};

const polls = () => mockedStamp.mock.calls.length;
const tick = (ms: number) => act(async () => { await new Promise((resolve) => setTimeout(resolve, ms)); });

beforeEach(() => {
  mockedStamp.mockReset();
  __resetChangeSyncForTests();
  failures = 0;
  serve('a');
  mockedStamp.mockImplementation(async () => {
    if (failures > 0) {
      failures -= 1;
      throw new Error('offline');
    }
    return server;
  });
});

afterEach(() => {
  focusManager.setFocused(undefined);
  onlineManager.setOnline(true);
});

type Screens = {
  /** covered, financial */ summary: jest.Mock;
  profile: jest.Mock;
  categories: jest.Mock;
  /** not covered by the stamp */ tickets: jest.Mock;
};

function setup(opts: { enabled?: boolean; interval?: number; summaryStaleTime?: number; summaryMs?: number } = {}) {
  const { enabled = true, interval = INTERVAL, summaryStaleTime = Infinity, summaryMs } = opts;
  const queryClient = new GatedQueryClient({
    defaultOptions: { queries: { retry: false, gcTime: Infinity, refetchOnWindowFocus: shouldRefetchOnFocus, refetchOnReconnect: shouldRefetchOnFocus } },
  });
  queryClient.mount();
  const screens: Screens = {
    summary: jest.fn(async () => {
      if (summaryMs) await new Promise((resolve) => setTimeout(resolve, summaryMs));
      return 'summary';
    }),
    profile: jest.fn(async () => ({ fullName: 'Fynora' })),
    categories: jest.fn(async () => ['Dining']),
    tickets: jest.fn(async () => 'tickets'),
  };
  const view = renderHook(
    ({ on }: { on: boolean }) => {
      useChangePolling(on, interval);
      // Mounted screens: active observers whose data the poll should (or should not) refresh.
      useQuery({ queryKey: ['dashboard-summary'], queryFn: screens.summary, staleTime: summaryStaleTime });
      useQuery({ queryKey: ['user-settings'], queryFn: screens.profile, staleTime: Infinity });
      useQuery({ queryKey: ['categories'], queryFn: screens.categories, staleTime: Infinity });
      useQuery({ queryKey: ['support-tickets-mine'], queryFn: screens.tickets, staleTime: 0 });
    },
    {
      initialProps: { on: enabled },
      wrapper: ({ children }: { children: ReactNode }) => (
        <QueryClientProvider client={queryClient}>{children}</QueryClientProvider>
      ),
    }
  );
  return { queryClient, screens, ...view };
}

/** Lets the first answer render so the baseline is recorded. */
const settled = async () => {
  await waitFor(() => expect(polls()).toBeGreaterThanOrEqual(1));
  await tick(INTERVAL);
};

/** The app leaves and comes back: the check runs straight away. */
const returnToApp = async (ms = 80) => {
  act(() => focusManager.setFocused(false));
  act(() => focusManager.setFocused(true));
  await tick(ms);
};

describe('useChangePolling', () => {
  it('treats the first answer as a baseline, not a change', async () => {
    const { screens } = setup();
    await settled();
    await tick(INTERVAL * 3);
    expect(screens.summary).toHaveBeenCalledTimes(1);
  });

  it('does nothing while the answer stays the same', async () => {
    const { screens } = setup();
    await waitFor(() => expect(polls()).toBeGreaterThanOrEqual(4));
    expect(screens.summary).toHaveBeenCalledTimes(1);
    expect(screens.profile).toHaveBeenCalledTimes(1);
  });

  it('refreshes only the profile for a change to the profile: a rename on the web does not re-read the ledger', async () => {
    const { screens } = setup({ interval: 10_000 });
    await settled();

    serve('a', { profile: 'renamed' });
    await returnToApp();

    expect(screens.profile).toHaveBeenCalledTimes(2);
    expect(screens.summary).toHaveBeenCalledTimes(1);
    expect(screens.categories).toHaveBeenCalledTimes(1);
  });

  it('refreshes the financial screens for a change to transactions, and leaves the profile alone', async () => {
    const { screens } = setup({ interval: 10_000 });
    await settled();

    serve('a', { transactions: 'imported' });
    await returnToApp();

    expect(screens.summary).toHaveBeenCalledTimes(2);
    expect(screens.profile).toHaveBeenCalledTimes(1);
  });

  it('refreshes categories and the financial screens (their rows carry the names) for a category change', async () => {
    const { screens } = setup({ interval: 10_000 });
    await settled();

    serve('a', { categories: 'renamed' });
    await returnToApp();

    expect(screens.categories).toHaveBeenCalledTimes(2);
    expect(screens.summary).toHaveBeenCalledTimes(2);
    expect(screens.profile).toHaveBeenCalledTimes(1);
  });

  it('refreshes once per change, not once per poll after it', async () => {
    const { screens } = setup({ interval: 40 });
    await settled();

    serve('b');
    await waitFor(() => expect(screens.summary).toHaveBeenCalledTimes(2));
    const pollsAtChange = polls();
    await waitFor(() => expect(polls()).toBeGreaterThanOrEqual(pollsAtChange + 4));
    expect(screens.summary).toHaveBeenCalledTimes(2);
  });

  it('never polls while disabled (signed out, or the app tabs are not showing)', async () => {
    setup({ enabled: false });
    await tick(INTERVAL * 4);
    expect(polls()).toBe(0);
  });

  it('keeps comparing with what it last saw across a switch off and on', async () => {
    // Changes made while it was off are still changes.
    const { rerender, screens } = setup({ interval: 10_000 });
    await settled();
    rerender({ on: false });
    serve('b');
    rerender({ on: true });
    await waitFor(() => expect(screens.summary).toHaveBeenCalledTimes(2));
  });

  it('survives a failed poll and keeps the baseline it had', async () => {
    const { screens } = setup({ interval: 40 });
    await settled();

    failNext(2);
    serve('b');

    await waitFor(() => expect(screens.summary).toHaveBeenCalledTimes(2));
  });
});

describe('useChangePolling and edits made on this device', () => {
  it('does not refresh the screens a second time for its own edit', async () => {
    const { queryClient, screens } = setup({ interval: 60 });
    await settled();

    serve('a', { accounts: 'mine' }); // the edit lands on the server...
    act(() => invalidateFinancialData(queryClient)); // ...and the app refreshes, as every local write does
    await waitFor(() => expect(screens.summary).toHaveBeenCalledTimes(2)); // the edit's own refresh

    const pollsAtEdit = polls();
    await waitFor(() => expect(polls()).toBeGreaterThanOrEqual(pollsAtEdit + 4));
    expect(screens.summary).toHaveBeenCalledTimes(2);
  });

  it('treats a plain invalidation of a section\'s data the same way (budgets, goals, statements...)', async () => {
    const budgets = jest.fn(async () => ['budget']);
    const queryClient = new GatedQueryClient({ defaultOptions: { queries: { retry: false, gcTime: Infinity } } });
    renderHook(
      () => {
        useChangePolling(true, 60);
        useQuery({ queryKey: ['budgets'], queryFn: budgets, staleTime: Infinity });
      },
      { wrapper: ({ children }: { children: ReactNode }) => <QueryClientProvider client={queryClient}>{children}</QueryClientProvider> }
    );
    await settled();

    serve('a', { budgets: 'edited' });
    act(() => {
      void queryClient.invalidateQueries({ queryKey: ['budgets'] });
    });
    await waitFor(() => expect(budgets).toHaveBeenCalledTimes(2));

    const pollsAtEdit = polls();
    await waitFor(() => expect(polls()).toBeGreaterThanOrEqual(pollsAtEdit + 4));
    expect(budgets).toHaveBeenCalledTimes(2);
  });

  it('still refreshes for a change made elsewhere after the edit', async () => {
    const { queryClient, screens } = setup({ interval: 60 });
    await settled();
    serve('a', { accounts: 'mine' });
    act(() => invalidateFinancialData(queryClient));
    await waitFor(() => expect(screens.summary).toHaveBeenCalledTimes(2));

    serve('a', { accounts: 'mine', profile: 'renamed' }); // someone else, afterwards

    // Only the profile is news: the ledger is not re-read.
    await waitFor(() => expect(screens.profile).toHaveBeenCalledTimes(2));
    expect(screens.summary).toHaveBeenCalledTimes(2);
  });

  it('never hides a rename made elsewhere at the same moment as an edit made here', async () => {
    // The reading taken for the edit already contains the rename. Only the sections whose data is
    // refetched are rebaselined, so the profile still counts as a change at the next poll.
    const { queryClient, screens } = setup({ interval: 60 });
    await settled();

    serve('a', { accounts: 'mine', profile: 'renamed' });
    act(() => invalidateFinancialData(queryClient));

    await waitFor(() => expect(screens.profile).toHaveBeenCalledTimes(2));
  });

  it('falls back to one extra refresh when it cannot take the reading for the edit', async () => {
    const { queryClient, screens } = setup({ interval: 10_000 });
    await settled();

    serve('b');
    failNext(1); // the reading for the edit fails
    act(() => invalidateFinancialData(queryClient));
    await waitFor(() => expect(screens.summary).toHaveBeenCalledTimes(2)); // went ahead without it

    await returnToApp(); // the next check finds the edit and refreshes once more
    expect(screens.summary).toHaveBeenCalledTimes(3);
  });

  it('does nothing about edits while it is switched off', async () => {
    const { queryClient } = setup({ enabled: false });
    act(() => invalidateFinancialData(queryClient));
    await tick(INTERVAL * 2);
    expect(polls()).toBe(0);
  });
});

describe('returning to the app after a change made elsewhere', () => {
  it('refreshes what changed exactly once, not once for the focus and again for the stamp', async () => {
    const { screens } = setup({ interval: 10_000, summaryStaleTime: 0 });
    await waitFor(() => expect(polls()).toBe(1));
    await waitFor(() => expect(screens.summary).toHaveBeenCalledTimes(1));
    await tick(50);

    serve('b');
    await returnToApp();

    expect(polls()).toBe(2);
    expect(screens.summary).toHaveBeenCalledTimes(2);
  });

  it('refetches nothing the stamp covers when nothing changed', async () => {
    const { screens } = setup({ interval: 10_000, summaryStaleTime: 0 });
    await waitFor(() => expect(polls()).toBe(1));
    await waitFor(() => expect(screens.summary).toHaveBeenCalledTimes(1));
    await tick(50);

    await returnToApp();

    expect(polls()).toBe(2);
    expect(screens.summary).toHaveBeenCalledTimes(1);
  });

  it('still refetches what the stamp does not cover', async () => {
    const { screens } = setup({ interval: 10_000 });
    await waitFor(() => expect(screens.tickets).toHaveBeenCalledTimes(1));
    await tick(50);

    await returnToApp();

    expect(screens.tickets).toHaveBeenCalledTimes(2);
  });

  it('does not restart a fetch already in flight when the stamp reports the change', async () => {
    // A covered query fetched on an earlier day is refetched by the focus AND named by the stamp.
    const { queryClient, screens } = setup({ interval: 10_000, summaryStaleTime: 0, summaryMs: 30 });
    await waitFor(() => expect(screens.summary).toHaveBeenCalledTimes(1));
    await waitFor(() => expect(queryClient.getQueryState(['dashboard-summary'])?.fetchStatus).toBe('idle'));
    await tick(50);
    queryClient.setQueryData(['dashboard-summary'], 'summary', { updatedAt: Date.now() - 26 * 60 * 60 * 1000 });

    serve('b');
    await returnToApp();

    expect(screens.summary).toHaveBeenCalledTimes(2);
  });
});

describe('when the check itself fails', () => {
  it('falls back to refreshing the stale data it covers when returning to the app', async () => {
    const { screens } = setup({ interval: 10_000, summaryStaleTime: 0 });
    await waitFor(() => expect(screens.summary).toHaveBeenCalledTimes(1));
    await tick(50);

    failNext(1000); // the return cannot be checked, so refetch as the focus would have
    await returnToApp(40);

    expect(screens.summary).toHaveBeenCalledTimes(2);
  });

  it('does not refetch what is still fresh, exactly as a focus refetch would not', async () => {
    const { screens } = setup({ interval: 10_000, summaryStaleTime: Infinity });
    await waitFor(() => expect(screens.summary).toHaveBeenCalledTimes(1));
    await tick(50);

    failNext(1000);
    await returnToApp(40);

    expect(screens.summary).toHaveBeenCalledTimes(1);
  });

  it('does nothing extra when an ordinary poll fails (no request storm during an outage)', async () => {
    const { screens } = setup({ interval: 60, summaryStaleTime: 0 });
    await settled();

    failNext(1000);
    const pollsBefore = polls();
    await waitFor(() => expect(polls()).toBeGreaterThanOrEqual(pollsBefore + 3));

    expect(screens.summary).toHaveBeenCalledTimes(1);
  });

  it('falls back once per return, not once per later failure', async () => {
    const { screens } = setup({ interval: 60, summaryStaleTime: 0 });
    await settled();

    failNext(1000);
    await returnToApp(40);
    const pollsAfterReturn = polls();
    await waitFor(() => expect(polls()).toBeGreaterThanOrEqual(pollsAfterReturn + 3));

    expect(screens.summary).toHaveBeenCalledTimes(2);
  });
});

describe('coming back online', () => {
  it('refreshes what changed while offline exactly once, not once for the reconnect and again for the stamp', async () => {
    const { screens } = setup({ interval: 10_000, summaryStaleTime: 0 });
    await waitFor(() => expect(screens.summary).toHaveBeenCalledTimes(1));
    await tick(50);

    act(() => onlineManager.setOnline(false));
    serve('b');
    await tick(20);
    act(() => onlineManager.setOnline(true));
    await tick(120);

    expect(screens.summary).toHaveBeenCalledTimes(2);
  });

  it('refetches nothing the stamp covers when nothing changed while offline, but does refetch what it does not cover', async () => {
    const { screens } = setup({ interval: 10_000, summaryStaleTime: 0 });
    await waitFor(() => expect(screens.summary).toHaveBeenCalledTimes(1));
    await waitFor(() => expect(screens.tickets).toHaveBeenCalledTimes(1));
    await tick(50);

    act(() => onlineManager.setOnline(false));
    await tick(20);
    act(() => onlineManager.setOnline(true));
    await tick(120);

    expect(screens.summary).toHaveBeenCalledTimes(1);
    expect(screens.tickets).toHaveBeenCalledTimes(2);
  });

  it('falls back to refreshing the stale data it covers when the check right after reconnecting fails', async () => {
    const { screens } = setup({ interval: 10_000, summaryStaleTime: 0 });
    await waitFor(() => expect(screens.summary).toHaveBeenCalledTimes(1));
    await tick(50);

    act(() => onlineManager.setOnline(false));
    await tick(20);
    failNext(1000);
    act(() => onlineManager.setOnline(true));
    await tick(120);

    expect(screens.summary).toHaveBeenCalledTimes(2);
  });
});
