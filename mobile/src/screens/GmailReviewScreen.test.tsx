import { act, fireEvent, render, screen } from '@testing-library/react-native';
import { QueryClient, QueryClientProvider } from '@tanstack/react-query';
import { GmailReviewScreen } from './GmailReviewScreen';
import { categoriesApi, gmailApi, type GmailReviewItem } from '../api/endpoints';
import { invalidateFinancialData } from '../lib/invalidateFinancialData';
import { ThemeProvider } from '../theme';

jest.mock('../api/endpoints', () => ({
  gmailApi: { reviewQueue: jest.fn(), approve: jest.fn(), reject: jest.fn() },
  categoriesApi: { list: jest.fn() },
}));

jest.mock('../lib/invalidateFinancialData', () => ({
  invalidateFinancialData: jest.fn(),
}));

const api = gmailApi as jest.Mocked<typeof gmailApi>;
const categories = categoriesApi as jest.Mocked<typeof categoriesApi>;

function renderScreen() {
  const queryClient = new QueryClient({ defaultOptions: { queries: { retry: false, gcTime: 0 } } });
  return render(
    <QueryClientProvider client={queryClient}>
      <ThemeProvider>
        <GmailReviewScreen />
      </ThemeProvider>
    </QueryClientProvider>
  );
}

const ITEM: GmailReviewItem = {
  sessionId: 'sess-1', merchant: 'Swiggy', merchantDomain: 'swiggy.in', amount: 450,
  date: '2026-08-10', category: 'Food', confidence: 0.92, stagedAt: '2026-08-10T12:00:00Z',
  reasoning: 'Matched a known food-delivery merchant template.',
};

const ITEM_2: GmailReviewItem = {
  sessionId: 'sess-2', merchant: 'Ola', merchantDomain: 'olacabs.com', amount: 220,
  date: '2026-08-11', category: 'Travel', confidence: 0.81, stagedAt: '2026-08-11T09:00:00Z',
  reasoning: 'Matched a known ride-hailing merchant template.',
};

beforeEach(() => {
  jest.clearAllMocks();
  categories.list.mockResolvedValue([
    { id: 'c1', name: 'Food', isSystem: true, icon: 'utensils', color: 'orange' },
    { id: 'c2', name: 'Travel', isSystem: false, icon: 'plane', color: 'blue' },
  ] as never);
});

