import { describe, it, expect, vi } from 'vitest';
import { render, screen, waitFor } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { SecurityPane } from './SecurityPane';
import { deviceApi } from '../../api/endpoints';
import type { AccountUserState } from './useAccountUser';

vi.mock('../../api/endpoints', () => ({
  deviceApi: { list: vi.fn(), revoke: vi.fn() },
  passwordChangeApi: { start: vi.fn(), verifyOtp: vi.fn(), complete: vi.fn() },
}));

function user(overrides: Partial<AccountUserState> = {}): AccountUserState {
  return {
    phoneNumber: '+919876543210', // synthetic-ok: invented test number
    phoneVerified: true, passwordChangedAt: null, signInMethod: 'PASSWORD',
    lowBalanceThreshold: 2000, timezone: 'Asia/Kolkata', ...overrides,
  };
}

describe('SecurityPane', () => {
  it('shows the active session and lets the user sign it out', async () => {
    vi.mocked(deviceApi.list).mockResolvedValue([
      { id: 's1', current: false, browser: 'Chrome', device: 'macOS', lastSeenAt: null, lastSeenIp: null, sessionStartedAt: null, sessionExpiresAt: null } as never,
    ]);
    vi.mocked(deviceApi.revoke).mockResolvedValue(undefined as never);
    render(<SecurityPane user={user()} loading={false} loadError={false} onUserUpdate={vi.fn()} />);
    await screen.findByText('Chrome on macOS');
    await userEvent.click(screen.getByRole('button', { name: 'Sign out this device' }));
    await waitFor(() => expect(deviceApi.revoke).toHaveBeenCalledWith('s1'));
  });
});
