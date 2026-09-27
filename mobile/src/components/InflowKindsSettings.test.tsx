import { fireEvent, render, screen, waitFor } from '@testing-library/react-native';
import { QueryClient, QueryClientProvider } from '@tanstack/react-query';
import { AxiosError, AxiosHeaders } from 'axios';
import { InflowKindsSettings } from './InflowKindsSettings';
import { inflowApi } from '../api/endpoints';
import { AppAlert } from '../lib/appAlert';
import type { InflowKind } from '../types';

jest.mock('../api/endpoints', () => ({
  inflowApi: {
    kinds: jest.fn(), createKind: jest.fn(), updateKind: jest.fn(), deleteKind: jest.fn(), senderRules: jest.fn(), forgetSender: jest.fn(),
  },
}));

jest.mock('../lib/invalidateFinancialData', () => ({
  invalidateFinancialData: jest.fn(),
}));

const inflow = inflowApi as jest.Mocked<typeof inflowApi>;
const income: InflowKind = { id: 'k1', name: 'Income', countsAsIncome: true, builtIn: 'INCOME' };
const rent: InflowKind = { id: 'k6', name: 'Rent from tenant', countsAsIncome: true, builtIn: null };

function renderSettings() {
  const queryClient = new QueryClient({ defaultOptions: { queries: { retry: false, gcTime: 0 } } });
  return render(
    <QueryClientProvider client={queryClient}>
      <InflowKindsSettings />
    </QueryClientProvider>,
  );
}

/** Answers every AppAlert with its destructive button, as a user confirming would. */
function confirmAlerts() {
  return jest.spyOn(AppAlert, 'alert').mockImplementation((_title, _message, buttons) => {
    buttons?.find((b) => b.style === 'destructive')?.onPress?.();
  });
}

beforeEach(() => {
  jest.restoreAllMocks();
  jest.clearAllMocks();
  inflow.kinds.mockResolvedValue([income, rent]);
  inflow.senderRules.mockResolvedValue([{ id: 'r1', label: 'ASHA VERMA', kind: income, rowCount: 3 }]);
});

describe('InflowKindsSettings', () => {
  it('lists kinds and remembered senders', async () => {
    renderSettings();
    expect(await screen.findByText('Rent from tenant')).toBeOnTheScreen();
    expect(await screen.findByText('ASHA VERMA')).toBeOnTheScreen();
    expect(screen.getByText('Income · 3 payments')).toBeOnTheScreen();
  });

  it('built-ins have no delete button and no income switch', async () => {
    renderSettings();
    await screen.findByText('Rent from tenant');
    expect(screen.queryByLabelText('Delete Income')).not.toBeOnTheScreen();
    expect(screen.getByLabelText('Delete Rent from tenant')).toBeOnTheScreen();
    expect(screen.queryByLabelText('Income counts as income')).not.toBeOnTheScreen();
    expect(screen.getByLabelText('Rent from tenant counts as income')).toBeOnTheScreen();
  });

  it('flips a custom kind to not counting as income', async () => {
    inflow.updateKind.mockResolvedValue({ ...rent, countsAsIncome: false });
    renderSettings();
    fireEvent(await screen.findByLabelText('Rent from tenant counts as income'), 'valueChange', false);
    await waitFor(() => expect(inflow.updateKind).toHaveBeenCalledWith('k6', { countsAsIncome: false }));
  });

  it('explains why a kind in use cannot be deleted', async () => {
    confirmAlerts();
    inflow.deleteKind.mockRejectedValue(new AxiosError('conflict', 'ERR_BAD_REQUEST', undefined, undefined, {
      status: 409, statusText: 'Conflict', headers: {}, config: { headers: new AxiosHeaders() },
      data: { message: 'This kind is still used. Move those payments and senders to another kind first.', details: { rows: 2, senders: 1 } },
    }));
    renderSettings();
    fireEvent.press(await screen.findByLabelText('Delete Rent from tenant'));
    expect(await screen.findByText(/Used by 2 payments and 1 sender\./)).toBeOnTheScreen();
  });

  it('forgets a sender', async () => {
    confirmAlerts();
    inflow.forgetSender.mockResolvedValue({} as never);
    renderSettings();
    fireEvent.press(await screen.findByLabelText('Forget ASHA VERMA'));
    await waitFor(() => expect(inflow.forgetSender).toHaveBeenCalledWith('r1'));
  });
});
