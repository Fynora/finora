import { fireEvent, render, screen, waitFor } from '@testing-library/react-native';
import { QueryClient, QueryClientProvider } from '@tanstack/react-query';
import { TransactionExplanationModal } from './TransactionExplanationModal';
import { transactionsApi } from '../api/endpoints';
import type { TransactionExplanation } from '../types';

jest.mock('../api/endpoints', () => ({
  transactionsApi: { explanation: jest.fn() },
}));

const transactions = transactionsApi as jest.Mocked<typeof transactionsApi>;

function explanation(over: Partial<TransactionExplanation> = {}): TransactionExplanation {
  return {
    decisionSource: 'RULE',
    summary: 'Matched your rule for "Big Bazaar".',
    evidence: [],
    ...over,
  };
}

function renderModal(
  transactionId: string | null,
  category: string | null = 'Food',
  onClose = jest.fn()
) {
  const queryClient = new QueryClient({ defaultOptions: { queries: { retry: false, gcTime: 0 } } });
  return {
    onClose,
    ...render(
      <QueryClientProvider client={queryClient}>
        <TransactionExplanationModal transactionId={transactionId} category={category} onClose={onClose} />
      </QueryClientProvider>
    ),
  };
}

beforeEach(() => {
  jest.clearAllMocks();
});

describe('TransactionExplanationModal (Phase 4)', () => {
  it('renders nothing when no transaction is being explained', () => {
    const { toJSON } = renderModal(null);
    expect(toJSON()).toBeNull();
    expect(transactions.explanation).not.toHaveBeenCalled();
  });

  it('shows the row\'s own category as context, and the summary once loaded', async () => {
    transactions.explanation.mockResolvedValue(explanation());

    renderModal('t-1', 'Food');

    expect(await screen.findByText('Matched your rule for "Big Bazaar".')).toBeTruthy();
    expect(screen.getByText('Food')).toBeTruthy();
    expect(transactions.explanation).toHaveBeenCalledWith('t-1');
  });

  it('omits the category context line when none was given', async () => {
    transactions.explanation.mockResolvedValue(explanation());

    renderModal('t-1', null);

    await screen.findByText('Matched your rule for "Big Bazaar".');
    expect(screen.queryByText('Food')).toBeNull();
  });

  it('shows the confidence only when the decision source actually has one', async () => {
    transactions.explanation.mockResolvedValue(explanation({ confidence: 92 }));

    renderModal('t-1');

    expect(await screen.findByText('92% confidence')).toBeTruthy();
  });

  // MANUAL/FILE_PROVIDED decisions are stated facts, not guesses -- they carry no confidence at
  // all (backend sends the field absent, not zero), and rendering "0% confidence" for one would
  // claim the engine was nearly certain about something it never decided in the first place.
  it('shows no confidence line at all when the field is absent', async () => {
    transactions.explanation.mockResolvedValue(explanation({ confidence: undefined }));

    renderModal('t-1');

    await screen.findByText('Matched your rule for "Big Bazaar".');
    expect(screen.queryByText(/confidence/)).toBeNull();
  });

  it('lists categorization evidence as bullet lines', async () => {
    transactions.explanation.mockResolvedValue(explanation({
      evidence: ['Rule created 2026-05-01', 'Matched merchant "Big Bazaar" exactly'],
    }));

    renderModal('t-1');

    expect(await screen.findByText(/Rule created 2026-05-01/)).toBeTruthy();
    expect(screen.getByText(/Matched merchant "Big Bazaar" exactly/)).toBeTruthy();
  });

  // The overwhelming majority of rows have reconciliationStatus OK -- nothing matched them, so
  // there is nothing here to explain, and the section must not render an empty husk.
  it('omits the reconciliation section entirely when nothing matched this row', async () => {
    transactions.explanation.mockResolvedValue(explanation());

    renderModal('t-1');

    await screen.findByText('Matched your rule for "Big Bazaar".');
    expect(screen.queryByText('Duplicate')).toBeNull();
    expect(screen.queryByText('Transfer')).toBeNull();
  });

  it('shows the reconciliation match, badged, with its own evidence, above the categorization answer', async () => {
    transactions.explanation.mockResolvedValue(explanation({
      reconciliation: {
        status: 'TRANSFER', matchedTransactionId: 't-9',
        summary: 'Matches money moving to your Kotak account.', evidence: ['Same amount, opposite direction, same day'],
      },
    }));

    renderModal('t-1');

    expect(await screen.findByText('Transfer')).toBeTruthy();
    expect(screen.getByText('Matches money moving to your Kotak account.')).toBeTruthy();
    expect(screen.getByText(/Same amount, opposite direction, same day/)).toBeTruthy();
  });

  // reconciliationBadge itself returns null for OK -- this pins that a genuinely unrecognised
  // future status (or one the badge mapping doesn't cover) still shows the plain-English summary
  // rather than silently dropping the whole reconciliation section for want of a badge.
  it('still shows the reconciliation summary even when no badge tone applies', async () => {
    transactions.explanation.mockResolvedValue(explanation({
      reconciliation: {
        status: 'SUPERSEDED', matchedTransactionId: null,
        summary: 'This period was replaced by a later statement re-upload.', evidence: [],
      },
    }));

    renderModal('t-1');

    expect(await screen.findByText('This period was replaced by a later statement re-upload.')).toBeTruthy();
  });

  it('says so, rather than nothing, when the request fails', async () => {
    transactions.explanation.mockRejectedValue(new Error('network down'));

    renderModal('t-1');

    expect(await screen.findByText("Couldn't load this explanation.")).toBeTruthy();
  });

  it('calls onClose when Close is pressed', async () => {
    transactions.explanation.mockResolvedValue(explanation());
    const { onClose } = renderModal('t-1');

    fireEvent.press(await screen.findByText('Close'));

    await waitFor(() => expect(onClose).toHaveBeenCalled());
  });
});
