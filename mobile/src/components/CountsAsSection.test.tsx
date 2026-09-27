import { fireEvent, render, screen, waitFor } from '@testing-library/react-native';
import { QueryClient, QueryClientProvider } from '@tanstack/react-query';
import { CountsAsSection } from './CountsAsSection';
import { inflowApi } from '../api/endpoints';
import type { CountsAs, InflowKind } from '../types';

jest.mock('../api/endpoints', () => ({
  inflowApi: { countsAs: jest.fn(), kinds: jest.fn(), setChoice: jest.fn(), clearChoice: jest.fn(), createKind: jest.fn() },
}));

const inflow = inflowApi as jest.Mocked<typeof inflowApi>;

const unresolved: CountsAs = {
  flowClass: 'UNRESOLVED', flowReason: 'PERSON_INFLOW', kind: null, appliedBy: null, choosable: true,
  notChoosableReason: null, senderAvailable: true, senderLabel: 'ASHA VERMA', senderRowCount: 3,
  summary: 'Not counted yet · from a person',
};
const family: InflowKind = { id: 'k2', name: 'Family support', countsAsIncome: true, builtIn: 'FAMILY_SUPPORT' };

function renderSection() {
  const queryClient = new QueryClient({ defaultOptions: { queries: { retry: false } } });
  return render(
    <QueryClientProvider client={queryClient}>
      <CountsAsSection transactionId="t1" />
    </QueryClientProvider>,
  );
}

describe('CountsAsSection', () => {
  beforeEach(() => {
    jest.clearAllMocks();
    inflow.countsAs.mockResolvedValue(unresolved);
    inflow.kinds.mockResolvedValue([family]);
  });

  it('sets a kind for every payment from the sender by default', async () => {
    const marked: CountsAs = {
      ...unresolved, flowClass: 'INCOME', flowReason: 'FAMILY_SUPPORT', kind: family, appliedBy: 'SENDER',
      summary: 'You marked payments from this sender as Family support',
    };
    inflow.setChoice.mockResolvedValue(marked);
    // The write refreshes every financial query, this one included; the server then says the same.
    inflow.countsAs.mockResolvedValueOnce(unresolved).mockResolvedValue(marked);
    renderSection();
    expect(await screen.findByText('Not counted yet · from a person')).toBeOnTheScreen();
    fireEvent.press(screen.getByText('Change'));
    fireEvent.press(await screen.findByText('Family support'));
    fireEvent.press(screen.getByText('Every payment from ASHA VERMA (3)'));
    await waitFor(() => expect(inflow.setChoice).toHaveBeenCalledWith('t1', 'k2', 'SENDER'));
    expect(await screen.findByText('You marked payments from this sender as Family support')).toBeOnTheScreen();
  });

  it('offers only this payment when the sender is unknown', async () => {
    inflow.countsAs.mockResolvedValue({ ...unresolved, senderAvailable: false, senderLabel: null, senderRowCount: 0 });
    renderSection();
    fireEvent.press(await screen.findByText('Change'));
    fireEvent.press(await screen.findByText('Family support'));
    expect(screen.queryByText(/Every payment from/)).not.toBeOnTheScreen();
    expect(screen.getByText('Just this one')).toBeOnTheScreen();
  });

  it('shows the reason and no Change button when the row cannot take a kind', async () => {
    inflow.countsAs.mockResolvedValue({
      ...unresolved, flowClass: 'TRANSFER', flowReason: 'OWN_ACCOUNT_TRANSFER', choosable: false,
      notChoosableReason: 'This payment is matched as a transfer between your accounts. Use "Not a transfer" first.',
      summary: 'Transfer between your accounts',
    });
    renderSection();
    expect(await screen.findByText(/Use "Not a transfer" first/)).toBeOnTheScreen();
    expect(screen.queryByText('Change')).not.toBeOnTheScreen();
  });

  it('says a sender-wide choice is cleared for every payment from the sender', async () => {
    inflow.countsAs.mockResolvedValue({
      ...unresolved, flowClass: 'INCOME', kind: family, appliedBy: 'SENDER',
      summary: 'You marked payments from this sender as Family support',
    });
    inflow.clearChoice.mockResolvedValue(unresolved);
    renderSection();
    fireEvent.press(await screen.findByText('Clear for every payment from ASHA VERMA (3)'));
    await waitFor(() => expect(inflow.clearChoice).toHaveBeenCalledWith('t1', 'SENDER'));
  });

  it('clears the user choice', async () => {
    inflow.countsAs.mockResolvedValue({
      ...unresolved, flowClass: 'INCOME', kind: family, appliedBy: 'ROW', summary: 'You marked this payment as Family support',
    });
    inflow.clearChoice.mockResolvedValue(unresolved);
    renderSection();
    fireEvent.press(await screen.findByText('Clear my choice'));
    await waitFor(() => expect(inflow.clearChoice).toHaveBeenCalledWith('t1', 'ROW'));
  });
});
