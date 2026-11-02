import { QueryClient, QueryClientProvider } from '@tanstack/react-query';
import { act, fireEvent, render, screen, waitFor } from '@testing-library/react-native';
import { aiInsightsApi, insightsApi, type LookAtServerRow } from '../../api/endpoints';
import { DoneChanges } from './DoneChanges';

// The real hook resolves once per test process, so the first test in a run saw the glass card
// remount its content mid-test and an element it had just found leave the tree.
jest.mock('../../lib/useReduceTransparency', () => ({ useReduceTransparency: jest.fn(() => null) }));
jest.mock('../../api/endpoints', () => ({
  ...jest.requireActual('../../api/endpoints'),
  aiInsightsApi: { lookAt: jest.fn() },
  insightsApi: { acknowledgements: jest.fn() },
}));

const lookAt = aiInsightsApi.lookAt as jest.Mock;
const acks = insightsApi.acknowledgements as jest.Mock;

function serverRow(over: Partial<LookAtServerRow>): LookAtServerRow {
  return {
    kind: 'running-high', key: 'k', ackKind: null, ackItemKey: null, month: '2026-09', total: null, count: null,
    cardName: null, cardLast4: null, people: null, recurring: null, merchant: null, category: null,
    latestAmount: null, latestDate: null, current: null, priorAverage: null, whyAvailable: null, ...over,
  };
}

const high = serverRow({
  kind: 'running-high', key: 'high-Food', ackKind: 'CATEGORY_HIGH', ackItemKey: 'Food|2026-09',
  category: 'Food', current: 9400, priorAverage: 6300,
});
const changed = serverRow({
  kind: 'amount-changed', key: 'changed-Sample Tv', ackKind: 'AMOUNT_CHANGED', ackItemKey: 'Sample Tv',
  merchant: 'Sample Tv', category: 'Subscriptions', latestAmount: 649, latestDate: '2026-09-05',
});

function renderDone(month: string | null, onOpen = jest.fn()) {
  const client = new QueryClient({ defaultOptions: { queries: { retry: false } } });
  const view = render(
    <QueryClientProvider client={client}>
      <DoneChanges month={month} onOpen={onOpen} />
    </QueryClientProvider>,
  );
  return { ...view, onOpen };
}

beforeEach(() => {
  lookAt.mockReset();
  acks.mockReset().mockResolvedValue([]);
});

describe('DoneChanges', () => {
  it('names the month and lists its rows', async () => {
    lookAt.mockResolvedValue({ rows: [high, changed], orderedByAi: false });
    renderDone('2026-09');

    expect(await screen.findByText('September 2026 in Fynora')).toBeOnTheScreen();
    expect(screen.getByText('Food is ₹3,100 above your usual month')).toBeOnTheScreen();
    expect(screen.getByText('Amount changed: Sample Tv')).toBeOnTheScreen();
    expect(lookAt).toHaveBeenCalledWith('2026-09');
  });

  it('hides a row the person already marked "Got it"', async () => {
    lookAt.mockResolvedValue({ rows: [high, changed], orderedByAi: false });
    acks.mockResolvedValue([{ kind: 'AMOUNT_CHANGED', itemKey: 'Sample Tv' }]);
    renderDone('2026-09');

    expect(await screen.findByText('Food is ₹3,100 above your usual month')).toBeOnTheScreen();
    expect(screen.queryByText('Amount changed: Sample Tv')).toBeNull();
  });

  it('opens Insights on the month', async () => {
    lookAt.mockResolvedValue({ rows: [high], orderedByAi: false });
    const { onOpen } = renderDone('2026-09');

    fireEvent.press(await screen.findByRole('button', { name: /Food is ₹3,100 above your usual month/ }));
    expect(onOpen).toHaveBeenCalledWith('2026-09');
  });

  // React Query hands results over on a timer: wait for the request, then one real tick.
  async function settled() {
    await waitFor(() => expect(lookAt).toHaveBeenCalled());
    await act(async () => { await new Promise((resolve) => setTimeout(resolve, 0)); });
  }

  it('renders nothing with no rows', async () => {
    lookAt.mockResolvedValue({ rows: [], orderedByAi: false });
    const { toJSON } = renderDone('2026-09');
    await settled();
    expect(toJSON()).toBeNull();
  });

  it('renders nothing when the request fails', async () => {
    lookAt.mockRejectedValue(new Error('offline'));
    const { toJSON } = renderDone('2026-09');
    await settled();
    expect(toJSON()).toBeNull();
  });

  it('renders nothing and asks nothing without a month', () => {
    const { toJSON } = renderDone(null);
    expect(toJSON()).toBeNull();
    expect(lookAt).not.toHaveBeenCalled();
  });
});
