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
});
