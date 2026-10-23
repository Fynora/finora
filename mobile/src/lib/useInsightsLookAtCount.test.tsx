import { render, screen, waitFor } from '@testing-library/react-native';
import { Text } from 'react-native';
import { QueryClient, QueryClientProvider } from '@tanstack/react-query';
import { aiInsightsApi, insightsApi, peopleApi, recurringApi, type LookAtServerRow } from '../api/endpoints';
import { useInsightsLookAtCount } from './useInsightsLookAtCount';

jest.mock('../api/endpoints', () => ({
  insightsApi: { get: jest.fn(), acknowledgements: jest.fn() },
  recurringApi: { list: jest.fn(), changedAmounts: jest.fn() },
  peopleApi: { suggestions: jest.fn() },
  aiInsightsApi: { lookAt: jest.fn() },
}));

const insights = insightsApi as jest.Mocked<typeof insightsApi>;
const recurring = recurringApi as jest.Mocked<typeof recurringApi>;
const people = peopleApi as jest.Mocked<typeof peopleApi>;
const ai = aiInsightsApi as jest.Mocked<typeof aiInsightsApi>;

function serverRow(fields: Partial<LookAtServerRow> & Pick<LookAtServerRow, 'kind' | 'key'>): LookAtServerRow {
  return {
    ackKind: null, ackItemKey: null, month: null, total: null, count: null, cardName: null, cardLast4: null,
    people: null, recurring: null, merchant: null, category: null, latestAmount: null, latestDate: null,
    current: null, priorAverage: null, whyAvailable: null, ...fields,
  };
}
const travel = serverRow({
  kind: 'running-high', key: 'CATEGORY_HIGH|Travel|2026-09', ackKind: 'CATEGORY_HIGH', ackItemKey: 'Travel|2026-09',
  month: '2026-09', category: 'Travel', current: 12000, priorAverage: 7000, whyAvailable: true,
});

function Probe() {
  return <Text testID="count">{String(useInsightsLookAtCount())}</Text>;
}

function show() {
  const queryClient = new QueryClient({ defaultOptions: { queries: { retry: false, gcTime: 0 } } });
  render(<QueryClientProvider client={queryClient}><Probe /></QueryClientProvider>);
}

beforeEach(() => {
  insights.get.mockReset().mockResolvedValue({
    sentences: [], movers: [{ category: 'Food', current: 9400, priorAverage: 6300, pctChange: 49 }],
    coverageCaveat: null, biggestCategory: null, topMerchant: null, reportingMonth: '2026-09', reportingMonthIsCurrent: false,
    unlinkedCardBills: { month: '2026-09', total: 21400, count: 2, cardName: null, cardLast4: null },
  });
  insights.acknowledgements.mockReset().mockResolvedValue([]);
  recurring.list.mockReset().mockResolvedValue([]);
  recurring.changedAmounts.mockReset().mockResolvedValue([]);
  people.suggestions.mockReset().mockResolvedValue({ items: [], expected: [] });
  // An older server: the count is the phone's own, as before.
  ai.lookAt.mockReset().mockRejectedValue(Object.assign(new Error('Not Found'), { response: { status: 404 } }));
});

describe('useInsightsLookAtCount', () => {
  it('counts the rows "To look at" shows', async () => {
    show();
    await waitFor(() => expect(screen.getByTestId('count').props.children).toBe('2'));
  });

  it('counts the questions about money with people too, less one marked "Got it"', async () => {
    const row = {
      kind: 'SHARE_CHECK' as const, ackKey: 't-1', txnId: 't-1', relatedTxnId: null, personKey: 'vpa:flatmate.a',
      label: 'Flatmate A', amount: 9000, date: '2026-10-04', category: 'Rent', bills: [], owed: null,
    };
    people.suggestions.mockResolvedValue({ items: [row, { ...row, kind: 'LENT_CHECK', ackKey: 't-2', txnId: 't-2' }], expected: [] });
    insights.acknowledgements.mockResolvedValue([{ kind: 'LENT_CHECK', itemKey: 't-2' }]);
    show();
    await waitFor(() => expect(screen.getByTestId('count').props.children).toBe('3'));
  });

  it('still counts the other rows when the people rows cannot be fetched', async () => {
    people.suggestions.mockRejectedValue(new Error('older server'));
    show();
    await waitFor(() => expect(people.suggestions).toHaveBeenCalled());
    await waitFor(() => expect(screen.getByTestId('count').props.children).toBe('2'));
  });

  it('does not count what was marked "Got it"', async () => {
    insights.acknowledgements.mockResolvedValue([
      { kind: 'CATEGORY_HIGH', itemKey: 'Food|2026-09' }, { kind: 'CARD_STATEMENT_MISSING', itemKey: '2026-09' },
    ]);
    show();
    await waitFor(() => expect(insights.acknowledgements).toHaveBeenCalled());
    await waitFor(() => expect(screen.getByTestId('count').props.children).toBe('0'));
  });

  it('counts the server\'s rows, the ones Insights shows, when the server gives them', async () => {
    ai.lookAt.mockResolvedValue({ rows: [travel], orderedByAi: false });
    show();
    await waitFor(() => expect(ai.lookAt).toHaveBeenCalled());
    await waitFor(() => expect(screen.getByTestId('count').props.children).toBe('1'));
  });

  it('counts the server\'s rows less one marked "Got it" since, and even when insights fail', async () => {
    insights.get.mockRejectedValue(new Error('down'));
    ai.lookAt.mockResolvedValue({ rows: [travel, { ...travel, key: 'CATEGORY_HIGH|Rent|2026-09', category: 'Rent', ackItemKey: 'Rent|2026-09' }], orderedByAi: false });
    insights.acknowledgements.mockResolvedValue([{ kind: 'CATEGORY_HIGH', itemKey: 'Rent|2026-09' }]);
    show();
    await waitFor(() => expect(insights.acknowledgements).toHaveBeenCalled());
    await waitFor(() => expect(screen.getByTestId('count').props.children).toBe('1'));
  });

  it('is 0 while loading and when insights fail', async () => {
    insights.get.mockRejectedValue(new Error('down'));
    show();
    expect(screen.getByTestId('count').props.children).toBe('0');
    await waitFor(() => expect(insights.get).toHaveBeenCalled());
    expect(screen.getByTestId('count').props.children).toBe('0');
  });
});
