import { describe, it, expect, vi } from 'vitest';
import { render, screen } from '@testing-library/react';
import { MemoryRouter } from 'react-router-dom';
import { ConnectedAppsPane } from './ConnectedAppsPane';
import { gmailApi } from '../../api/endpoints';

vi.mock('../../api/endpoints', () => ({
  gmailApi: { status: vi.fn(), connect: vi.fn(), disconnect: vi.fn(), syncNow: vi.fn() },
  entitlementsApi: { mine: vi.fn().mockResolvedValue({ planCode: 'PREMIUM', planName: 'Premium', features: { GMAIL_SYNC: true } }) },
}));

describe('ConnectedAppsPane', () => {
  it('shows the connected state with the account email', async () => {
    vi.mocked(gmailApi.status).mockResolvedValue({
      available: true, connected: true, needsReconnect: false, googleEmail: 'amy@example.com',
      grantedScopes: [], connectedAt: null, lastSyncedAt: null, lastDiscoveryAt: null, transactionsFound: 3, needsReview: 1,
    } as never);
    render(<MemoryRouter><ConnectedAppsPane /></MemoryRouter>);
    expect(await screen.findByText('amy@example.com')).toBeInTheDocument();
  });

  // The bug this test exists for: a connection whose discovery keeps failing (lastDiscoveryAt
  // stays null) but whose extraction is still draining an existing backlog (lastSyncedAt IS set)
  // must not show "Never synced yet" next to transactions the user can already see in review.
  it('shows Last synced from lastSyncedAt even when lastDiscoveryAt is null', async () => {
    vi.mocked(gmailApi.status).mockResolvedValue({
      available: true, connected: true, needsReconnect: false, googleEmail: 'amy@example.com',
      grantedScopes: [], connectedAt: null, lastSyncedAt: new Date().toISOString(),
      lastDiscoveryAt: null, transactionsFound: 3, needsReview: 1,
    } as never);
    render(<MemoryRouter><ConnectedAppsPane /></MemoryRouter>);

    await screen.findByText('amy@example.com');
    expect(screen.queryByText('Never synced yet')).not.toBeInTheDocument();
    expect(screen.getByText(/^Last synced/)).toBeInTheDocument();
  });

  // A malformed timestamp must never silently win over a valid one -- `NaN >= x` is always
  // false in JS, so a naive comparison would prefer garbage the moment either side fails to
  // parse, discarding a perfectly good date. Found in review, not by a failing test.
  it('prefers a valid timestamp over a malformed one on either side', async () => {
    vi.mocked(gmailApi.status).mockResolvedValue({
      available: true, connected: true, needsReconnect: false, googleEmail: 'amy@example.com',
      grantedScopes: [], connectedAt: null, lastSyncedAt: new Date().toISOString(),
      lastDiscoveryAt: 'not-a-real-date', transactionsFound: 3, needsReview: 1,
    } as never);
    render(<MemoryRouter><ConnectedAppsPane /></MemoryRouter>);

    await screen.findByText('amy@example.com');
    expect(screen.queryByText('Never synced yet')).not.toBeInTheDocument();
    expect(screen.getByText(/^Last synced/)).toBeInTheDocument();
  });
});
