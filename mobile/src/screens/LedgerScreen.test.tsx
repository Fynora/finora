import { FlatList, Keyboard, Platform, RefreshControl, StyleSheet } from 'react-native';
import { AppAlert } from '../lib/appAlert';
import { act, fireEvent, render, screen, waitFor, within } from '@testing-library/react-native';
import { QueryClient, QueryClientProvider } from '@tanstack/react-query';
import { DateTimePickerAndroid } from '@react-native-community/datetimepicker';
import { usePreventScreenCapture } from '../lib/screenCapture';
import {
  DEFAULT_LEDGER_FILTERS, LEDGER_PAGE_SIZE, LedgerScreen, getLedgerNextPageParam, groupTransactionsByDay,
  ledgerPageRequest,
} from './LedgerScreen';
import { categoriesApi, dashboardApi, onboardingApi, transactionsApi } from '../api/endpoints';
import { expectHaptics } from '../test/haptics';
import { dayHeading } from '../lib/ledgerRows';
import { invalidateFinancialData } from '../lib/invalidateFinancialData';
import { ToastProvider } from '../context/ToastContext';
import type { LedgerDrillThroughFilters } from '../navigation/types';
import type { Transaction } from '../types';
import { useScrollToTop } from '@react-navigation/native';
import { expectSkeletonNotSpinner, never } from '../test/loadingStates';

