import { Alert } from 'react-native';
import { fireEvent, render, screen } from '@testing-library/react-native';
import { QueryClient, QueryClientProvider } from '@tanstack/react-query';
import { DeviceSessionsSection } from './DeviceSessionsSection';
import { devicesApi, type DeviceSession } from '../../api/endpoints';
import { ThemeProvider } from '../../theme';

jest.mock('../../api/endpoints', () => ({
  devicesApi: { list: jest.fn(), revoke: jest.fn() },
}));

const devices = devicesApi as jest.Mocked<typeof devicesApi>;

function renderSection() {
  const queryClient = new QueryClient({ defaultOptions: { queries: { retry: false, gcTime: 0 } } });
  return render(
    <QueryClientProvider client={queryClient}>
      <ThemeProvider>
        <DeviceSessionsSection />
      </ThemeProvider>
    </QueryClientProvider>
  );
}

function session(over: Partial<DeviceSession> = {}): DeviceSession {
  return {
    id: 'dev-1', sessionId: 'sess-1', current: false, browser: 'Chrome', device: 'Windows',
    lastSeenIp: '203.0.113.7', lastSeenAt: '2026-08-04T09:00:00Z', createdAt: '2026-07-01T09:00:00Z',
    expiresAt: '2026-09-01T09:00:00Z', sessionStartedAt: '2026-07-01T09:00:00Z',
    sessionExpiresAt: null,
    ...over,
  };
}

describe('DeviceSessionsSection', () => {
  beforeEach(() => {
    devices.list.mockReset();
    devices.revoke.mockReset().mockResolvedValue({ message: 'ok' });
  });

  /**
   * Phase 4 (Medium-Tier Parity, item 2). DeviceSessionDto gained `current`/`sessionExpiresAt`
   * after this screen was first written -- this locks in that the row this request is actually
   * running on gets badged, and no other row does.
   */
  it('badges the current device and no other', async () => {
    devices.list.mockResolvedValue([
      session({ id: 'dev-1', current: true, device: 'This Phone', browser: null }),
      session({ id: 'dev-2', current: false, device: 'Old Laptop', browser: null }),
    ]);
    renderSection();

    await screen.findByText('This Phone');
    expect(screen.getByText('This device')).toBeTruthy();
    // Exactly one badge, not one per row.
    expect(screen.getAllByText('This device')).toHaveLength(1);
  });

  it('shows when the absolute session cap expires', async () => {
    const inFiveDays = new Date(Date.now() + 5 * 24 * 60 * 60 * 1000).toISOString();
    devices.list.mockResolvedValue([session({ sessionExpiresAt: inFiveDays })]);
    renderSection();

    expect(await screen.findByText('Session expires in 5 days')).toBeTruthy();
  });

  // Null means the absolute session cap is disabled server-side -- rendering a fabricated expiry
  // would be worse than showing nothing, the same "don't guess" rule this app applies everywhere
  // else a figure can be genuinely unknown.
  it('shows no expiry line when the session cap is disabled', async () => {
    devices.list.mockResolvedValue([session({ sessionExpiresAt: null })]);
    renderSection();

    await screen.findByText('Chrome on Windows');
    expect(screen.queryByText(/Session expires/)).toBeNull();
  });

  it('still lets the current device be signed out, with the same confirmation as any other', async () => {
    const alertSpy = jest.spyOn(Alert, 'alert').mockImplementation(() => {});
    devices.list.mockResolvedValue([session({ current: true, device: 'This Phone', browser: null })]);
    renderSection();
    await screen.findByText('This Phone');

    fireEvent.press(screen.getByLabelText('Sign out This Phone'));

    expect(alertSpy).toHaveBeenCalledWith(
      'Sign out this device?',
      expect.stringContaining('This Phone'),
      expect.anything()
    );
    alertSpy.mockRestore();
  });
});
