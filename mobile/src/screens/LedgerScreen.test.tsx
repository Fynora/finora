import { Alert, Platform } from 'react-native';
import { act, fireEvent, render, screen, waitFor } from '@testing-library/react-native';
import { QueryClient, QueryClientProvider } from '@tanstack/react-query';
import { DateTimePickerAndroid } from '@react-native-community/datetimepicker';
import { usePreventScreenCapture } from 'expo-screen-capture';
import {
  DEFAULT_LEDGER_FILTERS, LEDGER_PAGE_SIZE, LedgerScreen, getLedgerNextPageParam, groupTransactionsByDay,
} from './LedgerScreen';
import { categoriesApi, dashboardApi, onboardingApi, transactionsApi } from '../api/endpoints';
import { hapticImpact } from '../lib/haptics';
import { invalidateFinancialData } from '../lib/invalidateFinancialData';
import type { LedgerDrillThroughFilters } from '../navigation/types';
import type { Transaction } from '../types';

// Controllable stand-in for useRoute, same pattern ImportScreen.test.tsx uses for its own
// reimport-arrival params (Track C/C4).
let mockRouteParams: { filters?: LedgerDrillThroughFilters } | undefined;
jest.mock('@react-navigation/native', () => ({
  useRoute: () => ({ params: mockRouteParams }),
}));

/**
 * Three outcomes of the same request must stay visibly different:
 *
 *   succeeded, no rows  -> "No transactions yet. Import a statement to get started."
 *   succeeded, has rows -> the rows
 *   failed              -> "Couldn't load your transactions." + a retry that re-requests
 *
 * Before this, the first and third were identical. A failed search left `data` undefined, so
 * `txns` was [] and FlatList fell through to ListEmptyComponent -- telling someone who may have
 * years of imported history that they have none, and sending them to re-import data they already
 * own. The empty copy is not neutral: it is an instruction to go and fix a problem that does not
 * exist.
 *
 * The tests assert the DIFFERENCE between the three, not each in isolation, because the bug was
 * never that one state rendered wrongly -- it was that two states rendered the same.
 */

jest.mock('../api/endpoints', () => ({
  transactionsApi: {
    search: jest.fn(), remove: jest.fn(), updateCategory: jest.fn(), source: jest.fn(),
    update: jest.fn(), create: jest.fn(), explanation: jest.fn(),
    markTransfer: jest.fn(), unmarkTransfer: jest.fn(),
    acknowledgeBankCorrection: jest.fn(), correctionHistory: jest.fn(),
  },
  accountsApi: { list: jest.fn().mockResolvedValue([{ id: 'a-1', name: 'HDFC Savings' }]) },
  categoriesApi: { list: jest.fn(), options: jest.fn().mockResolvedValue({ icons: [], colors: [] }) },
  dashboardApi: { summary: jest.fn() },
  // Getting-started checklist dwell timer (D-onboarding) -- default to "no REVIEW_TRANSACTIONS
  // item in the response" so it never fires in tests that don't care about it.
  onboardingApi: {
    getChecklist: jest.fn().mockResolvedValue({ items: [], completedCount: 0, totalCount: 6 }),
    completeChecklistItem: jest.fn().mockResolvedValue(undefined),
  },
}));

jest.mock('../lib/invalidateFinancialData', () => ({
  invalidateFinancialData: jest.fn(),
}));

jest.mock('../lib/haptics');

const transactions = transactionsApi as jest.Mocked<typeof transactionsApi>;
const categories = categoriesApi as jest.Mocked<typeof categoriesApi>;
const dashboard = dashboardApi as jest.Mocked<typeof dashboardApi>;

function txn(over: Partial<Transaction> = {}): Transaction {
  return {
    id: 't-1',
    date: '2026-07-14',
    description: 'Grocery run',
    merchant: 'Big Bazaar',
    amount: -1250,
    type: 'EXPENSE',
    category: 'Food',
    accountId: 'a-1',
    accountName: 'HDFC Savings',
    needsCategoryReview: false,
    recurring: false,
    categoryManuallySet: false,
    ...over,
  } as Transaction;
}

function page(content: Transaction[], over: Record<string, unknown> = {}) {
  return {
    content,
    page: 0,
    size: 20,
    totalElements: content.length,
    totalPages: content.length ? 1 : 0,
    ...over,
  };
}

// Tracked so afterEach can unmount every screen a test rendered -- gcTime: 0 on the client cancels
// each query's own GC timer, but a MOUNTED useQuery's stale-timeout (@tanstack/query-core's
// QueryObserver#updateStaleTimeout) is scheduled on every successful fetch independently of
// gcTime, and is only cancelled by the observer's own destroy(), which happens on unmount -- not by
// gcTime, and not by queryClient.clear() (Query#destroy() never touches its observers). This file
// had none of that: no afterEach, no explicit unmount in ~30 of its ~32 tests. Confirmed as a real,
// referenced timer surviving to the end of a full CI run via a Node diagnostic report (SIGUSR2
// mid-hang), on two separate runs, both times with a creation timestamp landing inside this exact
// file's own test block.
const activeScreens: { unmount: () => void }[] = [];
function renderScreen() {
  const queryClient = new QueryClient({
    defaultOptions: { queries: { retry: false, gcTime: 0 } },
  });
  const result = render(
    <QueryClientProvider client={queryClient}>
      <LedgerScreen />
    </QueryClientProvider>
  );
  activeScreens.push(result);
  return result;
}

beforeEach(() => {
  jest.clearAllMocks();
  mockRouteParams = undefined;
  categories.list.mockResolvedValue([
    { id: 'c-1', name: 'Food', isSystem: true, icon: 'utensils', color: 'orange' },
    { id: 'c-2', name: 'Travel', isSystem: true, icon: 'plane', color: 'blue' },
  ] as never);
  // Never resolves by default, so pre-existing tests that don't care about the summary card see
  // it stay permanently absent (the screen renders nothing extra until `summary` resolves) --
  // NOT mockResolvedValue(undefined), which TanStack Query logs a "Query data cannot be
  // undefined" console.error for on every affected test.
  dashboard.summary.mockReturnValue(new Promise(() => {}));
});

afterEach(() => {
  // .unmount() is safe to call again on the one test that already unmounts one of its two screens
  // mid-test -- react-test-renderer no-ops on an already-unmounted tree.
  activeScreens.splice(0).forEach((r) => r.unmount());
});