// Controllable stand-in for useRoute, same pattern ImportScreen.test.tsx uses for its own
// reimport-arrival params (Track C/C4).
let mockRouteParams: { filters?: LedgerDrillThroughFilters } | undefined;
const mockNavigate = jest.fn();
jest.mock('@react-navigation/native', () => ({
  useRoute: () => ({ params: mockRouteParams }),
  useScrollToTop: jest.fn(),
  useNavigation: () => ({ navigate: mockNavigate }),
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
    // No other row from the payee unless a test says so: then a category change asks nothing.
    similar: jest.fn().mockResolvedValue({ similar: 0, keptByUser: 0 }),
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

// React Native's jest setup reports a font scale of 2, which is "large text" to this screen: rows
// lose their avatar and the controls stack. These tests describe the ordinary layout; the one that
// is about large text turns it on.
jest.mock('../lib/useLargeFontScale', () => ({ useLargeFontScale: jest.fn(() => false) }));
const { useLargeFontScale } = jest.requireMock('../lib/useLargeFontScale');

const transactions = transactionsApi as jest.Mocked<typeof transactionsApi>;
const categories = categoriesApi as jest.Mocked<typeof categoriesApi>;
const dashboard = dashboardApi as jest.Mocked<typeof dashboardApi>;

function txn(over: Partial<Transaction> = {}): Transaction {
  const description = over.description ?? 'Grocery run';
  return {
    id: 't-1',
    date: '2026-07-14',
    description,
    // As the server derives it: the description in lower case, punctuation gone, four words at
    // most. The row is titled from the two together (lib/ledgerRows rowTitle), so a test that sets
    // only `description` still finds it as the title.
    merchant: description.toLowerCase().replace(/[^a-z0-9]+/g, ' ').trim().split(' ').slice(0, 4).join(' '),
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
  // ToastProvider as App.tsx mounts it: the screen confirms a change, and reports a failed one, there.
  const result = render(
    <QueryClientProvider client={queryClient}>
      <ToastProvider>
        <LedgerScreen />
      </ToastProvider>
    </QueryClientProvider>
  );
  activeScreens.push(result);
  return result;
}

/** Status, date range and International live in the Filters sheet. */
function openFilters() {
  fireEvent.press(screen.getByLabelText(/^Filters/));
}

/** A scroll event with everything the list reads from one. */
function scrollListTo(y: number) {
  fireEvent.scroll(screen.getByTestId('ledger-list'), {
    nativeEvent: {
      contentOffset: { x: 0, y },
      contentSize: { width: 390, height: 4000 },
      layoutMeasurement: { width: 390, height: 800 },
    },
  });
}

beforeEach(() => {
  jest.clearAllMocks();
  useLargeFontScale.mockReturnValue(false);
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
describe('row tags', () => {
  it('shows no tag for an ordinary row: Categorized and Reviewed are the normal state', async () => {
    transactions.search.mockResolvedValue(page([txn(), txn({ id: 't-2', description: 'Salary', categoryManuallySet: true })]) as never);

    renderScreen();

    await screen.findByText('Grocery run');
    expect(screen.queryByText('Categorized')).toBeNull();
    expect(screen.queryByText('Reviewed')).toBeNull();
  });

  it('marks an international transaction, and says so to a screen reader', async () => {
    transactions.search.mockResolvedValue(page([
      txn({ id: 't-intl', international: true, foreignCurrency: 'USD', foreignAmount: 12.5 }),
      txn({ id: 't-home', description: 'Salary' }),
    ]) as never);

    renderScreen();

    expect(await screen.findByTestId('international-badge-t-intl')).toBeTruthy();
    expect(screen.queryByTestId('international-badge-t-home')).toBeNull();
    expect(screen.getByLabelText(/Grocery run.*, international$/)).toBeTruthy();
  });

  it('marks a card instalment, and reads out in full what the mark means', async () => {
    transactions.search.mockResolvedValue(page([
      txn({ id: 't-emi', description: 'FP EMI 02/06', cardInstalment: true }),
      txn({ id: 't-plain', description: 'Salary', cardInstalment: false }),
      // A server that predates the flag sends none: not marked.
      txn({ id: 't-old', description: 'Old row' }),
    ]) as never);

    renderScreen();

    expect(await screen.findByTestId('card-instalment-badge-t-emi')).toHaveTextContent('EMI instalment');
    expect(screen.queryByTestId('card-instalment-badge-t-plain')).toBeNull();
    expect(screen.queryByTestId('card-instalment-badge-t-old')).toBeNull();
    // The whole note, not the pill's two words: a listener has no details sheet open yet.
    expect(screen.getByLabelText(/^FP EMI 02\/06.*A monthly instalment on an earlier card purchase\..*count both the purchase and its instalments\.$/i)).toBeTruthy();
    expect(screen.getByLabelText(/^Salary/).props.accessibilityLabel).not.toMatch(/instalment/i);
  });

  it('shows Needs Review and Recurring at once: independent facts, not a priority chain', async () => {
    transactions.search.mockResolvedValue(page([txn({ needsCategoryReview: true, recurring: true })]) as never);

    renderScreen();

    await screen.findByText('Needs Review');
    expect(screen.getByText('Recurring')).toBeTruthy();
  });

  it('shows Bank Correction only when the bank changed the row', async () => {
    transactions.search.mockResolvedValue(page([
      txn({ pendingBankCorrection: true }), txn({ id: 't-2', description: 'Salary' }),
    ]) as never);

    renderScreen();

    expect(await screen.findAllByText('Bank Correction')).toHaveLength(1);
  });

  it("names every tag in the row's spoken name", async () => {
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
    const first = { content: [], page: 0, size: 20, totalElements: 40, totalPages: 2 };
    const last = { content: [], page: 1, size: 20, totalElements: 40, totalPages: 2 };
    expect(getLedgerNextPageParam(first, [first])).toBe(1);
    expect(getLedgerNextPageParam(last, [first, last])).toBeUndefined();
  });
});

/**
 * A page number counts rows from the top of the list. A transaction added or deleted on the server
 * between two page requests (an import finishing, an edit on another device) shifts every later
 * page by one row, so the next page repeated a row or stepped over one. The list now names the last
 * row it holds and the server continues from there.
 */
describe('paging continues from the last row held', () => {
  it('asks for the rows after the last one on screen when the list reaches its end', async () => {
    transactions.search
      .mockResolvedValueOnce(page(
        [txn({ id: 't-1', description: 'First row' }), txn({ id: 't-2', description: 'Second row' })],
        { totalPages: 2, totalElements: 3 },
      ) as never)
      .mockResolvedValueOnce(page([txn({ id: 't-3', description: 'Third row' })], { totalElements: 1 }) as never);

    renderScreen();
    await screen.findByText('Second row');
    fireEvent(screen.getByTestId('ledger-list'), 'onEndReached');

    expect(await screen.findByText('Third row')).toBeTruthy();
    // The page number still goes along: a server that does not know `after` pages by it.
    expect(transactions.search).toHaveBeenLastCalledWith({ ...DEFAULT_LEDGER_FILTERS, page: 1, after: 't-2' });
  });

  it('stops when the server says nothing is left after that row', async () => {
    transactions.search
      .mockResolvedValueOnce(page([txn({ id: 't-1' })], { totalPages: 2, totalElements: 2 }) as never)
      // Continued from a row, the server describes what is left: always page 0 of the remainder.
      .mockResolvedValueOnce(page([txn({ id: 't-2', description: 'Last row' })], { page: 0, totalPages: 1, totalElements: 1 }) as never);

    renderScreen();
    await screen.findByText('Grocery run');
    fireEvent(screen.getByTestId('ledger-list'), 'onEndReached');
    await screen.findByText('Last row');
    fireEvent(screen.getByTestId('ledger-list'), 'onEndReached');

    await waitFor(() => expect(transactions.search).toHaveBeenCalledTimes(2));
  });

  it('names the last row of the last page, and counts pages itself', () => {
    const p0 = { content: [txn({ id: 'a' }), txn({ id: 'b' })], page: 0, size: 2, totalElements: 6, totalPages: 3 };
    // What a server that understands `after` sends back for the second request.
    const rest = { content: [txn({ id: 'c' }), txn({ id: 'd' })], page: 0, size: 2, totalElements: 4, totalPages: 2 };
    const end = { content: [txn({ id: 'e' }), txn({ id: 'f' })], page: 0, size: 2, totalElements: 2, totalPages: 1 };

    expect(getLedgerNextPageParam(p0, [p0])).toEqual({ page: 1, after: 'b' });
    expect(getLedgerNextPageParam(rest, [p0, rest])).toEqual({ page: 2, after: 'd' });
    expect(getLedgerNextPageParam(end, [p0, rest, end])).toBeUndefined();
  });

  it('still works against a server that pages by number only', () => {
    const p0 = { content: [txn({ id: 'a' })], page: 0, size: 1, totalElements: 3, totalPages: 3 };
    const p1 = { content: [txn({ id: 'b' })], page: 1, size: 1, totalElements: 3, totalPages: 3 };
    const p2 = { content: [txn({ id: 'c' })], page: 2, size: 1, totalElements: 3, totalPages: 3 };

    expect(getLedgerNextPageParam(p0, [p0])).toEqual({ page: 1, after: 'a' });
    expect(getLedgerNextPageParam(p1, [p0, p1])).toEqual({ page: 2, after: 'b' });
    expect(getLedgerNextPageParam(p2, [p0, p1, p2])).toBeUndefined();
  });

  it('turns a page param into request params, including a bare number from an older saved cache', () => {
    expect(ledgerPageRequest(0)).toEqual({ page: 0 });
    expect(ledgerPageRequest(3)).toEqual({ page: 3 });
    expect(ledgerPageRequest({ page: 2, after: 'd' })).toEqual({ page: 2, after: 'd' });
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
    openFilters();
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
    openFilters();
    fireEvent.press(screen.getByLabelText('Filter by status: OK'));

    await waitFor(() => expect(transactions.search).toHaveBeenCalledWith(
      expect.objectContaining({ status: 'OK' })
    ));
  });

  it('filters to international transactions when the International chip is on, and back off again', async () => {
    transactions.search.mockResolvedValue(page([]) as never);

    renderScreen();
    await waitFor(() => expect(transactions.search).toHaveBeenCalledWith(
      expect.objectContaining({ international: undefined })
    ));
    openFilters();
    fireEvent.press(screen.getByLabelText('Show only international transactions'));
    await waitFor(() => expect(transactions.search).toHaveBeenCalledWith(
      expect.objectContaining({ international: true })
    ));
    expect(await screen.findByText('No transactions match these filters.')).toBeTruthy();

    fireEvent.press(screen.getByLabelText('Show only international transactions'));
    await waitFor(() => expect(transactions.search).toHaveBeenLastCalledWith(
      expect.objectContaining({ international: undefined })
    ));
  });

  it('clears the status filter when Any status is picked again', async () => {
    transactions.search.mockResolvedValue(page([]) as never);

    renderScreen();
    await waitFor(() => expect(transactions.search).toHaveBeenCalled());
    openFilters();
    fireEvent.press(screen.getByLabelText('Filter by status: Duplicate'));
    await waitFor(() => expect(transactions.search).toHaveBeenCalledWith(
      expect.objectContaining({ status: 'DUPLICATE' })
    ));

    fireEvent.press(screen.getByLabelText('Filter by status: Any status'));

    await waitFor(() => expect(transactions.search).toHaveBeenCalledWith(
      expect.objectContaining({ status: undefined })
    ));
  });

  it('combines with the type filter rather than replacing it', async () => {
    transactions.search.mockResolvedValue(page([]) as never);

    renderScreen();
    await waitFor(() => expect(transactions.search).toHaveBeenCalled());
    fireEvent.press(screen.getByLabelText('Filter: expense'));
    openFilters();
    fireEvent.press(screen.getByLabelText('Filter by status: Transfer'));

    await waitFor(() => expect(transactions.search).toHaveBeenCalledWith(
      expect.objectContaining({ type: 'EXPENSE', status: 'TRANSFER' })
    ));
  });

  it('says "no transactions match these filters" when a status filter narrows the list to nothing, not the fresh-account empty state', async () => {
    transactions.search.mockResolvedValue(page([]) as never);

    renderScreen();
    await waitFor(() => expect(transactions.search).toHaveBeenCalled());
    openFilters();
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

    expectHaptics({ impact: 1 });
  });
});

describe('pull to refresh', () => {
  it('a pull to refresh gives one impact', async () => {
    transactions.search.mockResolvedValue(page([txn()]) as never);
    renderScreen();
    await waitFor(() => screen.getByText('Grocery run'));
    const control = screen.UNSAFE_getByType(RefreshControl);
    act(() => control.props.onRefresh());
    expectHaptics({ impact: 1 });
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
    expect(screen.queryByText(/^0 /)).toBeNull();
  });

  it('counts in words, singular for one', async () => {
    transactions.search.mockResolvedValue(page([txn()], { totalElements: 392 }) as never);
    const many = renderScreen();
    expect(await screen.findByText('392 transactions')).toBeTruthy();
    many.unmount();

    transactions.search.mockResolvedValue(page([txn()]) as never);
    renderScreen();
    expect(await screen.findByText('1 transaction')).toBeTruthy();
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
    const alertSpy = jest.spyOn(AppAlert, 'alert').mockImplementation(() => {});
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
    const alertSpy = jest.spyOn(AppAlert, 'alert').mockImplementation(() => {});
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
    const alertSpy = jest.spyOn(AppAlert, 'alert').mockImplementation(() => {});
    transactions.search.mockResolvedValue(page([txn()]) as never);
    transactions.remove.mockResolvedValue(undefined as never);

    renderScreen();
    fireEvent.press(await screen.findByText('Grocery run'));
    fireEvent.press(screen.getByTestId('delete-button-t-1'));
    await confirmLastAlert(alertSpy, 'Delete');

    await waitFor(() => expect(transactions.remove).toHaveBeenCalledWith('t-1'));
    expect(invalidateFinancialData).toHaveBeenCalled();
    await waitFor(() => expect(screen.queryByText('Transaction Details')).toBeNull());
    // The press that opens the confirmation (Ledger's own impact), then the delete's success.
    expectHaptics({ impact: 1, success: 1 });
    alertSpy.mockRestore();
  });

  it('says so and keeps the sheet closed but the row on screen when the delete fails', async () => {
    const alertSpy = jest.spyOn(AppAlert, 'alert').mockImplementation(() => {});
    transactions.search.mockResolvedValue(page([txn()]) as never);
    transactions.remove.mockRejectedValue(new Error('network'));

    renderScreen();
    fireEvent.press(await screen.findByText('Grocery run'));
    fireEvent.press(screen.getByTestId('delete-button-t-1'));
    await confirmLastAlert(alertSpy, 'Delete');

    expect(await screen.findByText(/Could not delete this transaction/i)).toBeTruthy();
    expect(screen.getByText('Grocery run')).toBeTruthy();
    expect(screen.queryByText('Transaction Details')).toBeNull();
    expectHaptics({ impact: 1, error: 1 });
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

  // Regression: this screen used to open OptionPickerModal, a bare fixed list with no create
  // affordance -- recategorizing a row is exactly when a user is most likely to want a category
  // that doesn't exist yet, and it was unreachable here.
  it('offers to create a new category from the recategorize picker', async () => {
    transactions.search.mockResolvedValue(page([txn({ categoryName: 'Food' })]) as never);

    renderScreen();
    fireEvent.press(await screen.findByText('Grocery run'));
    fireEvent.press(await screen.findByTestId('category-button-t-1'));

    expect(await screen.findByText('New category')).toBeTruthy();
  });

  it('saves the picked category and refreshes the figures it moves', async () => {
    transactions.search.mockResolvedValue(page([txn()]) as never);
    transactions.updateCategory.mockResolvedValue({} as never);

    renderScreen();
    fireEvent.press(await screen.findByText('Grocery run'));
    fireEvent.press(await screen.findByTestId('category-button-t-1'));
    fireEvent.press(await screen.findByText('Travel'));

    await waitFor(() => expect(transactions.updateCategory).toHaveBeenCalledWith('t-1', 'Travel', 'SIMILAR'));
    // A category move changes spend-by-category, budget progress and insights -- none of which
    // this screen renders, and all of which would otherwise keep showing pre-edit figures.
    expect(invalidateFinancialData).toHaveBeenCalled();
    // The pick is the save: one haptic, the success -- no tick from the picker as well.
    await waitFor(() => expectHaptics({ success: 1 }));
  });

  it('asks whether to change the other transactions from the payee, and sends the answer', async () => {
    const alertSpy = jest.spyOn(AppAlert, 'alert').mockImplementation(() => {});
    transactions.search.mockResolvedValue(page([txn()]) as never);
    transactions.similar.mockResolvedValueOnce({ similar: 2, keptByUser: 0 });
    transactions.updateCategory.mockResolvedValue({} as never);

    renderScreen();
    fireEvent.press(await screen.findByText('Grocery run'));
    fireEvent.press(await screen.findByTestId('category-button-t-1'));
    fireEvent.press(await screen.findByText('Travel'));

    await waitFor(() => expect(alertSpy).toHaveBeenCalledWith(
      'Change the others from this payee too?', expect.stringContaining('2 other transactions'),
      expect.anything(), expect.anything()));
    expect(transactions.updateCategory).not.toHaveBeenCalled();
    const buttons = alertSpy.mock.calls[0][2]!;
    await act(async () => { buttons.find((b) => b.text === 'Only this one')!.onPress!(); });

    await waitFor(() => expect(transactions.updateCategory).toHaveBeenCalledWith('t-1', 'Travel', 'ONLY_THIS'));
    alertSpy.mockRestore();
  });

  it('saves nothing when the payee question is cancelled', async () => {
    const alertSpy = jest.spyOn(AppAlert, 'alert').mockImplementation(() => {});
    transactions.search.mockResolvedValue(page([txn()]) as never);
    transactions.similar.mockResolvedValueOnce({ similar: 2, keptByUser: 0 });

    renderScreen();
    fireEvent.press(await screen.findByText('Grocery run'));
    fireEvent.press(await screen.findByTestId('category-button-t-1'));
    fireEvent.press(await screen.findByText('Travel'));

    await waitFor(() => expect(alertSpy).toHaveBeenCalled());
    const buttons = alertSpy.mock.calls[0][2]!;
    await act(async () => { buttons.find((b) => b.text === 'Cancel')!.onPress!(); });

    expect(transactions.updateCategory).not.toHaveBeenCalled();
    expect(invalidateFinancialData).not.toHaveBeenCalled();
    alertSpy.mockRestore();
  });

  it('does not call the API when the picked category is the one already set', async () => {
    transactions.search.mockResolvedValue(page([txn({ categoryName: 'Food' })]) as never);

    renderScreen();
    fireEvent.press(await screen.findByText('Grocery run'));
    fireEvent.press(await screen.findByTestId('category-button-t-1'));
    await screen.findByText('Change category');
    // The last "Food" is the picker's: the row behind it shows its category too.
    fireEvent.press(screen.getAllByText('Food').at(-1)!);

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

    await waitFor(() => expect(transactions.updateCategory).toHaveBeenCalledWith('t-2', 'Food', 'SIMILAR'));

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
    expectHaptics({ impact: 1 });
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

  // A category named in the link is resolved to an id from the category list. If the list has not
  // arrived, a search now would be the month WITHOUT its category: a wrong list on screen until the
  // right one replaced it. (The plain, date-less search a first mount makes before the link is read
  // is never shown, and is not what this is about.)
  const monthWithoutCategory = () => transactions.search.mock.calls
    .map(([f]) => f as { categoryId?: string; dateFrom?: string })
    .filter((f) => f.dateFrom && !f.categoryId);

  it('never searches the month without its category when the category list is already known', async () => {
    mockRouteParams = { filters: filters({ categoryName: 'Travel', dateFrom: '2026-08-01', dateTo: '2026-08-31' }) };
    const queryClient = new QueryClient({ defaultOptions: { queries: { retry: false, gcTime: 0 } } });
    queryClient.setQueryData(['categories'], [{ id: 'c-2', name: 'Travel', isSystem: true, icon: 'plane', color: 'blue' }]);
    const result = render(<QueryClientProvider client={queryClient}><ToastProvider><LedgerScreen /></ToastProvider></QueryClientProvider>);
    activeScreens.push(result);

    await waitFor(() => expect(transactions.search).toHaveBeenCalledWith(expect.objectContaining({ categoryId: 'c-2' })));
    expect(monthWithoutCategory()).toHaveLength(0);
  });

  it('holds the list, showing none of the month, until the category list arrives', async () => {
    mockRouteParams = { filters: filters({ categoryName: 'Travel', dateFrom: '2026-08-01', dateTo: '2026-08-31' }) };
    transactions.search.mockImplementation((async (f: { categoryId?: string }) => page([
      txn({ id: f.categoryId ? 't-cat' : 't-month', description: f.categoryId ? 'Flight to Goa' : 'Month wide row' }),
    ])) as never);
    let resolveList!: (v: unknown) => void;
    categories.list.mockReturnValue(new Promise((res) => { resolveList = res; }) as never);

    renderScreen();
    await act(async () => {});
    expect(monthWithoutCategory()).toHaveLength(0);
    expect(screen.queryByText('Month wide row')).toBeNull();

    await act(async () => {
      resolveList([{ id: 'c-2', name: 'Travel', isSystem: true, icon: 'plane', color: 'blue' }]);
    });
    expect(await screen.findByText('Flight to Goa')).toBeTruthy();
    expect(monthWithoutCategory()).toHaveLength(0);
    expect(screen.queryByText('Month wide row')).toBeNull();
  });

  it('searches the month anyway when the category list cannot be had', async () => {
    mockRouteParams = { filters: filters({ categoryName: 'Travel', dateFrom: '2026-08-01', dateTo: '2026-08-31' }) };
    categories.list.mockRejectedValue(new Error('down'));

    renderScreen();

    await waitFor(() => expect(transactions.search).toHaveBeenCalledWith(
      expect.objectContaining({ categoryId: undefined, dateFrom: '2026-08-01', dateTo: '2026-08-31' })
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
        <ToastProvider>
          <LedgerScreen />
        </ToastProvider>
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
        <ToastProvider>
          <LedgerScreen />
        </ToastProvider>
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
    openFilters();
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
    openFilters();
    fireEvent.press(screen.getByLabelText(/From: not set\. Choose a date/));

    await waitFor(() => expect(transactions.search).toHaveBeenCalledWith(
      expect.objectContaining({ dateFrom: '2026-07-15' })
    ));
  });

  it('has nothing to clear before a pick is made', async () => {
    renderScreen();
    await screen.findByText(/No transactions yet/i);

    openFilters();
    expect(screen.getByLabelText(/From: not set\. Choose a date/)).toBeTruthy();
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
    openFilters();
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
        <ToastProvider>
          <LedgerScreen />
        </ToastProvider>
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
    // Two: the row's own category line, and the panel's echo of it.
    expect(screen.getAllByText('Food')).toHaveLength(2);
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
    // Two: the row's own tag and the modal's own badge for the same status. The status filter's
    // "Duplicate" is in the Filters sheet now, which is closed, and the detail sheet's own pill is
    // not among these either, since opening this modal already closed that sheet.
    expect(screen.getAllByText('Duplicate')).toHaveLength(2);
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
    // The pick is the save: one success once the server pairs them, not a tick on the tap.
    expectHaptics({ success: 1 });
  });

  it('gives one error, and no tick, when pairing a transfer fails', async () => {
    transactions.search.mockReset().mockImplementation(async (filters: any) =>
      filters?.keyword
        ? (page([txn({ id: 't-2', merchant: 'Savings Account', description: 'Own transfer', amount: 1299, type: 'INCOME' })]) as never)
        : (page([txn({ id: 't-1', reconciliationStatus: 'OK' })]) as never));
    transactions.markTransfer.mockRejectedValueOnce(new Error('down'));

    renderScreen();
    fireEvent.press(await screen.findByText('Grocery run'));
    fireEvent.press(screen.getByTestId('mark-transfer-button-t-1'));
    fireEvent.changeText(await screen.findByLabelText('Search transactions to pair with'), 'Savings');
    fireEvent.press(await screen.findByTestId('transfer-candidate-t-2'));

    expect(await screen.findByText('Could not mark these as a transfer.')).toBeTruthy();
    expectHaptics({ error: 1 });
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
    expectHaptics({ success: 1 });
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

  it('says the month is "so far" under the card when a statement stops short of it, and keeps the figures', async () => {
    transactions.search.mockResolvedValue(page([]) as never);
    dashboard.summary.mockResolvedValue({
      monthlyIncome: 145000, monthlyExpense: 18672, incomeDeltaPct: 12, expenseDeltaPct: -8,
      netCashFlow: 126328, netDeltaPct: 15, savingsRatePct: 87, currentBalance: 50000,
      reportingMonth: '2026-09', reportingMonthIsCurrent: true,
      statementBehind: { accountName: 'HDFC Card', coveredThrough: '2026-09-11', accountCount: 1 },
    } as never);
    renderScreen();

    expect((await screen.findByTestId('so-far-note')).props.children).toContain('September is partial so far');
    expect(screen.getByTestId('kpi-Income')).toBeTruthy();
    expect(screen.getByTestId('kpi-Expenses')).toBeTruthy();
  });

  it('names the month instead of "This Month" when the figures are from an earlier month', async () => {
    transactions.search.mockResolvedValue(page([]) as never);
    dashboard.summary.mockResolvedValue({
      monthlyIncome: 145000, monthlyExpense: 18672, incomeDeltaPct: null, expenseDeltaPct: null,
      netCashFlow: 126328, netDeltaPct: null, savingsRatePct: 87, currentBalance: 50000,
      reportingMonth: '2026-08', reportingMonthIsCurrent: false,
    } as never);
    renderScreen();

    expect(await screen.findByText('Aug 26')).toBeTruthy();
    expect(screen.queryByText('This Month')).toBeNull();
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

/**
 * The transfer modal and the bank-correction modal are siblings, and each is keyed by the
 * transaction it is showing so that it remounts with fresh state when that transaction changes.
 * Both used the same fallback key while closed, so the screen rendered two siblings with one key
 * and React logged "Encountered two children with the same key" on every render of this tab.
 */
describe('sibling modal keys', () => {
  function duplicateKeyErrors(spy: jest.SpyInstance): string[] {
    return spy.mock.calls
      .map((args) => args.map(String).join(' '))
      .filter((message) => message.includes('two children with the same key'));
  }

  let consoleError: jest.SpyInstance;
  beforeEach(() => {
    consoleError = jest.spyOn(console, 'error');
  });
  afterEach(() => {
    consoleError.mockRestore();
  });

  it('logs no duplicate-key error with both modals closed', async () => {
    transactions.search.mockResolvedValue(page([txn()]) as never);

    renderScreen();
    await screen.findByText('Grocery run');

    expect(duplicateKeyErrors(consoleError)).toEqual([]);
  });

  it('logs no duplicate-key error with both modals open on the same transaction', async () => {
    // A phone cannot reach this: the first modal covers the row. The test renderer can, and it is
    // the other way the two keys could meet -- the same transaction id in both.
    transactions.search.mockResolvedValue(
      page([txn({ reconciliationStatus: 'OK', pendingBankCorrection: true })]) as never,
    );
    transactions.correctionHistory.mockResolvedValue([] as never);

    renderScreen();
    fireEvent.press(await screen.findByText('Grocery run'));
    fireEvent.press(await screen.findByTestId('mark-transfer-button-t-1'));
    await screen.findByLabelText('Search transactions to pair with');
    fireEvent.press(screen.getAllByText('Grocery run')[0]);
    fireEvent.press(await screen.findByTestId('bank-correction-button-t-1'));
    await screen.findByText('Bank correction');

    // Both are open at once, or this test is not exercising the case it names.
    expect(screen.getByLabelText('Search transactions to pair with')).toBeTruthy();
    expect(duplicateKeyErrors(consoleError)).toEqual([]);
  });

  it('still opens the transfer modal with fresh state for the next transaction', async () => {
    // What the key is for. A key that no longer varied with the transaction would silence the
    // error above just as well, and carry one transaction's search text into the next.
    transactions.search.mockImplementation(async (filters: any) =>
      (filters?.keyword
        ? page([])
        : page([
          txn({ id: 't-1', reconciliationStatus: 'OK' }),
          txn({ id: 't-2', description: 'Rent', reconciliationStatus: 'OK' }),
        ])) as never);

    renderScreen();
    fireEvent.press(await screen.findByText('Grocery run'));
    fireEvent.press(await screen.findByTestId('mark-transfer-button-t-1'));
    fireEvent.changeText(await screen.findByLabelText('Search transactions to pair with'), 'Savings');
    expect(screen.getByLabelText('Search transactions to pair with').props.value).toBe('Savings');
    fireEvent.press(screen.getByText('Cancel'));
    await waitFor(() => expect(screen.queryByLabelText('Search transactions to pair with')).toBeNull());

    fireEvent.press(screen.getByText('Rent'));
    fireEvent.press(await screen.findByTestId('mark-transfer-button-t-2'));

    expect((await screen.findByLabelText('Search transactions to pair with')).props.value).toBe('');
  });
});

describe('the redesigned list', () => {
  it('titles a row by the payee name with softened capitals, not by the narration', async () => {
    transactions.search.mockResolvedValue(page([
      txn({ description: 'UPI/DR/600000000001/RAHUL SH/YESB/rahulsh@ybl', merchant: 'rahul sh' }),
    ]) as never);

    renderScreen();

    expect(await screen.findByText('Rahul Sh')).toBeTruthy();
    expect(screen.queryByText(/UPI\/DR/)).toBeNull();
  });

  it('heads each day with a short date and no longer prints the date on rows', async () => {
    transactions.search.mockResolvedValue(page([txn({ date: '2026-07-14' })]) as never);

    renderScreen();

    expect(await screen.findByRole('header', { name: dayHeading('2026-07-14', new Date()) })).toBeTruthy();
    expect(screen.queryByText(/2026-07-14/)).toBeNull();
  });

  it('drops the line under the title and the arrow at the end of a row', async () => {
    transactions.search.mockResolvedValue(page([txn()]) as never);

    renderScreen();

    await screen.findByText('Grocery run');
    expect(screen.queryByText('All your financial activity in one place.')).toBeNull();
    // The icon stand-in in these tests renders an icon as its name.
    expect(screen.queryByText('chevron-forward')).toBeNull();
  });

  it('lists and counts once a row the server returned on two pages', async () => {
    const repeated = txn({ id: 't-rep', description: 'Rent', amount: -100 });
    transactions.search
      .mockResolvedValueOnce(page([txn({ id: 't-a', description: 'Tea', amount: -10 }), repeated], { totalPages: 2, totalElements: 3 }) as never)
      .mockResolvedValueOnce(page([repeated, txn({ id: 't-b', description: 'Bus', amount: -5 })], { page: 1, totalPages: 2, totalElements: 3 }) as never);

    renderScreen();
    await screen.findByText('Rent');
    fireEvent(screen.getByTestId('ledger-list'), 'endReached');

    expect(await screen.findByText('Bus')).toBeTruthy();
    expect(screen.getAllByText('Rent')).toHaveLength(1);
    // 10 + 100 + 5, the repeated 100 counted once.
    expect(screen.getByText('-₹115')).toBeTruthy();
  });

  it('says on the Filters button how many of its filters are on, and shows a chip for each', async () => {
    transactions.search.mockResolvedValue(page([]) as never);
    renderScreen();
    await waitFor(() => expect(transactions.search).toHaveBeenCalled());

    openFilters();
    fireEvent.press(screen.getByLabelText('Filter by status: Transfer'));
    fireEvent.press(screen.getByLabelText('Show only international transactions'));

    expect(screen.getByLabelText('Filters, 2 on')).toBeTruthy();
    expect(screen.getByLabelText('Clear filter: Transfer')).toBeTruthy();
    expect(screen.getByLabelText('Clear filter: International')).toBeTruthy();
  });

  it('turns a filter off from its chip', async () => {
    transactions.search.mockResolvedValue(page([]) as never);
    renderScreen();
    await waitFor(() => expect(transactions.search).toHaveBeenCalled());
    openFilters();
    fireEvent.press(screen.getByLabelText('Filter by status: Transfer'));
    await waitFor(() => expect(transactions.search).toHaveBeenCalledWith(expect.objectContaining({ status: 'TRANSFER' })));

    fireEvent.press(screen.getByLabelText('Clear filter: Transfer'));

    await waitFor(() => expect(transactions.search).toHaveBeenLastCalledWith(expect.objectContaining({ status: undefined })));
    expect(screen.getByLabelText('Filters')).toBeTruthy();
  });

  it('shows a picked date range as one chip that clears both dates', async () => {
    const originalOS = Platform.OS;
    Platform.OS = 'android';
    try {
      transactions.search.mockResolvedValue(page([]) as never);
      renderScreen();
      await waitFor(() => expect(transactions.search).toHaveBeenCalled());
      jest.mocked(DateTimePickerAndroid.open).mockImplementation(({ onChange }) => {
        onChange?.({ type: 'set' } as never, new Date(2026, 6, 1));
      });
      openFilters();
      fireEvent.press(screen.getByLabelText(/From: not set\. Choose a date/));

      const clear = await screen.findByLabelText(/^Clear filter: From /);
      expect(screen.getByLabelText('Filters, 1 on')).toBeTruthy();
      fireEvent.press(clear);

      await waitFor(() => expect(transactions.search).toHaveBeenLastCalledWith(expect.objectContaining({ dateFrom: undefined })));
      expect(screen.queryByLabelText(/^Clear filter: From /)).toBeNull();
    } finally {
      Platform.OS = originalOS;
    }
  });

  it('Clear all turns off status, dates and international, and leaves search, type and an arriving filter alone', async () => {
    mockRouteParams = { filters: { label: 'Dining', nonce: 7, categoryId: 'c-1' } };
    transactions.search.mockResolvedValue(page([]) as never);
    renderScreen();
    await waitFor(() => expect(transactions.search).toHaveBeenCalled());
    fireEvent.changeText(screen.getByLabelText('Search transactions'), 'tea');
    fireEvent.press(screen.getByLabelText('Filter: expense'));
    openFilters();
    fireEvent.press(screen.getByLabelText('Filter by status: Refund'));
    fireEvent.press(screen.getByLabelText('Show only international transactions'));

    fireEvent.press(screen.getByLabelText('Clear all filters'));

    await waitFor(() => expect(transactions.search).toHaveBeenLastCalledWith(expect.objectContaining({
      status: undefined, international: undefined, dateFrom: undefined, dateTo: undefined,
      type: 'EXPENSE', keyword: 'tea', categoryId: 'c-1',
    })));
    expect(screen.getByLabelText('Clear filter: Dining')).toBeTruthy();
  });

  it('closes the sheet from a button that says how many match', async () => {
    transactions.search.mockResolvedValue(page([txn(), txn({ id: 't-2', description: 'Salary' })]) as never);
    renderScreen();
    await screen.findByText('Grocery run');
    openFilters();

    expect(screen.getByTestId('filters-done')).toHaveTextContent('Show 2 transactions');
    fireEvent.press(screen.getByTestId('filters-done'));

    expect(screen.queryByTestId('filters-done')).toBeNull();
  });

  it('says Done on that button while the count is loading', async () => {
    transactions.search.mockReturnValue(new Promise(() => {}));
    renderScreen();
    openFilters();
    expect(screen.getByTestId('filters-done')).toHaveTextContent('Done');
  });

  it('shows the pinned title and its buttons as soon as the list has moved under the bar', async () => {
    transactions.search.mockResolvedValue(page([txn()]) as never);
    renderScreen();
    await screen.findByText('Grocery run');
    expect(screen.getAllByLabelText('Add transaction')).toHaveLength(1);
    expect(screen.queryByRole('button', { name: 'Search transactions' })).toBeNull();

    // Still inside the space above the large title: nothing has reached the bar yet.
    scrollListTo(8);
    expect(screen.getAllByLabelText('Add transaction')).toHaveLength(1);

    // The large title is still on its way out. The bar covers it from here on, so its own title
    // and buttons have to be there already.
    scrollListTo(20);

    expect(screen.getAllByLabelText('Add transaction')).toHaveLength(2);
    expect(screen.getByRole('button', { name: 'Search transactions' })).toBeTruthy();
  });

  it('puts the pinned bar away again at the top, and during a pull to refresh', async () => {
    transactions.search.mockResolvedValue(page([txn()]) as never);
    renderScreen();
    await screen.findByText('Grocery run');

    scrollListTo(300);
    expect(screen.getAllByLabelText('Add transaction')).toHaveLength(2);
    scrollListTo(0);
    expect(screen.getAllByLabelText('Add transaction')).toHaveLength(1);

    scrollListTo(-80);
    expect(screen.getAllByLabelText('Add transaction')).toHaveLength(1);
  });

  // "Never while scrolling" (haptics table): scrolling, even past the pull-to-refresh point,
  // gives nothing. The impact belongs to the committed pull (RefreshControl's onRefresh).
  it('gives nothing while scrolling', async () => {
    transactions.search.mockResolvedValue(page([txn()]) as never);
    renderScreen();
    await screen.findByText('Grocery run');

    scrollListTo(300);
    scrollListTo(0);
    scrollListTo(-80);

    expectHaptics({});
  });

  it('scrolls to the top from the pinned search button', async () => {
    const scrollToOffset = jest.spyOn(FlatList.prototype, 'scrollToOffset').mockImplementation(() => {});
    transactions.search.mockResolvedValue(page([txn()]) as never);
    renderScreen();
    await screen.findByText('Grocery run');
    scrollListTo(300);

    fireEvent.press(screen.getByRole('button', { name: 'Search transactions' }));

    expect(scrollToOffset).toHaveBeenCalledWith({ offset: 0, animated: true });
    scrollToOffset.mockRestore();
  });

  it('opens Add from the pinned bar too', async () => {
    transactions.search.mockResolvedValue(page([txn()]) as never);
    renderScreen();
    await screen.findByText('Grocery run');
    scrollListTo(300);

    fireEvent.press(screen.getAllByLabelText('Add transaction')[1]);

    expect(await screen.findByText('Add Transaction')).toBeTruthy();
  });

  it('at large text drops the row avatar and stacks the controls', async () => {
    useLargeFontScale.mockReturnValue(true);
    transactions.search.mockResolvedValue(page([txn({ merchant: 'Swiggy' })]) as never);

    renderScreen();

    await screen.findByText('Swiggy');
    expect(screen.queryByLabelText('Swiggy')).toBeNull();
    expect(screen.getByTestId('ledger-controls-row')).toHaveStyle({ flexDirection: 'column' });
  });

  it('starts the list below the status bar, so the refresh spinner is not hidden under it', async () => {
    // Found on an iPhone 17 Pro simulator: with the list running under the status bar, the
    // pull-to-refresh spinner sat centred under the camera cut-out and could not be seen. The
    // pinned bar is what covers the status bar area; the list itself begins under it.
    const insets = require('react-native-safe-area-context').useSafeAreaInsets() as { top: number };
    insets.top = 59;
    try {
      transactions.search.mockResolvedValue(page([txn()]) as never);
      renderScreen();
      await screen.findByText('Grocery run');

      const list = screen.getByTestId('ledger-list');
      expect(list).toHaveStyle({ marginTop: 59 });
      expect(StyleSheet.flatten(list.props.contentContainerStyle).paddingTop).toBeUndefined();
      expect(screen.getByTestId('ledger-pinned-bar')).toHaveStyle({ top: 0, height: 59 + 54 });
    } finally {
      insets.top = 0;
    }
  });

  it('puts the loading, failed and empty states in a card', async () => {
    transactions.search.mockResolvedValue(page([]) as never);
    renderScreen();
    await screen.findByText(/No transactions yet/i);
    expect(screen.getByTestId('ledger-state-card')).toBeTruthy();
  });
});

describe('a second tap on this screen\'s tab', () => {
  // The library's useScrollToTop scrolls the list it is given back to the top when the tab that is
  // already open is pressed again (and only then). This checks the screen hands it its list.
  it('scrolls the screen back to the top: the list is handed to useScrollToTop', async () => {
    (useScrollToTop as jest.Mock).mockClear();
    renderScreen();
    await waitFor(() => {
      const calls = (useScrollToTop as jest.Mock).mock.calls;
      expect(calls.length).toBeGreaterThan(0);
      expect(typeof calls[calls.length - 1][0].current?.scrollToOffset).toBe('function');
    });
  });
});

describe('LedgerScreen first load', () => {
  it('reads as one "Loading" region to a screen reader during the first load', () => {
    transactions.search.mockReturnValue(never());
    renderScreen();
    expectSkeletonNotSpinner();
  });
});

// What a change says. The error used to be a line in the list's header, scrolled out of sight by
// the time a row far down the list was changed, and a change that worked said nothing at all.
describe('what a change says', () => {
  it('confirms a category change with the category and the payment', async () => {
    transactions.search.mockResolvedValue(page([txn()]) as never);
    transactions.updateCategory.mockResolvedValue(undefined as never);

    renderScreen();
    fireEvent.press(await screen.findByText('Grocery run'));
    fireEvent.press(await screen.findByTestId('category-button-t-1'));
    fireEvent.press(await screen.findByText('Travel'));

    const toast = await screen.findByTestId('toast');
    expect(within(toast).getByText(/Saved Travel for Grocery run/)).toBeTruthy();
  });

  it('reports a failed category change in an error toast, where it can be seen', async () => {
    transactions.search.mockResolvedValue(page([txn()]) as never);
    transactions.updateCategory.mockRejectedValue(new Error('nope'));

    renderScreen();
    fireEvent.press(await screen.findByText('Grocery run'));
    fireEvent.press(await screen.findByTestId('category-button-t-1'));
    fireEvent.press(await screen.findByText('Travel'));

    const toast = await screen.findByTestId('toast');
    expect(within(toast).getByText(/Could not change this category/i)).toBeTruthy();
    expect(within(toast).queryByText(/✓/)).toBeNull();
  });

  it('confirms a delete', async () => {
    const alertSpy = jest.spyOn(AppAlert, 'alert').mockImplementation(() => {});
    transactions.search.mockResolvedValue(page([txn()]) as never);
    transactions.remove.mockResolvedValue(undefined as never);

    renderScreen();
    fireEvent.press(await screen.findByText('Grocery run'));
    fireEvent.press(screen.getByTestId('delete-button-t-1'));
    await confirmLastAlert(alertSpy, 'Delete');
    expect(within(await screen.findByTestId('toast')).getByText(/Deleted Grocery run/)).toBeTruthy();
    alertSpy.mockRestore();
  });

  it('reports a failed delete in an error toast', async () => {
    const alertSpy = jest.spyOn(AppAlert, 'alert').mockImplementation(() => {});
    transactions.search.mockResolvedValue(page([txn()]) as never);
    transactions.remove.mockRejectedValue(new Error('network'));

    renderScreen();
    fireEvent.press(await screen.findByText('Grocery run'));
    fireEvent.press(screen.getByTestId('delete-button-t-1'));
    await confirmLastAlert(alertSpy, 'Delete');
    const toast = await screen.findByTestId('toast');
    expect(within(toast).getByText(/Could not delete this transaction/i)).toBeTruthy();
    alertSpy.mockRestore();
  });

  it('confirms a transfer unmark', async () => {
    transactions.search.mockResolvedValue(page([txn({ reconciliationStatus: 'TRANSFER' })]) as never);
    transactions.unmarkTransfer.mockResolvedValue(undefined as never);

    renderScreen();
    fireEvent.press(await screen.findByText('Grocery run'));
    fireEvent.press(screen.getByTestId('unmark-transfer-button-t-1'));
    const toast = await screen.findByTestId('toast');
    expect(within(toast).getByText(/Grocery run is no longer a transfer/)).toBeTruthy();
  });

  it('reports a failed transfer unmark in an error toast', async () => {
    transactions.search.mockResolvedValue(page([txn({ reconciliationStatus: 'TRANSFER' })]) as never);
    transactions.unmarkTransfer.mockRejectedValue(new Error('network'));

    renderScreen();
    fireEvent.press(await screen.findByText('Grocery run'));
    fireEvent.press(screen.getByTestId('unmark-transfer-button-t-1'));
    const toast = await screen.findByTestId('toast');
    expect(within(toast).getByText(/Could not unmark this transfer/i)).toBeTruthy();
  });
});

describe('the delete confirmation', () => {
  it('names the payment as its row does, with its signed amount', async () => {
    const alertSpy = jest.spyOn(AppAlert, 'alert').mockImplementation(() => {});
    transactions.search.mockResolvedValue(page([txn({
      description: 'UPI/CR/100000000001/SAMPLE EMPLOYER/YBL', merchant: 'sample employer', // synthetic-ok
      amount: 500, type: 'INCOME',
    })]) as never);

    renderScreen();
    fireEvent.press(await screen.findByText('Sample Employer'));
    fireEvent.press(screen.getByTestId('delete-button-t-1'));
    expect(alertSpy.mock.calls.at(-1)?.[1]).toBe('"Sample Employer" (+₹500) can\'t be recovered.');
    alertSpy.mockRestore();
  });
});

describe('searching', () => {
  // With the keyboard up, the list's default swallowed the first tap on a result to close the
  // keyboard (seen on the iOS simulator): finding a payment took two taps.
  it('lets the first tap on a result open it while the keyboard is up, and closes the keyboard on scroll', async () => {
    transactions.search.mockResolvedValue(page([txn()]) as never);
    renderScreen();
    await screen.findByText('Grocery run');
    const list = screen.UNSAFE_getByType(FlatList);
    expect(list.props.keyboardShouldPersistTaps).toBe('handled');
    expect(list.props.keyboardDismissMode).toBe('on-drag');
  });

  // With the tap now reaching the row, the search box kept its focus behind the details sheet, and
  // the keyboard came back over half the list when the sheet closed (iOS simulator).
  it('puts the keyboard away when a result is opened', async () => {
    const dismiss = jest.spyOn(Keyboard, 'dismiss');
    transactions.search.mockResolvedValue(page([txn()]) as never);
    renderScreen();
    fireEvent.press(await screen.findByText('Grocery run'));
    expect(dismiss).toHaveBeenCalled();
    dismiss.mockRestore();
  });

  it('labels the keyboard key Search', async () => {
    transactions.search.mockResolvedValue(page([txn()]) as never);
    renderScreen();
    expect((await screen.findByLabelText('Search transactions')).props.returnKeyType).toBe('search');
  });

  it('offers a clear button once something is typed, which empties the search', async () => {
    transactions.search.mockResolvedValue(page([txn()]) as never);
    renderScreen();
    await screen.findByText('Grocery run');
    expect(screen.queryByLabelText('Clear search')).toBeNull();

    fireEvent.changeText(screen.getByLabelText('Search transactions'), 'swiggy');
    fireEvent.press(screen.getByLabelText('Clear search'));

    expect(screen.getByLabelText('Search transactions').props.value).toBe('');
    expect(screen.queryByLabelText('Clear search')).toBeNull();
  });
});

describe('empty states that offer the next step', () => {
  it('offers Clear filters when the filters match nothing, and it clears every one of them', async () => {
    transactions.search.mockResolvedValue(page([]) as never);
    renderScreen();
    await waitFor(() => expect(transactions.search).toHaveBeenCalled());
    fireEvent.changeText(screen.getByLabelText('Search transactions'), 'swiggy');
    fireEvent.press(screen.getByLabelText('Filter: expense'));
    openFilters();
    fireEvent.press(screen.getByLabelText('Show only international transactions'));
    fireEvent.press(screen.getByLabelText('Filter by status: OK'));
    // The sheet closes on its own Done/close; the empty state sits behind it either way.
    fireEvent.press(await screen.findByRole('button', { name: 'Clear filters' }));

    await waitFor(() => expect(transactions.search).toHaveBeenLastCalledWith(expect.objectContaining({
      keyword: undefined, type: undefined, status: undefined, international: undefined,
      dateFrom: undefined, dateTo: undefined,
    })));
    expect(screen.getByLabelText('Search transactions').props.value).toBe('');
  });

  it('offers Import a statement on an empty ledger, which opens the Import tab', async () => {
    transactions.search.mockResolvedValue(page([]) as never);
    mockNavigate.mockClear();
    renderScreen();
    fireEvent.press(await screen.findByRole('button', { name: 'Import a statement' }));
    expect(mockNavigate).toHaveBeenCalledWith('Import');
  });

  it('does not offer Import when the list is empty only because of filters', async () => {
    transactions.search.mockResolvedValue(page([]) as never);
    renderScreen();
    await waitFor(() => expect(transactions.search).toHaveBeenCalled());
    fireEvent.press(screen.getByLabelText('Filter: expense'));
    await screen.findByText('No transactions match these filters.');
    expect(screen.queryByRole('button', { name: 'Import a statement' })).toBeNull();
  });
});