describe('GmailReviewScreen', () => {
  it('shows a positive empty state when nothing is waiting', async () => {
    api.reviewQueue.mockResolvedValue([]);
    renderScreen();

    expect(await screen.findByText('Nothing waiting for review right now.')).toBeTruthy();
  });

  it('shows an error when the queue fails to load', async () => {
    api.reviewQueue.mockRejectedValue(new Error('network'));
    renderScreen();

    expect(await screen.findByText("Couldn't load your Gmail receipts — please try again later.")).toBeTruthy();
  });

  it('lists a receipt with its amount, confidence, and reasoning', async () => {
    api.reviewQueue.mockResolvedValue([ITEM]);
    renderScreen();

    expect(await screen.findByText('Swiggy')).toBeTruthy();
    expect(screen.getByText('₹450')).toBeTruthy();
    expect(screen.getByText('92% confidence')).toBeTruthy();
    expect(screen.getByText('Matched a known food-delivery merchant template.')).toBeTruthy();
    expect(screen.getByText('Food')).toBeTruthy();
  });

  it('approves with the original category when it was not changed', async () => {
    api.reviewQueue.mockResolvedValue([ITEM]);
    api.approve.mockResolvedValue(undefined as never);
    renderScreen();
    await screen.findByText('Swiggy');

    await act(async () => fireEvent.press(screen.getByLabelText('Approve Swiggy')));

    expect(api.approve).toHaveBeenCalledWith('sess-1', undefined);
    expect(invalidateFinancialData).toHaveBeenCalled();
    expect(screen.queryByText('Swiggy')).toBeNull();
  });

  it('approves with the edited category when one was picked', async () => {
    api.reviewQueue.mockResolvedValue([ITEM]);
    api.approve.mockResolvedValue(undefined as never);
    renderScreen();
    await screen.findByText('Swiggy');

    fireEvent.press(screen.getByLabelText('Category: Food'));
    fireEvent.press(await screen.findByTestId('option-Travel'));
    await act(async () => fireEvent.press(screen.getByLabelText('Approve Swiggy')));

    expect(api.approve).toHaveBeenCalledWith('sess-1', 'Travel');
  });

  it('does not send a category override for an exact re-selection of the original', async () => {
    api.reviewQueue.mockResolvedValue([ITEM]);
    api.approve.mockResolvedValue(undefined as never);
    renderScreen();
    await screen.findByText('Swiggy');

    fireEvent.press(screen.getByLabelText('Category: Food'));
    fireEvent.press(await screen.findByTestId('option-Food'));
    await act(async () => fireEvent.press(screen.getByLabelText('Approve Swiggy')));

    expect(api.approve).toHaveBeenCalledWith('sess-1', undefined);
  });

  it('shows a row error and keeps the row when approve fails', async () => {
    api.reviewQueue.mockResolvedValue([ITEM]);
    api.approve.mockRejectedValue(new Error('network'));
    renderScreen();
    await screen.findByText('Swiggy');

    await act(async () => fireEvent.press(screen.getByLabelText('Approve Swiggy')));

    expect(await screen.findByText("Couldn't approve this receipt -- try again.")).toBeTruthy();
    expect(screen.getByText('Swiggy')).toBeTruthy();
  });

  it('rejects and removes the row without touching financial data', async () => {
    api.reviewQueue.mockResolvedValue([ITEM]);
    api.reject.mockResolvedValue(undefined as never);
    renderScreen();
    await screen.findByText('Swiggy');

    await act(async () => fireEvent.press(screen.getByLabelText('Reject Swiggy')));

    expect(api.reject).toHaveBeenCalledWith('sess-1');
    expect(invalidateFinancialData).not.toHaveBeenCalled();
    expect(screen.queryByText('Swiggy')).toBeNull();
  });

  // Regression test: busy state used to be tracked as a single id, so approving row A then
  // rejecting row B while A's own request was still in flight incorrectly cleared A's busy state
  // (busyId had moved on to B), re-showing A's Approve/Reject buttons and opening a real
  // double-submit window on the still-pending first request.
  it('keeps an in-flight row busy while a different row is resolved concurrently', async () => {
    api.reviewQueue.mockResolvedValue([ITEM, ITEM_2]);
    let resolveApproveA: (() => void) | undefined;
    api.approve.mockImplementation(
      () => new Promise((resolve) => { resolveApproveA = () => resolve(undefined as never); })
    );
    api.reject.mockResolvedValue(undefined as never);
    renderScreen();
    await screen.findByText('Swiggy');
    await screen.findByText('Ola');

    // Row A (Swiggy) starts approving and never resolves yet.
    fireEvent.press(screen.getByLabelText('Approve Swiggy'));
    // Row B (Ola) resolves fully while row A is still pending.
    await act(async () => fireEvent.press(screen.getByLabelText('Reject Ola')));

    // Row A must still be showing as busy -- its own Approve/Reject controls gone -- not
    // reappeared just because an unrelated row finished.
    expect(screen.queryByLabelText('Approve Swiggy')).toBeNull();
    expect(screen.queryByLabelText('Reject Swiggy')).toBeNull();
    expect(api.approve).toHaveBeenCalledTimes(1);

    resolveApproveA?.();
    await act(async () => {});
    expect(screen.queryByText('Swiggy')).toBeNull();
  });

  // Regression coverage for the actual double-submit guard: two presses on the SAME row's Approve
  // button dispatched before either has a chance to update state (a real double-tap) must still
  // only send one request -- this is what useKeyedSingleFlight's ref (not the busy-state Set,
  // which updates a render late) is for.
  it('sends only one request for two rapid presses on the same row', async () => {
    api.reviewQueue.mockResolvedValue([ITEM]);
    api.approve.mockResolvedValue(undefined as never);
    renderScreen();
    await screen.findByText('Swiggy');

    await act(async () => {
      fireEvent.press(screen.getByLabelText('Approve Swiggy'));
      fireEvent.press(screen.getByLabelText('Approve Swiggy'));
    });

    expect(api.approve).toHaveBeenCalledTimes(1);
  });

  it('shows a row error and keeps the row when reject fails', async () => {
    api.reviewQueue.mockResolvedValue([ITEM]);
    api.reject.mockRejectedValue(new Error('network'));
    renderScreen();
    await screen.findByText('Swiggy');

    await act(async () => fireEvent.press(screen.getByLabelText('Reject Swiggy')));

    expect(await screen.findByText("Couldn't discard this receipt -- try again.")).toBeTruthy();
    expect(screen.getByText('Swiggy')).toBeTruthy();
  });
});
