import { describe, it, expect, vi, beforeEach, afterEach } from 'vitest';
import { render, screen } from '@testing-library/react';
import { MemoryRouter } from 'react-router-dom';
import { QueryClient, QueryClientProvider } from '@tanstack/react-query';
import { AddTransactionModal } from './AddTransactionModal';
import { accountsApi, categoriesApi } from '../api/endpoints';

vi.mock('../api/endpoints', () => ({
  accountsApi: { list: vi.fn() },
  categoriesApi: { list: vi.fn() },
  transactionsApi: { create: vi.fn() },
}));

const ACCOUNTS = [{ id: 'a-1', name: 'HDFC Savings', accountType: 'SAVINGS' }] as never;

function renderModal() {
  const queryClient = new QueryClient({ defaultOptions: { queries: { retry: false, gcTime: 0 } } });
  return render(
    <MemoryRouter>
      <QueryClientProvider client={queryClient}>
        <AddTransactionModal onClose={vi.fn()} onSaved={vi.fn()} />
      </QueryClientProvider>
    </MemoryRouter>
  );
}

beforeEach(() => {
  vi.mocked(accountsApi.list).mockReset().mockResolvedValue(ACCOUNTS);
  vi.mocked(categoriesApi.list).mockReset().mockResolvedValue([]);
});

afterEach(() => {
  vi.useRealTimers();
});

describe('AddTransactionModal', () => {
  it('defaults the date to today in the browser timezone, not a UTC-shifted day', async () => {
    // Regression test for the bug fixed in mobile's AddTransactionSheet.test.tsx (see that file's
    // identical test): `new Date().toISOString().slice(0, 10)` converts to UTC before slicing, so
    // for anyone east of UTC (IST included) the window between local midnight and UTC catching up
    // to the same calendar day silently defaults to yesterday. Pin TZ ahead of UTC and the clock
    // inside that window so this fails against the old code regardless of the machine running it.
    const originalTz = process.env.TZ;
    process.env.TZ = 'Asia/Kolkata';
    vi.useFakeTimers({ toFake: ['Date'] });
    vi.setSystemTime(new Date('2026-09-12T01:00:00'));
    try {
      renderModal();
      const dateInput = await screen.findByLabelText('Date') as HTMLInputElement;
      expect(dateInput.value).toBe('2026-09-12');
    } finally {
      process.env.TZ = originalTz;
    }
  });

  it('excludes credit card accounts from the account picker', async () => {
    vi.mocked(accountsApi.list).mockResolvedValue([
      { id: 'a-1', name: 'HDFC Savings', accountType: 'SAVINGS' },
      { id: 'a-2', name: 'HDFC Credit Card', accountType: 'CREDIT_CARD' },
    ] as never);
    renderModal();
    const select = await screen.findByLabelText('Account') as HTMLSelectElement;
    const optionLabels = Array.from(select.options).map((o) => o.textContent);
    expect(optionLabels).toEqual(['HDFC Savings']);
  });

  it('blocks manual entry with a dedicated message when every account is a credit card', async () => {
    vi.mocked(accountsApi.list).mockResolvedValue([
      { id: 'a-2', name: 'HDFC Credit Card', accountType: 'CREDIT_CARD' },
    ] as never);
    renderModal();
    expect(await screen.findByText(/Manual entry isn't available for credit card accounts/)).toBeTruthy();
    expect(screen.queryByLabelText('Account')).toBeNull();
  });
});
