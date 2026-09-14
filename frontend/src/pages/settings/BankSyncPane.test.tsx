import { describe, it, expect, vi } from 'vitest';
import { render, screen } from '@testing-library/react';
import { MemoryRouter } from 'react-router-dom';
import { BankSyncPane } from './BankSyncPane';
import { accountAggregatorApi } from '../../api/endpoints';

vi.mock('../../api/endpoints', () => ({
  accountAggregatorApi: { list: vi.fn(), initiate: vi.fn(), disconnect: vi.fn() },
}));

describe('BankSyncPane', () => {
  it('shows a linked account and its status', async () => {
    vi.mocked(accountAggregatorApi.list).mockResolvedValue([
      { id: 'l1', fiType: 'DEPOSIT', status: 'ACTIVE', consentExpiresAt: null, lastSyncedAt: null, lastSyncStatus: null, statusChangedAt: '2026-08-01T00:00:00Z' },
    ] as never);
    render(<MemoryRouter><BankSyncPane /></MemoryRouter>);
    expect(await screen.findByText('Bank Account')).toBeInTheDocument();
    expect(screen.getByText('Connected')).toBeInTheDocument();
  });
});
