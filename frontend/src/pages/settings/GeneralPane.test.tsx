import { describe, it, expect, vi } from 'vitest';
import { render, screen, waitFor } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { ThemeProvider } from '../../context/ThemeContext';
import { AuthProvider } from '../../context/AuthContext';
import { GeneralPane } from './GeneralPane';
import { userApi, onboardingApi, authApi } from '../../api/endpoints';
import type { AccountUserState } from './useAccountUser';

vi.mock('../../api/endpoints', () => ({
  userApi: { update: vi.fn() },
  onboardingApi: { reset: vi.fn().mockResolvedValue(undefined) },
  authApi: { refresh: vi.fn().mockRejectedValue(new Error('no session')) },
}));

function user(overrides: Partial<AccountUserState> = {}): AccountUserState {
  return {
    phoneNumber: '', phoneVerified: false, passwordChangedAt: null, signInMethod: 'PASSWORD',
    lowBalanceThreshold: 2000, timezone: 'Asia/Kolkata', ...overrides,
  };
}

function renderPane(props: Partial<React.ComponentProps<typeof GeneralPane>> = {}) {
  const onUserUpdate = vi.fn();
  render(
    <AuthProvider>
      <ThemeProvider>
        <GeneralPane user={user()} loading={false} loadError={false} onUserUpdate={onUserUpdate} {...props} />
      </ThemeProvider>
    </AuthProvider>
  );
  return { onUserUpdate };
}

describe('GeneralPane', () => {
  it('saves the low balance threshold and calls onUserUpdate with the server response', async () => {
    vi.mocked(userApi.update).mockResolvedValue({ lowBalanceThreshold: 5000, timezone: 'Asia/Kolkata' } as never);
    const { onUserUpdate } = renderPane();
    await userEvent.clear(screen.getByLabelText(/low balance alert/i));
    await userEvent.type(screen.getByLabelText(/low balance alert/i), '5000');
    await userEvent.click(screen.getByRole('button', { name: 'Save preferences' }));
    await waitFor(() => expect(onUserUpdate).toHaveBeenCalledWith(expect.objectContaining({ lowBalanceThreshold: 5000 })));
  });

  it('every button label is sentence case, not all caps', () => {
    renderPane();
    for (const btn of screen.getAllByRole('button')) {
      expect(btn.textContent).not.toBe(btn.textContent?.toUpperCase());
    }
  });
});
