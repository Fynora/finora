import { act, fireEvent, render, screen, waitFor } from '@testing-library/react-native';
import { QueryClient, QueryClientProvider } from '@tanstack/react-query';
import { GmailConnectionSection } from './GmailConnectionSection';
import { gmailApi, type GmailConnectionStatus } from '../../api/endpoints';
import { connectGmail } from '../../lib/gmailAuth';
import { ThemeProvider } from '../../theme';

jest.mock('../../api/endpoints', () => ({
  gmailApi: { status: jest.fn(), syncNow: jest.fn(), disconnect: jest.fn() },
}));

jest.mock('../../lib/gmailAuth', () => ({
  connectGmail: jest.fn(),
}));

const mockNavigate = jest.fn();
jest.mock('@react-navigation/native', () => ({
  useNavigation: () => ({ navigate: mockNavigate }),
}));

const api = gmailApi as jest.Mocked<typeof gmailApi>;
const mockedConnectGmail = connectGmail as jest.MockedFunction<typeof connectGmail>;

function renderSection() {
  const queryClient = new QueryClient({ defaultOptions: { queries: { retry: false, gcTime: 0 } } });
  return render(
    <QueryClientProvider client={queryClient}>
      <ThemeProvider>
        <GmailConnectionSection />
      </ThemeProvider>
    </QueryClientProvider>
  );
}

const NOT_CONNECTED: GmailConnectionStatus = {
  connected: false, status: null, needsReconnect: false, googleEmail: null, grantedScopes: [],
  connectedAt: null, lastSyncedAt: null, lastDiscoveryAt: null, transactionsFound: 0, needsReview: 0,
  available: true,
};

const CONNECTED: GmailConnectionStatus = {
  connected: true, status: 'CONNECTED', needsReconnect: false, googleEmail: 'me@example.com',
  grantedScopes: ['https://www.googleapis.com/auth/gmail.readonly'],
  connectedAt: '2026-08-01T00:00:00Z', lastSyncedAt: '2026-09-01T00:00:00Z',
  lastDiscoveryAt: '2026-09-01T00:00:00Z', transactionsFound: 12, needsReview: 3, available: true,
};

beforeEach(() => {
  jest.clearAllMocks();
  mockNavigate.mockReset();
});

describe('GmailConnectionSection', () => {
  it("shows the deployment-unavailable message when the feature isn't configured", async () => {
    api.status.mockResolvedValue({ ...NOT_CONNECTED, available: false });
    renderSection();

    expect(await screen.findByText(/isn.t available on this deployment yet/)).toBeTruthy();
  });

  it('offers Connect Gmail when nothing is connected', async () => {
    api.status.mockResolvedValue(NOT_CONNECTED);
    renderSection();

    expect(await screen.findByText('Connect Gmail')).toBeTruthy();
  });

  it('starts the OAuth flow and shows a success notice once connected', async () => {
    api.status.mockResolvedValue(NOT_CONNECTED);
    mockedConnectGmail.mockResolvedValue('connected');
    renderSection();
    await screen.findByText('Connect Gmail');

    await act(async () => fireEvent.press(screen.getByText('Connect Gmail')));

    expect(mockedConnectGmail).toHaveBeenCalledTimes(1);
    expect(await screen.findByText('Gmail connected.')).toBeTruthy();
  });

  it('shows a neutral notice, not an error, when the user declines', async () => {
    api.status.mockResolvedValue(NOT_CONNECTED);
    mockedConnectGmail.mockResolvedValue('declined');
    renderSection();
    await screen.findByText('Connect Gmail');

    await act(async () => fireEvent.press(screen.getByText('Connect Gmail')));

    expect(await screen.findByText('Gmail connection was cancelled.')).toBeTruthy();
  });

  it('shows nothing extra when the auth session is simply dismissed', async () => {
    api.status.mockResolvedValue(NOT_CONNECTED);
    mockedConnectGmail.mockResolvedValue('cancelled');
    renderSection();
    await screen.findByText('Connect Gmail');

    await act(async () => fireEvent.press(screen.getByText('Connect Gmail')));

    expect(screen.queryByText('Gmail connected.')).toBeNull();
    expect(screen.queryByText(/Couldn.t connect Gmail/)).toBeNull();
  });

  it('shows an error when the flow fails', async () => {
    api.status.mockResolvedValue(NOT_CONNECTED);
    mockedConnectGmail.mockResolvedValue('failed');
    renderSection();
    await screen.findByText('Connect Gmail');

    await act(async () => fireEvent.press(screen.getByText('Connect Gmail')));

    expect(await screen.findByText(/Couldn.t connect Gmail/)).toBeTruthy();
  });

  it('offers Reconnect Gmail when the connection needs reauth', async () => {
    api.status.mockResolvedValue({ ...NOT_CONNECTED, needsReconnect: true, googleEmail: 'me@example.com' });
    renderSection();

    expect(await screen.findByText('Reconnect Gmail')).toBeTruthy();
    expect(await screen.findByText('Needs reconnect')).toBeTruthy();
  });

  it('shows connection details, permissions, and metrics once connected', async () => {
    api.status.mockResolvedValue(CONNECTED);
    renderSection();

    expect(await screen.findByText('me@example.com')).toBeTruthy();
    expect(screen.getByText('Connected')).toBeTruthy();
    expect(screen.getByText(/Read Gmail messages/)).toBeTruthy();
    expect(screen.getByText('12')).toBeTruthy();
    expect(screen.getByText('3')).toBeTruthy();
  });

  it('offers no Review button when nothing needs review', async () => {
    api.status.mockResolvedValue({ ...CONNECTED, needsReview: 0 });
    renderSection();

    await screen.findByText('me@example.com');
    expect(screen.queryByText(/^Review/)).toBeNull();
  });

  it('navigates to GmailReview when Review is pressed', async () => {
    api.status.mockResolvedValue(CONNECTED);
    renderSection();

    fireEvent.press(await screen.findByText('Review 3'));

    expect(mockNavigate).toHaveBeenCalledWith('GmailReview');
  });

  it('syncs now and refreshes the status', async () => {
    api.status.mockResolvedValue(CONNECTED);
    api.syncNow.mockResolvedValue(undefined as never);
    renderSection();
    await screen.findByText('me@example.com');

    await act(async () => fireEvent.press(screen.getByLabelText('Sync Gmail now')));

    expect(api.syncNow).toHaveBeenCalledTimes(1);
    await waitFor(() => expect(api.status).toHaveBeenCalledTimes(2));
  });

  it('shows a sync error without disturbing the rest of the card', async () => {
    api.status.mockResolvedValue(CONNECTED);
    api.syncNow.mockRejectedValue(
      Object.assign(new Error('cooldown'), { isAxiosError: true, response: { status: 429, data: { message: 'Gmail was synced recently -- try again in a moment.' } } })
    );
    renderSection();
    await screen.findByText('me@example.com');

    await act(async () => fireEvent.press(screen.getByLabelText('Sync Gmail now')));

    expect(await screen.findByText('Gmail was synced recently -- try again in a moment.')).toBeTruthy();
  });

  it('disconnects and refreshes the status', async () => {
    api.status.mockResolvedValue(CONNECTED);
    api.disconnect.mockResolvedValue(undefined as never);
    renderSection();
    await screen.findByText('me@example.com');

    await act(async () => fireEvent.press(screen.getByText('Disconnect')));

    expect(api.disconnect).toHaveBeenCalledTimes(1);
    await waitFor(() => expect(api.status).toHaveBeenCalledTimes(2));
  });
});