describe('the three outcomes stay distinguishable', () => {
  it('succeeded with no rows: offers the import prompt', async () => {
    transactions.search.mockResolvedValue(page([]) as never);

    renderScreen();

    expect(await screen.findByText(/No transactions yet/i)).toBeTruthy();
    // A genuine zero is not a failure and must never be dressed as one.
    expect(screen.queryByText(/Couldn't load your transactions/i)).toBeNull();
  });

  it('succeeded with rows: shows them', async () => {
    transactions.search.mockResolvedValue(page([txn(), txn({ id: 't-2', description: 'Salary' })]) as never);

    renderScreen();

    expect(await screen.findByText('Grocery run')).toBeTruthy();
    expect(screen.getByText('Salary')).toBeTruthy();
    expect(screen.queryByText(/No transactions yet/i)).toBeNull();
    expect(screen.queryByText(/Couldn't load your transactions/i)).toBeNull();
  });

  it('failed: says so, and never claims the ledger is empty', async () => {
    transactions.search.mockRejectedValue(new Error('Network Error'));

    renderScreen();

    expect(await screen.findByText(/Couldn't load your transactions/i)).toBeTruthy();
    // The regression this file exists for.
    expect(screen.queryByText(/No transactions yet/i)).toBeNull();
    expect(screen.queryByText(/Import a statement to get started/i)).toBeNull();
  });

  it('a failed request and an empty one do not render the same thing', async () => {
    // Renders both in one test so the assertion is the difference itself. A refactor that collapses
    // them fails here even when each branch looks individually reasonable.
    transactions.search.mockResolvedValue(page([]) as never);
    const ok = renderScreen();
    await screen.findByText(/No transactions yet/i);
    const emptyShowedError = screen.queryByText(/Couldn't load your transactions/i) !== null;
    ok.unmount();

    transactions.search.mockRejectedValue(new Error('Network Error'));
    renderScreen();
    await screen.findByText(/Couldn't load your transactions/i);
    const failureShowedEmpty = screen.queryByText(/No transactions yet/i) !== null;

    expect(emptyShowedError).toBe(false);
    expect(failureShowedEmpty).toBe(false);
  });
});

describe('counterparty label', () => {
  it('reads the same stored type two different ways depending on direction', async () => {
    transactions.search.mockResolvedValue(
      page([
        txn({ id: 't-out', description: 'PAID SUNIL', type: 'EXPENSE', counterpartyType: 'PERSON' }),
        txn({ id: 't-in', description: 'GOT FROM SUNIL', type: 'INCOME', counterpartyType: 'PERSON' }),
      ]) as never,
    );

    renderScreen();

    // The counterparty type stored is identical on both rows; the accessibility announcement
    // differs because direction is composed in at render time, never stored. This is the guard
    // against the V123 mistake -- a category literally named "Paid a Person" that turned out to be
    // money RECEIVED on 99 of 434 rows it was applied to.
    await waitFor(() => {
      expect(screen.getByLabelText(/Sent to a person/)).toBeTruthy();
      expect(screen.getByLabelText(/Received from a person/)).toBeTruthy();
    });
  });

  it('says nothing about the counterparty when it is unknown', async () => {
    // Roughly a fifth of real rows, plus everything a server backfill has not reached yet. Padding
    // every row in five with "unknown" would make the announcement slower to listen to for zero
    // information gained.
    transactions.search.mockResolvedValue(
      page([txn({ id: 't-1', description: 'Grocery run', counterpartyType: 'UNKNOWN' })]) as never,
    );

    renderScreen();

    await screen.findByText('Grocery run');
    expect(screen.queryByText(/unknown/i)).toBeNull();
  });
});

/**
 * `t.reconciliationStatus` used to be checked only for the 'DUPLICATE' case, appended as a plain
 * ' · Duplicate' string with no visual distinction and no accessibility exposure. The other five
 * non-OK values (TRANSFER, REFUND, REVERSAL, INVESTMENT_TRANSFER, SUPERSEDED) rendered nothing at
 * all -- silently indistinguishable from an ordinary OK transaction. Mirrors the web's
 * reconciliationBadge (frontend/src/pages/Ledger.tsx): OK gets no badge, every other status gets a
 * short label and an explanatory hint, tone-matched to what the status means.
 */
describe('reconciliation status indicator', () => {
  it('shows no badge for an ordinary OK transaction', async () => {
    transactions.search.mockResolvedValue(page([txn({ reconciliationStatus: 'OK' })]) as never);

    renderScreen();

    await screen.findByText('Grocery run');
    expect(screen.queryByTestId('reconciliation-badge-t-1')).toBeNull();
  });

  it.each([
    ['DUPLICATE', 'Duplicate'],
    ['TRANSFER', 'Transfer'],
    ['REFUND', 'Refund'],
    ['REVERSAL', 'Reversed'],
    ['INVESTMENT_TRANSFER', 'Investment'],
    ['SUPERSEDED', 'Superseded'],
  ] as const)('shows a %s badge labeled %s', async (status, label) => {
    transactions.search.mockResolvedValue(page([txn({ reconciliationStatus: status })]) as never);

    renderScreen();

    expect(await screen.findByText(label)).toBeTruthy();
  });

  it('names the status in the row\'s accessibility label, since a screen reader groups the badge into the row as one atomic element and would otherwise never announce it', async () => {
    transactions.search.mockResolvedValue(page([txn({ reconciliationStatus: 'DUPLICATE' })]) as never);

    renderScreen();

    expect(await screen.findByLabelText(/Matched as a repeat of another transaction/)).toBeTruthy();
  });
});

/**
 * Phase 5 (Low-Priority Polish). needsCategoryReview/recurring/categoryManuallySet were fetched
 * (every fixture in this file already sets them) but never rendered -- this row was silent about
 * review state entirely, independent of the reconciliation badge above (which is about a MATCH,
 * not review state).
 */
describe('status badges (Phase 5)', () => {
  it('shows "Categorized" for an ordinary, engine-categorized row with nothing else to flag', async () => {
    transactions.search.mockResolvedValue(page([txn()]) as never);

    renderScreen();

    expect(await screen.findByText('Categorized')).toBeTruthy();
  });

  it('shows "Reviewed" instead, once the category was set by hand', async () => {
    transactions.search.mockResolvedValue(page([txn({ categoryManuallySet: true })]) as never);

    renderScreen();

    expect(await screen.findByText('Reviewed')).toBeTruthy();
    expect(screen.queryByText('Categorized')).toBeNull();
  });

  it('shows "Needs Review" instead of the fallback when the engine is unsure', async () => {
    transactions.search.mockResolvedValue(page([txn({ needsCategoryReview: true })]) as never);

    renderScreen();

    expect(await screen.findByText('Needs Review')).toBeTruthy();
    expect(screen.queryByText('Categorized')).toBeNull();
  });

  it('shows both Needs Review and Recurring at once -- independent facts, not a priority chain', async () => {
    transactions.search.mockResolvedValue(
      page([txn({ needsCategoryReview: true, recurring: true })]) as never
    );

    renderScreen();

    await screen.findByText('Needs Review');
    expect(screen.getByText('Recurring')).toBeTruthy();
    // Neither fallback applies once either real flag is set.
    expect(screen.queryByText('Categorized')).toBeNull();
    expect(screen.queryByText('Reviewed')).toBeNull();
  });

  it('shows "Bank Correction" when pendingBankCorrection is true (Plan 6, Track B mobile parity)', async () => {
    transactions.search.mockResolvedValue(page([txn({ pendingBankCorrection: true })]) as never);

    renderScreen();

    expect(await screen.findByText('Bank Correction')).toBeTruthy();
  });

  it('does not show "Bank Correction" for an ordinary transaction', async () => {
    transactions.search.mockResolvedValue(page([txn()]) as never);

    renderScreen();

    await screen.findByText('Categorized');
    expect(screen.queryByText('Bank Correction')).toBeNull();
  });

  it('names every status badge in the row\'s accessibility label', async () => {
    transactions.search.mockResolvedValue(page([txn({ recurring: true })]) as never);

    renderScreen();

    expect(await screen.findByLabelText(/Recurring/)).toBeTruthy();
  });
});

describe('retry', () => {
  it('issues a NEW request rather than re-rendering the error', async () => {
    transactions.search.mockRejectedValueOnce(new Error('Network Error'));

    renderScreen();
    await screen.findByText(/Couldn't load your transactions/i);
    expect(transactions.search).toHaveBeenCalledTimes(1);

    transactions.search.mockResolvedValue(page([txn({ description: 'Recovered row' })]) as never);
    fireEvent.press(screen.getByText(/Try again/i));

    // The property that matters: the network was hit again. A button that only re-renders the
    // error state is worse than no button, because it looks like it did something.
    await waitFor(() => expect(transactions.search).toHaveBeenCalledTimes(2));
    expect(await screen.findByText('Recovered row')).toBeTruthy();
    expect(screen.queryByText(/Couldn't load your transactions/i)).toBeNull();
  });
});

describe('a failure while paging', () => {
  it('keeps the rows already on screen instead of blanking the list', async () => {
    // First page succeeds and reports a second page exists; the second fails.
    transactions.search
      .mockResolvedValueOnce(page([txn({ description: 'First page row' })], { totalPages: 2, totalElements: 40 }) as never)
      .mockRejectedValueOnce(new Error('Network Error'));

    renderScreen();
    const list = await screen.findByText('First page row');
    expect(list).toBeTruthy();

    fireEvent(screen.getByTestId('ledger-list'), 'onEndReached');

    await waitFor(() => expect(screen.getByText(/Couldn't load more transactions/i)).toBeTruthy());
    // The whole point of separating this from the empty-state branch: what the user was already
    // reading must survive a failed page.
    expect(screen.getByText('First page row')).toBeTruthy();
    expect(screen.queryByText(/No transactions yet/i)).toBeNull();
  });
});

describe('skeleton loading', () => {
  it('shows skeleton placeholder rows while the first page is loading, not a spinner', async () => {
    let resolveSearch: (value: unknown) => void = () => {};
    transactions.search.mockReturnValue(new Promise((resolve) => { resolveSearch = resolve as typeof resolveSearch; }));

    renderScreen();

    // The list itself now mounts immediately (its chrome -- search, filters, summary -- lives in
    // ListHeaderComponent so it scrolls with the rest of the content instead of sitting fixed
    // above a separate loading view); it's the skeleton rows rendered as its content, via
    // ListEmptyComponent, that signal the loading state -- not a spinner, and not the list's own
    // absence.
    expect(screen.getAllByTestId('skeleton-transaction-row', { hidden: true }).length).toBeGreaterThan(0);
    expect(screen.getByTestId('ledger-list')).toBeTruthy();

    await act(async () => resolveSearch(page([])));
  });
});

describe('DEFAULT_LEDGER_FILTERS export (for Dashboard prefetch)', () => {
  it('matches exactly what a fresh mount searches with', async () => {
    transactions.search.mockResolvedValue(page([]) as never);

    renderScreen();

    await screen.findByText(/No transactions yet/i);
    expect(transactions.search).toHaveBeenCalledWith({ ...DEFAULT_LEDGER_FILTERS, page: 0 });
  });

  it('exposes the page size and pagination cursor logic LedgerScreen itself uses', () => {
    expect(LEDGER_PAGE_SIZE).toBe(20);
    expect(DEFAULT_LEDGER_FILTERS).toEqual({ size: 20, sortField: 'date', sortDir: 'desc' });
    expect(getLedgerNextPageParam({ content: [], page: 0, size: 20, totalElements: 40, totalPages: 2 })).toBe(1);
    expect(getLedgerNextPageParam({ content: [], page: 1, size: 20, totalElements: 40, totalPages: 2 })).toBeUndefined();
  });
});

/**
 * Phase 4 (Medium-Tier Parity). Backs TransactionController.search's own `status` param -- present
 * on the backend since before this session (its doc comment names Ledger's Status column as the
 * reason it exists), unused by any client until now.
 */
describe('status filter (Phase 4)', () => {
  it('sends no status param by default', async () => {
    transactions.search.mockResolvedValue(page([]) as never);

    renderScreen();

    await waitFor(() => expect(transactions.search).toHaveBeenCalledWith(
      expect.objectContaining({ status: undefined })
    ));
  });

  it('filters by a real reconciliation status when its chip is picked', async () => {
    transactions.search.mockResolvedValue(page([]) as never);

    renderScreen();
    await waitFor(() => expect(transactions.search).toHaveBeenCalled());
    fireEvent.press(screen.getByLabelText('Filter by status: Duplicate'));

    await waitFor(() => expect(transactions.search).toHaveBeenCalledWith(
      expect.objectContaining({ status: 'DUPLICATE' })
    ));
  });

  // OK gets its own chip (unlike reconciliationBadge, which returns null for it, since there's
  // nothing to badge or explain about the ordinary case) -- "show me only the unflagged rows" is
  // still a real filter someone reviewing a batch of flagged rows might reach for.
  it('offers OK as its own filter, worded separately from the badge-derived labels', async () => {
    transactions.search.mockResolvedValue(page([]) as never);

    renderScreen();
    await waitFor(() => expect(transactions.search).toHaveBeenCalled());
    fireEvent.press(screen.getByLabelText('Filter by status: OK'));

    await waitFor(() => expect(transactions.search).toHaveBeenCalledWith(
      expect.objectContaining({ status: 'OK' })
    ));
  });

  it('clears the status filter when All is picked again', async () => {
    transactions.search.mockResolvedValue(page([]) as never);

    renderScreen();
    await waitFor(() => expect(transactions.search).toHaveBeenCalled());
    fireEvent.press(screen.getByLabelText('Filter by status: Duplicate'));
    await waitFor(() => expect(transactions.search).toHaveBeenCalledWith(
      expect.objectContaining({ status: 'DUPLICATE' })
    ));

    fireEvent.press(screen.getByLabelText('Filter by status: All'));

    await waitFor(() => expect(transactions.search).toHaveBeenCalledWith(
      expect.objectContaining({ status: undefined })
    ));
  });

  it('combines with the type filter rather than replacing it', async () => {
    transactions.search.mockResolvedValue(page([]) as never);

    renderScreen();
    await waitFor(() => expect(transactions.search).toHaveBeenCalled());
    fireEvent.press(screen.getByLabelText('Filter: expense'));
    fireEvent.press(screen.getByLabelText('Filter by status: Transfer'));

    await waitFor(() => expect(transactions.search).toHaveBeenCalledWith(
      expect.objectContaining({ type: 'EXPENSE', status: 'TRANSFER' })
    ));
  });

  it('says "no transactions match these filters" when a status filter narrows the list to nothing, not the fresh-account empty state', async () => {
    transactions.search.mockResolvedValue(page([]) as never);

    renderScreen();
    await waitFor(() => expect(transactions.search).toHaveBeenCalled());
    fireEvent.press(screen.getByLabelText('Filter by status: Superseded'));

    expect(await screen.findByText('No transactions match these filters.')).toBeTruthy();
    expect(screen.queryByText(/Import a statement to get started/)).toBeNull();
  });
});

describe('long-press haptic', () => {
  it('acknowledges the long press with an impact haptic before offering to delete', async () => {
    transactions.search.mockResolvedValue(page([txn()]) as never);
    renderScreen();
    await waitFor(() => screen.getByText('Grocery run'));

    fireEvent(screen.getByRole('button', { name: /Grocery run/ }), 'longPress');

    expect(hapticImpact).toHaveBeenCalledTimes(1);
  });
});

describe('the header count', () => {
  it('does not print "0 total" for a search that failed', async () => {
    // totalElements falls back to 0 when there are no pages, so a cold failure asserted a confident
    // zero directly above this screen's own "Couldn't load your transactions." -- contradicting, in
    // the header, the rule the error branch states explicitly.
    transactions.search.mockReset().mockRejectedValue(new Error('500'));

    renderScreen();

    expect(await screen.findByText(/Couldn't load your transactions/i)).toBeTruthy();
    expect(screen.queryByText('0 total')).toBeNull();
  });
});

/**
 * The half of the correction loop the review queue cannot reach.
 *
 * `/transactions/needs-review` only returns transactions the engine KNEW it was unsure about. One
 * it categorized confidently and wrongly never appears there -- so before this the ledger offered
 * no way to fix it at all: long-press-to-delete was the row's only write, which made "delete it
 * and re-enter it by hand" the sole route to correcting a category.
 */
/** Confirms the last Alert.alert(...) call and runs its destructive button's onPress. Mirrors
 *  AccountsScreen.test.tsx's identical helper -- same shape of confirm-then-delete flow. */
async function confirmLastAlert(alertSpy: jest.SpyInstance, label: string) {
  const buttons = alertSpy.mock.calls.at(-1)?.[2] as { text: string; onPress?: () => void }[];
  await act(async () => { buttons.find((b) => b.text === label)!.onPress!(); });
}

/**
 * Bug found in review: the sheet's own "Delete Transaction" row used to close the sheet the
 * instant Delete was confirmed, in the exact same render deletingId first became true -- so the
 * row's own "Deleting…" state (the `deleting` prop TransactionDetailSheet shows a spinner for)
 * could never actually be observed, since the component holding it was already unmounted. Fixed
 * to defer closing until handleDelete settles. These tests exercise that through the real
 * LedgerScreen wiring, not just TransactionDetailSheet's own isolated unit tests -- there was
 * previously no test anywhere in this app that drove a transaction delete to completion.
 */
describe('deleting a transaction from the detail sheet', () => {
  it('confirms before deleting, naming the transaction', async () => {
    const alertSpy = jest.spyOn(Alert, 'alert').mockImplementation(() => {});
    transactions.search.mockResolvedValue(page([txn()]) as never);

    renderScreen();
    fireEvent.press(await screen.findByText('Grocery run'));
    fireEvent.press(screen.getByTestId('delete-button-t-1'));

    expect(alertSpy).toHaveBeenCalledWith(
      'Delete transaction?',
      expect.stringContaining('Grocery run'),
      expect.anything()
    );
    expect(transactions.remove).not.toHaveBeenCalled();
    alertSpy.mockRestore();
  });

  it('keeps the sheet open, showing the row disabled, while the delete is in flight', async () => {
    const alertSpy = jest.spyOn(Alert, 'alert').mockImplementation(() => {});
    let resolveRemove: () => void = () => {};
    transactions.search.mockResolvedValue(page([txn()]) as never);
    transactions.remove.mockReturnValue(new Promise((resolve) => { resolveRemove = () => resolve(undefined as never); }));

    renderScreen();
    fireEvent.press(await screen.findByText('Grocery run'));
    fireEvent.press(screen.getByTestId('delete-button-t-1'));
    await confirmLastAlert(alertSpy, 'Delete');

    // Still open -- closing here (before the request settles) is exactly the bug this test
    // guards: it would make the in-flight state unobservable.
    const row = screen.getByTestId('delete-button-t-1');
    expect(row.props.accessibilityState?.disabled ?? row.props.disabled).toBeTruthy();

    // Flaky in CI: without wrapping this resolution in act(), the state updates it triggers
    // (deletingId reset, then viewingDetail closed once handleDelete's returned promise settles)
    // aren't guaranteed to flush before waitFor's own timeout on a slower/more loaded CI runner --
    // it passed reliably here locally but failed there. The sibling unmark test below already
    // wraps its own resolution in act() for the identical reason; this one just hadn't matched it.
    await act(async () => resolveRemove());
    await waitFor(() => expect(screen.queryByTestId('delete-button-t-1')).toBeNull());
    alertSpy.mockRestore();
  });

  it('removes the transaction and closes the sheet once the destructive confirmation settles', async () => {
    const alertSpy = jest.spyOn(Alert, 'alert').mockImplementation(() => {});
    transactions.search.mockResolvedValue(page([txn()]) as never);
    transactions.remove.mockResolvedValue(undefined as never);

    renderScreen();
    fireEvent.press(await screen.findByText('Grocery run'));
    fireEvent.press(screen.getByTestId('delete-button-t-1'));
    await confirmLastAlert(alertSpy, 'Delete');

    await waitFor(() => expect(transactions.remove).toHaveBeenCalledWith('t-1'));
    expect(invalidateFinancialData).toHaveBeenCalled();
    await waitFor(() => expect(screen.queryByText('Transaction Details')).toBeNull());
    alertSpy.mockRestore();
  });

  it('says so and keeps the sheet closed but the row on screen when the delete fails', async () => {
    const alertSpy = jest.spyOn(Alert, 'alert').mockImplementation(() => {});
    transactions.search.mockResolvedValue(page([txn()]) as never);
    transactions.remove.mockRejectedValue(new Error('network'));

    renderScreen();
    fireEvent.press(await screen.findByText('Grocery run'));
    fireEvent.press(screen.getByTestId('delete-button-t-1'));
    await confirmLastAlert(alertSpy, 'Delete');

    expect(await screen.findByText(/Could not delete this transaction/i)).toBeTruthy();
    expect(screen.getByText('Grocery run')).toBeTruthy();
    expect(screen.queryByText('Transaction Details')).toBeNull();
    alertSpy.mockRestore();
  });
});

describe('correcting a category from the ledger', () => {
  it('opens the picker from the detail sheet, seeded with the category the row has now', async () => {
    transactions.search.mockResolvedValue(page([txn({ categoryName: 'Food' })]) as never);

    renderScreen();
    fireEvent.press(await screen.findByText('Grocery run'));
    fireEvent.press(await screen.findByTestId('category-button-t-1'));

    // The sheet's own title, not the row's -- proves the picker itself opened.
    expect(await screen.findByText('Change category')).toBeTruthy();
    expect(screen.getByText('Travel')).toBeTruthy();
  });

  it('saves the picked category and refreshes the figures it moves', async () => {
    transactions.search.mockResolvedValue(page([txn()]) as never);
    transactions.updateCategory.mockResolvedValue({} as never);

    renderScreen();
    fireEvent.press(await screen.findByText('Grocery run'));
    fireEvent.press(await screen.findByTestId('category-button-t-1'));
    fireEvent.press(await screen.findByText('Travel'));

    await waitFor(() => expect(transactions.updateCategory).toHaveBeenCalledWith('t-1', 'Travel'));
    // A category move changes spend-by-category, budget progress and insights -- none of which
    // this screen renders, and all of which would otherwise keep showing pre-edit figures.
    expect(invalidateFinancialData).toHaveBeenCalled();
  });

  it('does not call the API when the picked category is the one already set', async () => {
    transactions.search.mockResolvedValue(page([txn({ categoryName: 'Food' })]) as never);

    renderScreen();
    fireEvent.press(await screen.findByText('Grocery run'));
    fireEvent.press(await screen.findByTestId('category-button-t-1'));
    fireEvent.press(await screen.findByText('Food'));

    await waitFor(() => expect(screen.queryByText('Change category')).toBeNull());
    expect(transactions.updateCategory).not.toHaveBeenCalled();
  });

  it('says so when the save fails, rather than appearing to have worked', async () => {
    transactions.search.mockResolvedValue(page([txn()]) as never);
    transactions.updateCategory.mockRejectedValue(new Error('nope'));

    renderScreen();
    fireEvent.press(await screen.findByText('Grocery run'));
    fireEvent.press(await screen.findByTestId('category-button-t-1'));
    fireEvent.press(await screen.findByText('Travel'));

    expect(await screen.findByText(/Could not change this category/i)).toBeTruthy();
  });

  it('does not drop a correction to one row while another row is still saving', async () => {
    // Regression: a global useSingleFlight guard here serialized every save through one ref, so
    // fixing row B while row A's request was in flight silently did nothing at all -- no write, no
    // error, the old category still on screen. Rows are independent actions, not one submit button.
    let releaseFirst: (v: unknown) => void = () => {};
    transactions.search.mockResolvedValue(
      page([txn({ id: 't-1' }), txn({ id: 't-2', description: 'Fuel top-up' })]) as never);
    transactions.updateCategory
      .mockImplementationOnce(() => new Promise((resolve) => { releaseFirst = resolve; }) as never)
      .mockResolvedValueOnce({} as never);

    renderScreen();

    fireEvent.press(await screen.findByText('Grocery run'));
    fireEvent.press(await screen.findByTestId('category-button-t-1'));
    fireEvent.press(await screen.findByText('Travel'));

    // First request deliberately still hanging.
    fireEvent.press(await screen.findByText('Fuel top-up'));
    fireEvent.press(await screen.findByTestId('category-button-t-2'));
    fireEvent.press(await screen.findByText('Food'));

    await waitFor(() => expect(transactions.updateCategory).toHaveBeenCalledWith('t-2', 'Food'));

    releaseFirst({});

    // Assert an outcome, not the call count -- that count was already satisfied before the release
    // above, so alone it pinned nothing. Both saves must reach invalidateFinancialData; a guard
    // that dropped the second would leave only one.
    await waitFor(() => expect(invalidateFinancialData).toHaveBeenCalledTimes(2));
  });

  it('still offers delete: tap and long-press stay different actions on the same row', async () => {
    transactions.search.mockResolvedValue(page([txn()]) as never);

    renderScreen();
    fireEvent(await screen.findByText('Grocery run'), 'longPress');

    // The detail sheet must NOT have opened -- a long-press that also fired the tap handler would
    // put a delete confirmation on top of (or under) it.
    expect(screen.queryByText('Transaction Details')).toBeNull();
    expect(hapticImpact).toHaveBeenCalled();
  });
});

/**
 * Track C/C4: a drill-through arriving from a donut legend row, a budget card, an insight/mover
 * row, or a report's category breakdown. This tab stays mounted like every other one, so the
 * nonce/re-arrival tests below matter for the same reason ImportScreen's own reimport-arrival
 * tests do -- a second drill-through must be told apart from the first still sitting in state.
 */
describe('drill-through filters (Track C/C4)', () => {
  function filters(over: Partial<LedgerDrillThroughFilters> = {}): LedgerDrillThroughFilters {
    return { label: 'Dining', nonce: 1, ...over };
  }

  beforeEach(() => {
    transactions.search.mockResolvedValue(page([]) as never);
  });

  it('applies an incoming categoryId directly, with no lookup needed', async () => {
    mockRouteParams = { filters: filters({ categoryId: 'c-9' }) };

    renderScreen();

    await waitFor(() => expect(transactions.search).toHaveBeenCalledWith(
      expect.objectContaining({ categoryId: 'c-9' })
    ));
  });

  // Track C/C6: ImportScreen's "View in Ledger" is the one caller that sets this.
  it('applies an incoming accountId, alongside a category or on its own', async () => {
    mockRouteParams = { filters: filters({ accountId: 'acct-1', categoryId: undefined, label: 'HDFC Savings' }) };

    renderScreen();

    await waitFor(() => expect(transactions.search).toHaveBeenCalledWith(
      expect.objectContaining({ accountId: 'acct-1' })
    ));
  });

  it('resolves a categoryName against the category list already fetched for the picker', async () => {
    mockRouteParams = { filters: filters({ categoryName: 'Travel' }) };

    renderScreen();

    await waitFor(() => expect(transactions.search).toHaveBeenCalledWith(
      expect.objectContaining({ categoryId: 'c-2' })
    ));
  });

  // A category renamed or deleted since the caller last saw it -- degrades to no category filter
  // rather than a search built from an id that doesn't exist, which could never match anything.
  it('drops the category filter rather than search for an unresolvable name', async () => {
    mockRouteParams = { filters: filters({ categoryName: 'Nonexistent', dateFrom: '2026-08-01', dateTo: '2026-08-31' }) };

    renderScreen();

    await waitFor(() => expect(transactions.search).toHaveBeenCalledWith(
      expect.objectContaining({ categoryId: undefined, dateFrom: '2026-08-01', dateTo: '2026-08-31' })
    ));
  });

  it('applies the date range and shows the active filter so the result is not a silent mystery', async () => {
    mockRouteParams = { filters: filters({ categoryId: 'c-1', dateFrom: '2026-08-01', dateTo: '2026-08-31', label: 'Food · August 2026' }) };

    renderScreen();

    await waitFor(() => expect(transactions.search).toHaveBeenCalledWith(
      expect.objectContaining({ categoryId: 'c-1', dateFrom: '2026-08-01', dateTo: '2026-08-31' })
    ));
    expect(await screen.findByText('Food · August 2026')).toBeTruthy();
  });

  it('clears the filter on request and searches again without it', async () => {
    mockRouteParams = { filters: filters({ categoryId: 'c-1', label: 'Food' }) };
    renderScreen();
    await screen.findByText('Food');
    transactions.search.mockClear();

    fireEvent.press(screen.getByLabelText('Clear filter: Food'));

    expect(screen.queryByText('Food')).toBeNull();
    await waitFor(() => expect(transactions.search).toHaveBeenCalledWith(
      expect.objectContaining({ categoryId: undefined })
    ));
  });

  // Regression: clearing a keyword-only drill-through (Insights' Top Merchant) used to only
  // dismiss the banner -- the search box and the actual results stayed silently narrowed to the
  // merchant, looking cleared while still filtering.
  it('clears the seeded keyword too when a keyword-only drill-through is cleared', async () => {
    mockRouteParams = { filters: filters({ keyword: 'Myntra', label: 'Myntra' }) };
    renderScreen();
    await screen.findByDisplayValue('Myntra');
    transactions.search.mockClear();

    fireEvent.press(screen.getByLabelText('Clear filter: Myntra'));

    expect(screen.queryByDisplayValue('Myntra')).toBeNull();
    await waitFor(() => expect(transactions.search).toHaveBeenCalledWith(
      expect.objectContaining({ keyword: undefined })
    ));
  });

  it('leaves a keyword the user typed over the seeded one alone when the drill-through is cleared', async () => {
    mockRouteParams = { filters: filters({ keyword: 'Myntra', label: 'Myntra' }) };
    renderScreen();
    await screen.findByDisplayValue('Myntra');
    fireEvent.changeText(screen.getByLabelText('Search transactions'), 'Amazon');

    fireEvent.press(screen.getByLabelText('Clear filter: Myntra'));

    expect(screen.getByDisplayValue('Amazon')).toBeTruthy();
  });

  // The tab stays mounted (React Navigation's default), so its local state survives a visit to
  // History and back -- the nonce is what tells a genuinely new arrival apart from the same old
  // params still sitting in route.params.filters.
  it('recognises a second drill-through even though the tab never unmounted between the two', async () => {
    mockRouteParams = { filters: filters({ categoryId: 'c-1', label: 'Food', nonce: 1 }) };
    const view = renderScreen();
    await screen.findByText('Food');

    mockRouteParams = { filters: filters({ categoryId: 'c-2', label: 'Travel', nonce: 2 }) };
    view.rerender(
      <QueryClientProvider client={new QueryClient({ defaultOptions: { queries: { retry: false, gcTime: 0 } } })}>
        <LedgerScreen />
      </QueryClientProvider>
    );

    expect(await screen.findByText('Travel')).toBeTruthy();
    expect(screen.queryByText('Food')).toBeNull();
  });

  // Insights' "Top Merchant" row -- a merchant isn't a category, so this is the one drill-through
  // that has to reach the search box directly rather than the activeDrillThrough-derived filters.
  it('seeds the search box from an incoming keyword-only drill-through', async () => {
    mockRouteParams = { filters: filters({ keyword: 'Myntra', label: 'Myntra' }) };
    renderScreen();

    await waitFor(() => expect(transactions.search).toHaveBeenCalledWith(
      expect.objectContaining({ keyword: 'Myntra' })
    ));
    expect(screen.getByDisplayValue('Myntra')).toBeTruthy();
  });

  // React's own render-phase-update mechanism (the pattern this file already uses for
  // activeDrillThrough/manualDateFrom/manualDateTo, and now for drillThroughKeyword too) discards
  // the stale-state render before it ever paints -- confirmed directly, not assumed: mocking a
  // DIFFERENT search result per keyword shows only the correctly-filtered row ever reaches the
  // screen. A background query for the stale, pre-correction filters can still fire (TanStack
  // Query's observer isn't tied to React's commit timing the way an effect is), but that's an
  // extra, wasted request, not a wrong answer the user ever sees -- the same pre-existing
  // trade-off every OTHER drill-through field on this screen (category/account/date) already
  // accepts, not something this keyword addition makes worse.
  it('never shows the unfiltered result set on screen, even transiently, once a keyword drill-through arrives', async () => {
    mockRouteParams = { filters: filters({ keyword: 'Myntra', label: 'Myntra' }) };
    transactions.search.mockImplementation((args: any) =>
      Promise.resolve(args?.keyword === 'Myntra'
        ? page([txn({ id: 't-myntra', description: 'Myntra order' })])
        : page([txn({ id: 't-other', description: 'Unrelated row' })])) as never);

    renderScreen();
    await screen.findByText('Myntra order');
    expect(screen.queryByText('Unrelated row')).toBeNull();
  });

  it('clears a keyword left over from an earlier drill-through when a new, keyword-less one arrives', async () => {
    mockRouteParams = { filters: filters({ keyword: 'Myntra', label: 'Myntra', nonce: 1 }) };
    const view = renderScreen();
    await waitFor(() => expect(transactions.search).toHaveBeenCalledWith(
      expect.objectContaining({ keyword: 'Myntra' })
    ));

    mockRouteParams = { filters: filters({ categoryId: 'c-1', label: 'Food', nonce: 2 }) };
    view.rerender(
      <QueryClientProvider client={new QueryClient({ defaultOptions: { queries: { retry: false, gcTime: 0 } } })}>
        <LedgerScreen />
      </QueryClientProvider>
    );

    await waitFor(() => expect(transactions.search).toHaveBeenCalledWith(
      expect.objectContaining({ categoryId: 'c-1', keyword: undefined })
    ));
    expect(screen.queryByDisplayValue('Myntra')).toBeNull();
  });
});

/**
 * Phase 5 (Low-Priority Polish). A manual pick, independent of the drill-through's own dateFrom/
 * dateTo tested above -- that one arrives already scoped FROM another screen; this is the user
 * picking a range by hand on the Ledger itself, which previously had no control for it at all.
 * DateField's real picker only has a testable path on Android in this suite (the iOS branch
 * renders an inline @react-native-community/datetimepicker mocked to `null`) -- same
 * Platform.OS-mutation convention AppleSignInButton.test.tsx already established for the reverse
 * case.
 */
describe('manual date-range filter (Phase 5)', () => {
  const originalOS = Platform.OS;

  beforeEach(() => {
    Platform.OS = 'android';
    transactions.search.mockResolvedValue(page([]) as never);
  });

  afterEach(() => {
    Platform.OS = originalOS;
  });

  it('sends the picked From date to the search, alongside whatever To is already set', async () => {
    renderScreen();
    await screen.findByText(/No transactions yet/i);
    transactions.search.mockClear();

    jest.mocked(DateTimePickerAndroid.open).mockImplementation(({ onChange }) => {
      onChange?.({ type: 'set' } as never, new Date(2026, 6, 1)); // July 1, 2026 local
    });
    fireEvent.press(screen.getByLabelText(/From: not set\. Choose a date/));

    await waitFor(() => expect(transactions.search).toHaveBeenCalledWith(
      expect.objectContaining({ dateFrom: '2026-07-01' })
    ));
  });

  it('wins over an incoming drill-through\'s own date range once picked', async () => {
    mockRouteParams = {
      filters: { label: 'August 2026', nonce: 1, dateFrom: '2026-08-01', dateTo: '2026-08-31' },
    };
    renderScreen();
    await screen.findByText(/No transactions match these filters/i);
    expect(transactions.search).toHaveBeenCalledWith(
      expect.objectContaining({ dateFrom: '2026-08-01' })
    );
    transactions.search.mockClear();

    jest.mocked(DateTimePickerAndroid.open).mockImplementation(({ onChange }) => {
      onChange?.({ type: 'set' } as never, new Date(2026, 6, 15)); // July 15, 2026 local
    });
    fireEvent.press(screen.getByLabelText(/From: not set\. Choose a date/));

    await waitFor(() => expect(transactions.search).toHaveBeenCalledWith(
      expect.objectContaining({ dateFrom: '2026-07-15' })
    ));
  });

  it('has nothing to clear before a pick is made', async () => {
    renderScreen();
    await screen.findByText(/No transactions yet/i);

    expect(screen.queryByLabelText('Clear From')).toBeNull();
    expect(screen.queryByLabelText('Clear To')).toBeNull();
  });

  // Bug fix: a manual pick used to survive a brand new drill-through arriving later on this
  // still-mounted tab (the nonce pattern from the drill-through describe block above), since
  // manualDateFrom/manualDateTo won over activeDrillThrough's own dates unconditionally with no
  // reset on a new arrival. The banner would show the new drill-through's label while the actual
  // search silently stayed scoped to the stale manual range from a previous, unrelated visit.
  it('is cleared by a brand new drill-through arriving later on this still-mounted tab', async () => {
    mockRouteParams = {
      filters: { label: 'August 2026', nonce: 1, dateFrom: '2026-08-01', dateTo: '2026-08-31' },
    };
    const view = renderScreen();
    await screen.findByText(/No transactions match these filters/i);

    jest.mocked(DateTimePickerAndroid.open).mockImplementation(({ onChange }) => {
      onChange?.({ type: 'set' } as never, new Date(2026, 6, 15)); // July 15, 2026 local
    });
    fireEvent.press(screen.getByLabelText(/From: not set\. Choose a date/));
    await waitFor(() => expect(transactions.search).toHaveBeenCalledWith(
      expect.objectContaining({ dateFrom: '2026-07-15' })
    ));
    transactions.search.mockClear();

    mockRouteParams = {
      filters: { label: 'September 2026', nonce: 2, dateFrom: '2026-09-01', dateTo: '2026-09-30' },
    };
    view.rerender(
      <QueryClientProvider client={new QueryClient({ defaultOptions: { queries: { retry: false, gcTime: 0 } } })}>
        <LedgerScreen />
      </QueryClientProvider>
    );

    expect(await screen.findByText('September 2026')).toBeTruthy();
    await waitFor(() => expect(transactions.search).toHaveBeenCalledWith(
      expect.objectContaining({ dateFrom: '2026-09-01', dateTo: '2026-09-30' })
    ));
    expect(screen.getByLabelText(/From: not set\. Choose a date/)).toBeTruthy();
  });
});

describe('"Where this came from" panel (Track C/C7)', () => {
  it('opens the source panel from the detail sheet', async () => {
    transactions.search.mockResolvedValue(page([txn()]) as never);
    transactions.source.mockResolvedValue({
      available: true, sourceLabel: 'CSV_IMPORT', statementDeleted: false, statementImportId: 'si-1',
      fileName: 'march-statement.pdf', rowPosition: 14, importedAt: '2026-08-15T10:00:00Z',
      accountName: 'HDFC Savings', statementPeriodStart: '2026-03-01', statementPeriodEnd: '2026-03-31',
    } as never);

    renderScreen();
    fireEvent.press(await screen.findByText('Grocery run'));
    fireEvent.press(await screen.findByTestId('source-button-t-1'));

    expect(await screen.findByText('march-statement.pdf')).toBeTruthy();
    expect(transactions.source).toHaveBeenCalledWith('t-1');
  });

  it('closes without affecting the row underneath', async () => {
    transactions.search.mockResolvedValue(page([txn()]) as never);
    transactions.source.mockResolvedValue({
      available: false, sourceLabel: 'MANUAL', statementDeleted: false, statementImportId: null, fileName: null,
      rowPosition: null, importedAt: null, accountName: null,
      statementPeriodStart: null, statementPeriodEnd: null,
    } as never);

    renderScreen();
    fireEvent.press(await screen.findByText('Grocery run'));
    fireEvent.press(await screen.findByTestId('source-button-t-1'));
    expect(await screen.findByText('You entered this transaction yourself.')).toBeTruthy();

    fireEvent.press(screen.getByText('Close'));

    await waitFor(() => expect(screen.queryByText('You entered this transaction yourself.')).toBeNull());
    expect(screen.getByText('Grocery run')).toBeTruthy();
  });
});

/**
 * "Why this category?" (Phase 4/Medium-Tier Parity). transactionsApi.explanation already computed
 * the full categorization AND reconciliation reasoning server-side -- this was simply never
 * rendered anywhere on mobile, so the category chip and reconciliationBadge pill were both static
 * labels with no way to ask "why". Same coverage shape as the "Where this came from" panel above:
 * opens without triggering the row's own onPress, closes cleanly, reachable via a screen-reader
 * accessibility action.
 */
describe('"Why this category?" panel (Phase 4)', () => {
  it('opens the explanation from the detail sheet', async () => {
    transactions.search.mockResolvedValue(page([txn({ categoryName: 'Food' })]) as never);
    transactions.explanation.mockResolvedValue({
      decisionSource: 'RULE', summary: 'Matched your rule for "Big Bazaar".', evidence: ['Rule created 2026-05-01'],
    } as never);

    renderScreen();
    fireEvent.press(await screen.findByText('Grocery run'));
    fireEvent.press(await screen.findByTestId('explain-button-t-1'));

    expect(await screen.findByText('Matched your rule for "Big Bazaar".')).toBeTruthy();
    expect(transactions.explanation).toHaveBeenCalledWith('t-1');
  });

  it('shows the row\'s own category as context, and the confidence when the source has one', async () => {
    transactions.search.mockResolvedValue(page([txn({ categoryName: 'Food' })]) as never);
    transactions.explanation.mockResolvedValue({
      decisionSource: 'LEARNED', summary: 'You corrected this merchant to Food before.', evidence: [],
      confidence: 87,
    } as never);

    renderScreen();
    fireEvent.press(await screen.findByText('Grocery run'));
    fireEvent.press(await screen.findByTestId('explain-button-t-1'));

    await screen.findByText('You corrected this merchant to Food before.');
    expect(screen.getByText('Food')).toBeTruthy();
    expect(screen.getByText('87% confidence')).toBeTruthy();
  });

  // The reconciliation section only exists for a row something actually matched (reconciliation
  // status other than OK) -- the overwhelming majority of rows have nothing here to explain.
  it('shows the reconciliation match, badged, above the categorization answer', async () => {
    transactions.search.mockResolvedValue(page([txn({ categoryName: 'Food', reconciliationStatus: 'DUPLICATE' })]) as never);
    transactions.explanation.mockResolvedValue({
      decisionSource: 'RULE', summary: 'Matched your rule for "Big Bazaar".', evidence: [],
      reconciliation: {
        status: 'DUPLICATE', matchedTransactionId: 't-9',
        summary: 'Matches a transaction imported on 2026-07-10.', evidence: ['Same date, amount and description'],
      },
    } as never);

    renderScreen();
    fireEvent.press(await screen.findByText('Grocery run'));
    fireEvent.press(await screen.findByTestId('explain-button-t-1'));

    // Not 'Duplicate' as the first assertion -- the row's own reconciliationBadge pill already
    // renders that text before the modal's own query even resolves, so waiting on it alone would
    // prove nothing about the modal. Wait on text only the modal's loaded content has, first.
    await screen.findByText('Matches a transaction imported on 2026-07-10.');
    // Not an exact match -- the bullet renders as its own text node inside the same <Text>, so the
    // element's full text content is "• Same date, amount and description".
    expect(screen.getByText(/Same date, amount and description/)).toBeTruthy();
    // Three, not two: the status filter chip (Phase 4), the row's own pill, and the modal's own
    // badge for the same status -- the detail sheet's own pill for the same status is not among
    // these three, since opening this modal already closed that sheet.
    expect(screen.getAllByText('Duplicate')).toHaveLength(3);
  });

  it('closes without affecting the row underneath', async () => {
    transactions.search.mockResolvedValue(page([txn({ categoryName: 'Food' })]) as never);
    transactions.explanation.mockResolvedValue({
      decisionSource: 'RULE', summary: 'Matched your rule for "Big Bazaar".', evidence: [],
    } as never);

    renderScreen();
    fireEvent.press(await screen.findByText('Grocery run'));
    fireEvent.press(await screen.findByTestId('explain-button-t-1'));
    expect(await screen.findByText('Matched your rule for "Big Bazaar".')).toBeTruthy();

    fireEvent.press(screen.getByText('Close'));

    await waitFor(() => expect(screen.queryByText('Matched your rule for "Big Bazaar".')).toBeNull());
    expect(screen.getByText('Grocery run')).toBeTruthy();
  });

  it('says so rather than nothing when the explanation fails to load', async () => {
    transactions.search.mockResolvedValue(page([txn({ categoryName: 'Food' })]) as never);
    transactions.explanation.mockRejectedValue(new Error('boom'));

    renderScreen();
    fireEvent.press(await screen.findByText('Grocery run'));
    fireEvent.press(await screen.findByTestId('explain-button-t-1'));

    expect(await screen.findByText("Couldn't load this explanation.")).toBeTruthy();
  });
});

/**
 * Plan 6, Track B mobile parity. The icon button only renders when pendingBankCorrection is true
 * -- unlike the always-present source/edit/explain buttons -- since it's an exception state, not a
 * routine action every row offers. Same reachability shape as the panels above: opens without
 * triggering the row's own onPress, reachable via a dedicated accessibilityAction for a
 * screen-reader user.
 */
describe('Bank Correction detail (Plan 6, Track B mobile parity)', () => {
  it('shows the action row in the detail sheet only when the transaction is flagged', async () => {
    transactions.search.mockResolvedValue(page([txn({ pendingBankCorrection: true })]) as never);

    renderScreen();
    fireEvent.press(await screen.findByText('Grocery run'));

    expect(await screen.findByTestId('bank-correction-button-t-1')).toBeTruthy();
  });

  it('does not show the action row for an ordinary transaction', async () => {
    transactions.search.mockResolvedValue(page([txn()]) as never);

    renderScreen();
    fireEvent.press(await screen.findByText('Grocery run'));

    await screen.findByText('Transaction Details');
    expect(screen.queryByTestId('bank-correction-button-t-1')).toBeNull();
  });

  it('opens the correction detail from the detail sheet', async () => {
    transactions.search.mockResolvedValue(page([txn({ pendingBankCorrection: true })]) as never);
    transactions.correctionHistory.mockResolvedValue([
      { action: 'ACCOUNT_AGGREGATOR_TRANSACTION_CORRECTED', metadata: { previousAmount: 500, newAmount: 700 }, createdAt: '2026-09-14T00:00:00Z' },
    ] as never);

    renderScreen();
    fireEvent.press(await screen.findByText('Grocery run'));
    fireEvent.press(await screen.findByTestId('bank-correction-button-t-1'));

    expect(await screen.findByText('Bank reported a different value')).toBeTruthy();
    expect(transactions.correctionHistory).toHaveBeenCalledWith('t-1');
  });
});

// D3 (Track D security cleanup). Transaction descriptions/amounts are as screenshot-attractive
// as anything on the Dashboard or Accounts screen, which already guard against this.
describe('screen capture protection (Track D/D3)', () => {
  it('calls usePreventScreenCapture on mount', () => {
    transactions.search.mockResolvedValue(page([]) as never);

    renderScreen();

    expect(usePreventScreenCapture).toHaveBeenCalled();
  });
});

describe('getting-started checklist dwell timer', () => {
  beforeEach(() => {
    transactions.search.mockResolvedValue(page([]) as never);
  });

  it('marks REVIEW_TRANSACTIONS complete after a 1.5s dwell', async () => {
    jest.useFakeTimers({ doNotFake: ['queueMicrotask'] });
    (onboardingApi.getChecklist as jest.Mock).mockResolvedValue({
      items: [{ key: 'REVIEW_TRANSACTIONS', completed: false }], completedCount: 0, totalCount: 6,
    });

    renderScreen();

    await act(async () => { await jest.advanceTimersByTimeAsync(0); });
    await act(async () => { await jest.advanceTimersByTimeAsync(1500); });

    expect(onboardingApi.completeChecklistItem).toHaveBeenCalledWith('REVIEW_TRANSACTIONS');
    jest.useRealTimers();
  });

  it('does not fire if the item is already complete', async () => {
    jest.useFakeTimers({ doNotFake: ['queueMicrotask'] });
    (onboardingApi.getChecklist as jest.Mock).mockResolvedValue({
      items: [{ key: 'REVIEW_TRANSACTIONS', completed: true }], completedCount: 1, totalCount: 6,
    });

    renderScreen();

    await act(async () => { await jest.advanceTimersByTimeAsync(0); });
    await act(async () => { await jest.advanceTimersByTimeAsync(1500); });

    expect(onboardingApi.completeChecklistItem).not.toHaveBeenCalled();
    jest.useRealTimers();
  });
});

describe('Add and Edit Transaction (Phase 1)', () => {
  it('opens Add Transaction from the header button', async () => {
    transactions.search.mockResolvedValue(page([]) as never);

    renderScreen();
    await screen.findByText('Transactions');
    fireEvent.press(screen.getByLabelText('Add transaction'));

    // The sheet's own title, not the header's -- proves the sheet itself opened.
    expect(await screen.findByText('Add Transaction')).toBeTruthy();
  });

  it('opens Edit Transaction from the detail sheet, seeded with that row\'s own fields', async () => {
    transactions.search.mockResolvedValue(page([txn({ categoryName: 'Food' })]) as never);

    renderScreen();
    fireEvent.press(await screen.findByText('Grocery run'));
    fireEvent.press(screen.getByTestId('edit-button-t-1'));

    expect(await screen.findByText('Edit Transaction')).toBeTruthy();
    expect(screen.getByLabelText('Description').props.value).toBe('Grocery run');
  });

  it('saves an edit and refreshes the figures it changed, without touching the quick-recategorize flow', async () => {
    transactions.search.mockResolvedValue(page([txn({ categoryName: 'Food' })]) as never);
    transactions.update.mockResolvedValue({} as never);

    renderScreen();
    fireEvent.press(await screen.findByText('Grocery run'));
    fireEvent.press(screen.getByTestId('edit-button-t-1'));
    await screen.findByText('Edit Transaction');

    fireEvent.changeText(screen.getByLabelText('Description'), 'Grocery run (corrected)');
    fireEvent.press(screen.getByRole('button', { name: /^Save Changes$/ }));
    await act(async () => {});

    await waitFor(() => expect(transactions.update).toHaveBeenCalledWith('t-1', expect.objectContaining({
      description: 'Grocery run (corrected)',
    })));
    expect(invalidateFinancialData).toHaveBeenCalled();
    // The row's own tap-to-recategorize path is untouched by this addition.
    expect(transactions.updateCategory).not.toHaveBeenCalled();
  });
});

describe('Mark / Unmark as transfer (Phase 6)', () => {
  it('offers Mark as transfer for an OK row, and finds+picks a candidate to pair it with', async () => {
    transactions.search.mockReset().mockImplementation(async (filters: any) =>
      filters?.keyword
        ? (page([txn({ id: 't-2', merchant: 'Savings Account', description: 'Own transfer', amount: 1299, type: 'INCOME' })]) as never)
        : (page([txn({ id: 't-1', reconciliationStatus: 'OK' })]) as never));
    transactions.markTransfer.mockResolvedValue(txn({ id: 't-1', reconciliationStatus: 'TRANSFER' }) as never);

    renderScreen();
    fireEvent.press(await screen.findByText('Grocery run'));
    fireEvent.press(screen.getByTestId('mark-transfer-button-t-1'));
    fireEvent.changeText(await screen.findByLabelText('Search transactions to pair with'), 'Savings');

    fireEvent.press(await screen.findByTestId('transfer-candidate-t-2'));

    await waitFor(() => expect(transactions.markTransfer).toHaveBeenCalledWith('t-1', 't-2'));
    expect(invalidateFinancialData).toHaveBeenCalled();
  });

  it('excludes the transaction itself and any already-paired transfer from the picker results', async () => {
    transactions.search.mockReset().mockImplementation(async (filters: any) =>
      filters?.keyword
        ? (page([
            txn({ id: 't-1', merchant: 'Self' }),
            txn({ id: 't-2', merchant: 'Already Paired', reconciliationStatus: 'TRANSFER' }),
            txn({ id: 't-3', merchant: 'Valid Candidate' }),
          ]) as never)
        : (page([txn({ id: 't-1', reconciliationStatus: 'OK' })]) as never));

    renderScreen();
    fireEvent.press(await screen.findByText('Grocery run'));
    fireEvent.press(screen.getByTestId('mark-transfer-button-t-1'));
    fireEvent.changeText(await screen.findByLabelText('Search transactions to pair with'), 'a');

    expect(await screen.findByTestId('transfer-candidate-t-3')).toBeTruthy();
    expect(screen.queryByTestId('transfer-candidate-t-1')).toBeNull();
    expect(screen.queryByTestId('transfer-candidate-t-2')).toBeNull();
  });

  it('offers Unmark as transfer, not Mark, for a row already at TRANSFER status', async () => {
    transactions.search.mockResolvedValue(page([txn({ reconciliationStatus: 'TRANSFER' })]) as never);

    renderScreen();
    fireEvent.press(await screen.findByText('Grocery run'));

    expect(screen.getByTestId('unmark-transfer-button-t-1')).toBeTruthy();
    expect(screen.queryByTestId('mark-transfer-button-t-1')).toBeNull();
  });

  it('calls unmarkTransfer when Unmark as transfer is pressed', async () => {
    transactions.search.mockResolvedValue(page([txn({ reconciliationStatus: 'TRANSFER' })]) as never);
    transactions.unmarkTransfer.mockResolvedValue(txn({ reconciliationStatus: 'OK' }) as never);

    renderScreen();
    fireEvent.press(await screen.findByText('Grocery run'));
    fireEvent.press(screen.getByTestId('unmark-transfer-button-t-1'));

    await waitFor(() => expect(transactions.unmarkTransfer).toHaveBeenCalledWith('t-1'));
    expect(invalidateFinancialData).toHaveBeenCalled();
  });

  // Same bug class as the delete flow's own timing test above: closing the sheet the instant
  // Unmark is pressed (rather than once the request settles) would make its own in-flight state
  // unobservable, since the component showing it would already be gone.
  it('keeps the sheet open, showing the row disabled, while the unmark is in flight, then closes it', async () => {
    let resolveUnmark: (v: unknown) => void = () => {};
    transactions.search.mockResolvedValue(page([txn({ reconciliationStatus: 'TRANSFER' })]) as never);
    transactions.unmarkTransfer.mockReturnValue(new Promise((resolve) => { resolveUnmark = resolve; }) as never);

    renderScreen();
    fireEvent.press(await screen.findByText('Grocery run'));
    fireEvent.press(screen.getByTestId('unmark-transfer-button-t-1'));

    await waitFor(() => expect(transactions.unmarkTransfer).toHaveBeenCalled());
    const row = screen.getByTestId('unmark-transfer-button-t-1');
    expect(row.props.accessibilityState?.disabled ?? row.props.disabled).toBeTruthy();

    await act(async () => resolveUnmark(txn({ reconciliationStatus: 'OK' }) as never));
    await waitFor(() => expect(screen.queryByTestId('unmark-transfer-button-t-1')).toBeNull());
  });

  it('offers neither Mark nor Unmark for a row already classified as something else, e.g. a duplicate', async () => {
    transactions.search.mockResolvedValue(page([txn({ reconciliationStatus: 'DUPLICATE' })]) as never);

    renderScreen();
    fireEvent.press(await screen.findByText('Grocery run'));

    expect(screen.queryByTestId('mark-transfer-button-t-1')).toBeNull();
    expect(screen.queryByTestId('unmark-transfer-button-t-1')).toBeNull();
  });
});

describe('LedgerScreen "This Month" summary', () => {
  it('shows Income and Expenses from the shared dashboard-summary query', async () => {
    transactions.search.mockResolvedValue(page([]) as never);
    dashboard.summary.mockResolvedValue({
      monthlyIncome: 145000, monthlyExpense: 18672, incomeDeltaPct: 12, expenseDeltaPct: -8,
      netCashFlow: 126328, netDeltaPct: 15, savingsRatePct: 87, currentBalance: 50000,
      reportingMonth: '2026-09', reportingMonthIsCurrent: true,
    } as never);
    renderScreen();

    expect(await screen.findByText('This Month')).toBeTruthy();
    expect(screen.getByTestId('kpi-Income')).toBeTruthy();
    expect(screen.getByTestId('kpi-Expenses')).toBeTruthy();
  });

  it('renders nothing extra while the summary is still loading', async () => {
    transactions.search.mockResolvedValue(page([]) as never);
    dashboard.summary.mockReturnValue(new Promise(() => {})); // never resolves
    renderScreen();
    await screen.findByText(/No transactions yet/i);
    expect(screen.queryByText('This Month')).toBeNull();
  });
});

describe('groupTransactionsByDay', () => {
  it('returns one header per distinct date, in the order the input already carries', () => {
    const rows = groupTransactionsByDay([
      txn({ id: 't1', date: '2026-09-10' }),
      txn({ id: 't2', date: '2026-09-10' }),
      txn({ id: 't3', date: '2026-09-08' }),
    ]);
    const headers = rows.filter((r) => r.kind === 'header');
    expect(headers.map((h) => (h as { date: string }).date)).toEqual(['2026-09-10', '2026-09-08']);
  });

  it("sums a day's subtotal as income minus expense, signed", () => {
    const rows = groupTransactionsByDay([
      txn({ id: 't1', date: '2026-09-10', type: 'INCOME', amount: 1000 }),
      txn({ id: 't2', date: '2026-09-10', type: 'EXPENSE', amount: 300 }),
    ]);
    const header = rows.find((r) => r.kind === 'header') as { subtotal: number };
    expect(header.subtotal).toBe(700);
  });

  it('returns an empty array for an empty input', () => {
    expect(groupTransactionsByDay([])).toEqual([]);
  });

  it('merges non-adjacent rows sharing the same date into one header, not two', () => {
    // The backend's own sort (TransactionService.java) orders by txnDate alone with no secondary
    // tiebreaker, so same-date rows are not guaranteed to stay contiguous. A grouping that only
    // starts a new header when the date differs from the row right before it would render the
    // same day twice, with a split (and wrong) subtotal on each half.
    const rows = groupTransactionsByDay([
      txn({ id: 't1', date: '2026-09-10', type: 'INCOME', amount: 1000 }),
      txn({ id: 't2', date: '2026-09-08', type: 'EXPENSE', amount: 200 }),
      txn({ id: 't3', date: '2026-09-10', type: 'EXPENSE', amount: 300 }),
    ]);
    const headers = rows.filter((r) => r.kind === 'header') as { date: string; subtotal: number }[];
    expect(headers.map((h) => h.date)).toEqual(['2026-09-10', '2026-09-08']);
    expect(headers.find((h) => h.date === '2026-09-10')?.subtotal).toBe(700);
    // Both 09-10 rows sit under the single 09-10 header, not split across two.
    const rowsUnderFirstHeader = rows.slice(0, rows.findIndex((r) => r.kind === 'header' && r.date === '2026-09-08'));
    expect(rowsUnderFirstHeader.filter((r) => r.kind === 'row')).toHaveLength(2);
  });

  it('labels a row dated today as "Today" and yesterday as "Yesterday", against a supplied clock', () => {
    const rows = groupTransactionsByDay(
      [
        txn({ id: 't1', date: '2026-09-12' }),
        txn({ id: 't2', date: '2026-09-11' }),
        txn({ id: 't3', date: '2026-09-01' }),
      ],
      new Date('2026-09-12T15:00:00')
    );
    const headers = rows.filter((r) => r.kind === 'header') as { date: string; label: string }[];
    // en-IN's short-month form for September is "Sept", not "Sep" -- matches the same
    // toLocaleDateString('en-IN', { month: 'short' }) convention already used elsewhere in this
    // app (DashboardScreen.tsx, GmailReviewScreen.tsx).
    expect(headers.map((h) => h.label)).toEqual(['Today', 'Yesterday', 'Tuesday, 1 Sept 2026']);
  });

  it("relabels the same rows correctly once the caller's clock advances a day", () => {
    // Guards the staleness bug this function's `today` parameter exists to prevent: a caller that
    // always reuses a stale clock would keep calling the row dated 2026-09-12 "Today" forever.
    const txns = [txn({ id: 't1', date: '2026-09-12' })];
    const day1 = groupTransactionsByDay(txns, new Date('2026-09-12T09:00:00'));
    const day2 = groupTransactionsByDay(txns, new Date('2026-09-13T09:00:00'));
    expect((day1[0] as { label: string }).label).toBe('Today');
    expect((day2[0] as { label: string }).label).toBe('Yesterday');
  });

  it('excludes a DUPLICATE-flagged row from the day subtotal', () => {
    // The default status filter is 'ALL' (see LedgerScreen's own statusFilter state), so a
    // DUPLICATE row is ordinarily present in `txns` alongside everything else, shown inline with
    // its own badge -- not hidden the way DashboardService/ReportService already hide it from
    // every total on the backend (RefundNetting.reportable). Summing it into the day header here
    // would double-count money the app itself has already flagged as the same real purchase
    // recorded twice, the identical defect RefundNetting exists to prevent on the backend -- just
    // one layer up, in a client-side aggregate the backend never computes or sees.
    const rows = groupTransactionsByDay([
      txn({ id: 't1', date: '2026-09-10', type: 'EXPENSE', amount: 300, reconciliationStatus: 'OK' }),
      txn({ id: 't2', date: '2026-09-10', type: 'EXPENSE', amount: 300, reconciliationStatus: 'DUPLICATE' }),
    ]);
    const header = rows.find((r) => r.kind === 'header') as { subtotal: number };
    expect(header.subtotal).toBe(-300);
  });

  it('excludes a SUPERSEDED row from the day subtotal', () => {
    // Same reasoning as the DUPLICATE case above -- a superseded row is the account's history
    // before a later re-upload of the exact same period replaced it (see
    // RefundNetting.reportable's own comment on SUPERSEDED), not a second real transaction.
    const rows = groupTransactionsByDay([
      txn({ id: 't1', date: '2026-09-10', type: 'EXPENSE', amount: 300, reconciliationStatus: 'OK' }),
      txn({ id: 't2', date: '2026-09-10', type: 'EXPENSE', amount: 300, reconciliationStatus: 'SUPERSEDED' }),
    ]);
    const header = rows.find((r) => r.kind === 'header') as { subtotal: number };
    expect(header.subtotal).toBe(-300);
  });

  it('still includes TRANSFER/REFUND/REVERSAL/INVESTMENT_TRANSFER rows in the day subtotal', () => {
    // Unlike DUPLICATE/SUPERSEDED, these represent real money that genuinely moved on this
    // account today -- the same convention Account.balance itself uses (see
    // RefundNetting.reportable's own class doc: those classifications only affect expense/income
    // REPORTING, not the balance, because the cash really moved). This pins the boundary so a
    // future change doesn't widen the exclusion by accident.
    const rows = groupTransactionsByDay([
      txn({ id: 't1', date: '2026-09-10', type: 'EXPENSE', amount: 100, reconciliationStatus: 'TRANSFER' }),
      txn({ id: 't2', date: '2026-09-10', type: 'EXPENSE', amount: 100, reconciliationStatus: 'REFUND' }),
      txn({ id: 't3', date: '2026-09-10', type: 'EXPENSE', amount: 100, reconciliationStatus: 'REVERSAL' }),
      txn({ id: 't4', date: '2026-09-10', type: 'EXPENSE', amount: 100, reconciliationStatus: 'INVESTMENT_TRANSFER' }),
    ]);
    const header = rows.find((r) => r.kind === 'header') as { subtotal: number };
    expect(header.subtotal).toBe(-400);
  });
});

describe('merchant logo on each row', () => {
  it('renders a MerchantLogo for each transaction row, keyed by merchant', async () => {
    transactions.search.mockResolvedValue(page([txn({ id: 't1', merchant: 'Swiggy' })]) as never);
    renderScreen();
    expect(await screen.findByLabelText('Swiggy')).toBeTruthy();
  });
});
