import { StrictMode } from 'react';
import { describe, it, expect, vi, beforeEach } from 'vitest';
import { render, screen, waitFor } from '@testing-library/react';
import { MemoryRouter, useLocation } from 'react-router-dom';
import { QueryClient, QueryClientProvider } from '@tanstack/react-query';
import { ConnectedAppsPane } from './ConnectedAppsPane';
import { gmailApi } from '../../api/endpoints';

vi.mock('../../api/endpoints', () => ({
  gmailApi: { status: vi.fn(), connect: vi.fn(), confirmConnect: vi.fn(), disconnect: vi.fn(), syncNow: vi.fn() },
  entitlementsApi: { mine: vi.fn().mockResolvedValue({ planCode: 'PREMIUM', planName: 'Premium', features: { GMAIL_SYNC: true } }) },
}));

const NOT_CONNECTED = {
  available: true, connected: false, needsReconnect: false, googleEmail: null,
  grantedScopes: [], connectedAt: null, lastSyncedAt: null, lastDiscoveryAt: null, transactionsFound: 0, needsReview: 0,
};
const CONNECTED = { ...NOT_CONNECTED, connected: true, googleEmail: 'amy@example.com' };

/** Shows the router's current query string, so a test can see what was stripped from the URL. */
function CurrentSearch() {
  return <span data-testid="search">{useLocation().search}</span>;
}

function renderAt(entry: string) {
  // The not-connected state renders PremiumFeatureGate, which reads entitlements through a query.
  const queryClient = new QueryClient({ defaultOptions: { queries: { retry: false } } });
  return render(
    <StrictMode>
      <QueryClientProvider client={queryClient}>
        <MemoryRouter initialEntries={[entry]}>
          <ConnectedAppsPane />
          <CurrentSearch />
        </MemoryRouter>
      </QueryClientProvider>
    </StrictMode>,
  );
}

// Google's redirect connects nothing. It hands the browser a one-time ticket, and the mailbox is
// connected only when this signed-in session posts that ticket and the server accepts it.
describe('ConnectedAppsPane, back from Google', () => {
  beforeEach(() => {
    vi.mocked(gmailApi.confirmConnect).mockReset();
    vi.mocked(gmailApi.status).mockReset();
  });

  it('posts the ticket once, then says connected and loads the status', async () => {
    vi.mocked(gmailApi.confirmConnect).mockResolvedValue({} as never);
    vi.mocked(gmailApi.status).mockResolvedValue(CONNECTED as never);

    // StrictMode runs the mount effect twice, as the real app does in development. The ticket
    // is single-use, so a second post would be refused and replace the success with an error.
    renderAt('/app/settings?gmail=confirm&ticket=the-ticket');

    expect(await screen.findByText('Gmail connected.')).toBeInTheDocument();
    expect(await screen.findByText('amy@example.com')).toBeInTheDocument();
    expect(gmailApi.confirmConnect).toHaveBeenCalledTimes(1);
    expect(gmailApi.confirmConnect).toHaveBeenCalledWith('the-ticket');
  });

  it('does not ask for the status until the ticket has been answered', async () => {
    let answer: () => void = () => {};
    vi.mocked(gmailApi.confirmConnect).mockReturnValue(new Promise((resolve) => { answer = () => resolve({} as never); }));
    vi.mocked(gmailApi.status).mockResolvedValue(CONNECTED as never);

    renderAt('/app/settings?gmail=confirm&ticket=the-ticket');

    await waitFor(() => expect(gmailApi.confirmConnect).toHaveBeenCalled());
    expect(gmailApi.status).not.toHaveBeenCalled();
    expect(screen.queryByText('Gmail connected.')).not.toBeInTheDocument();

    answer();
    expect(await screen.findByText('amy@example.com')).toBeInTheDocument();
  });

  it("shows the server's reason when the ticket is refused, and nothing is connected", async () => {
    vi.mocked(gmailApi.confirmConnect).mockRejectedValue({
      response: { status: 403, data: { message: 'This Gmail connection was started from a different Fynora account, so nothing was connected.' } },
    });
    vi.mocked(gmailApi.status).mockResolvedValue(NOT_CONNECTED as never);

    renderAt('/app/settings?gmail=confirm&ticket=someone-elses-ticket');

    expect(await screen.findByText(/started from a different Fynora account/)).toBeInTheDocument();
    expect(screen.queryByText('Gmail connected.')).not.toBeInTheDocument();
    expect(await screen.findByRole('button', { name: 'Connect Gmail' })).toBeInTheDocument();
  });

  it('takes the ticket out of the address bar and keeps this pane open', async () => {
    vi.mocked(gmailApi.confirmConnect).mockResolvedValue({} as never);
    vi.mocked(gmailApi.status).mockResolvedValue(CONNECTED as never);

    renderAt('/app/settings?gmail=confirm&ticket=the-ticket');

    await waitFor(() => expect(screen.getByTestId('search').textContent).toBe('?tab=connected-apps'));
  });

  // The server never sends gmail=connected any more. A link that carries it must not be able to
  // put a "connected" message on screen, and must not post anything.
  it('ignores a link that only claims to be connected', async () => {
    vi.mocked(gmailApi.status).mockResolvedValue(NOT_CONNECTED as never);

    renderAt('/app/settings?gmail=connected');

    expect(await screen.findByRole('button', { name: 'Connect Gmail' })).toBeInTheDocument();
    expect(screen.queryByText('Gmail connected.')).not.toBeInTheDocument();
    expect(gmailApi.confirmConnect).not.toHaveBeenCalled();
  });

  it('reports a failure, without posting, when the return has no ticket', async () => {
    vi.mocked(gmailApi.status).mockResolvedValue(NOT_CONNECTED as never);

    renderAt('/app/settings?gmail=confirm');

    expect(await screen.findByText(/Couldn't connect Gmail/)).toBeInTheDocument();
    expect(gmailApi.confirmConnect).not.toHaveBeenCalled();
  });
});

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
