import { describe, it, expect, vi, beforeEach } from 'vitest';
import { render, screen } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { MemoryRouter } from 'react-router-dom';
import { BankSyncPane } from './BankSyncPane';
import { accountAggregatorApi } from '../../api/endpoints';

vi.mock('../../api/endpoints', () => ({
  accountAggregatorApi: { list: vi.fn(), initiate: vi.fn(), disconnect: vi.fn() },
}));

// Bank Sync is not offered yet (lib/features.ts). The pane reads the flag at render time, so a getter
// on a mutable holder covers both states: closed (the shipped default) and opened.
const mockFeatures = vi.hoisted(() => ({ bankSyncConnectEnabled: false }));
vi.mock('../../lib/features', () => ({
  get BANK_SYNC_CONNECT_ENABLED() { return mockFeatures.bankSyncConnectEnabled; },
}));

beforeEach(() => {
  mockFeatures.bankSyncConnectEnabled = false;
  vi.mocked(accountAggregatorApi.initiate).mockReset();
});

describe('BankSyncPane', () => {
  it('shows a linked account and its status', async () => {
    vi.mocked(accountAggregatorApi.list).mockResolvedValue([
      { id: 'l1', fiType: 'DEPOSIT', status: 'ACTIVE', consentExpiresAt: null, lastSyncedAt: null, lastSyncStatus: null, statusChangedAt: '2026-08-01T00:00:00Z' },
    ] as never);
    render(<MemoryRouter><BankSyncPane /></MemoryRouter>);
    expect(await screen.findByText('Bank Account')).toBeInTheDocument();
    expect(screen.getByText('Connected')).toBeInTheDocument();
  });

  it('shows a disabled Coming soon button, and starts nothing when it is clicked, while Bank Sync is closed', async () => {
    vi.mocked(accountAggregatorApi.list).mockResolvedValue([] as never);
    render(<MemoryRouter><BankSyncPane /></MemoryRouter>);
    const button = await screen.findByRole('button', { name: 'Coming soon' });
    expect(button).toBeDisabled();
    expect(screen.queryByText('Connect a Bank Account')).toBeNull();
    await userEvent.click(button);
    expect(accountAggregatorApi.initiate).not.toHaveBeenCalled();
  });

  it('still lets a person disconnect an existing link while Bank Sync is closed', async () => {
    vi.mocked(accountAggregatorApi.list).mockResolvedValue([
      { id: 'l1', fiType: 'DEPOSIT', status: 'ACTIVE', consentExpiresAt: null, lastSyncedAt: null, lastSyncStatus: null, statusChangedAt: '2026-08-01T00:00:00Z' },
    ] as never);
    render(<MemoryRouter><BankSyncPane /></MemoryRouter>);
    expect(await screen.findByRole('button', { name: 'Disconnect' })).toBeEnabled();
    expect(screen.getByRole('button', { name: 'Coming soon' })).toBeDisabled();
  });

  it('offers Connect a Bank Account and starts the flow when the flag is on', async () => {
    mockFeatures.bankSyncConnectEnabled = true;
    vi.mocked(accountAggregatorApi.list).mockResolvedValue([] as never);
    vi.mocked(accountAggregatorApi.initiate).mockResolvedValue({ redirectUrl: null } as never);
    render(<MemoryRouter><BankSyncPane /></MemoryRouter>);
    const button = await screen.findByRole('button', { name: 'Connect a Bank Account' });
    expect(screen.queryByText('Coming soon')).toBeNull();
    await userEvent.click(button);
    expect(accountAggregatorApi.initiate).toHaveBeenCalledTimes(1);
    expect(await screen.findByText('This connection attempt is already in progress.')).toBeInTheDocument();
  });
});
