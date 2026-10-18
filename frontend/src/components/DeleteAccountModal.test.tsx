import { describe, it, expect, vi, beforeEach } from 'vitest';
import { render, screen, waitFor } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { DeleteAccountModal } from './DeleteAccountModal';
import { passwordChangeApi, accountLifecycleApi } from '../api/endpoints';
import { sendPhoneVerificationCode, confirmPhoneVerificationCode } from '../lib/phoneAuth';
import { isGoogleLoginConfigured, loadGoogleIdentityServices } from '../lib/googleIdentity';

vi.mock('../api/endpoints', () => ({
  passwordChangeApi: { start: vi.fn(), verifyOtp: vi.fn() },
  accountLifecycleApi: { deleteAccount: vi.fn() },
}));

vi.mock('../lib/phoneAuth', () => ({
  sendPhoneVerificationCode: vi.fn(),
  confirmPhoneVerificationCode: vi.fn(),
  resetPhoneVerification: vi.fn(),
}));

// See GoogleSignInButton.test.tsx's own doc comment for why this is mocked wholesale.
vi.mock('../lib/googleIdentity', () => ({
  isGoogleLoginConfigured: vi.fn(),
  loadGoogleIdentityServices: vi.fn(),
}));

const FAKE_CONFIRMATION = { confirm: vi.fn() } as any;

describe('DeleteAccountModal', () => {
  beforeEach(() => {
    vi.mocked(passwordChangeApi.start).mockReset().mockResolvedValue({
      sessionId: 'session-1', phoneNumber: '+919876543705', maskedPhone: '+•••••••••705', // synthetic-ok
    });
    vi.mocked(sendPhoneVerificationCode).mockReset().mockResolvedValue(FAKE_CONFIRMATION);
    vi.mocked(confirmPhoneVerificationCode).mockReset().mockResolvedValue('fake-firebase-id-token');
    vi.mocked(passwordChangeApi.verifyOtp).mockReset().mockResolvedValue({ message: 'Verified.' });
    vi.mocked(accountLifecycleApi.deleteAccount).mockReset().mockResolvedValue({ message: 'Account deleted.' });
  });

  describe('a password account', () => {
    it('starts a session with the entered password and advances to the OTP step', async () => {
      const user = userEvent.setup();
      render(<DeleteAccountModal onClose={vi.fn()} onDeleted={vi.fn()} signInMethod="PASSWORD" />);

      await user.type(screen.getByLabelText(/current password/i), 'OldPass123!');
      await user.click(screen.getByRole('button', { name: /send code/i }));

      await waitFor(() => expect(passwordChangeApi.start).toHaveBeenCalledWith('OldPass123!', null));
      expect(await screen.findByText(/\+•••••••••705/)).toBeInTheDocument();
    });
  });

  describe('a Google Sign-In account', () => {
    beforeEach(() => {
      vi.stubEnv('VITE_GOOGLE_LOGIN_CLIENT_ID', 'test-client-id.apps.googleusercontent.com');
      vi.mocked(isGoogleLoginConfigured).mockReturnValue(true);
      vi.mocked(loadGoogleIdentityServices).mockResolvedValue({
        initialize: vi.fn(), renderButton: vi.fn(),
      } as any);
    });

    it('offers no password field at all', async () => {
      render(<DeleteAccountModal onClose={vi.fn()} onDeleted={vi.fn()} signInMethod="GOOGLE" />);

      await waitFor(() => expect(loadGoogleIdentityServices).toHaveBeenCalled());
      expect(screen.queryByLabelText(/current password/i)).not.toBeInTheDocument();
    });

    it('starts a session with the fresh Google credential instead of a password', async () => {
      const initialize = vi.fn();
      vi.mocked(loadGoogleIdentityServices).mockResolvedValue({ initialize, renderButton: vi.fn() } as any);
      render(<DeleteAccountModal onClose={vi.fn()} onDeleted={vi.fn()} signInMethod="GOOGLE" />);
      await waitFor(() => expect(initialize).toHaveBeenCalled());

      const { callback } = initialize.mock.calls[0][0];
      callback({ credential: 'fresh-google-id-token' });

      await waitFor(() => expect(passwordChangeApi.start).toHaveBeenCalledWith(null, 'fresh-google-id-token'));
      expect(await screen.findByText(/\+•••••••••705/)).toBeInTheDocument();
    });
  });

  describe('the OTP step', () => {
    async function advanceToOtpStep(user: ReturnType<typeof userEvent.setup>) {
      render(<DeleteAccountModal onClose={vi.fn()} onDeleted={vi.fn()} signInMethod="PASSWORD" />);
      await user.type(screen.getByLabelText(/current password/i), 'OldPass123!');
      await user.click(screen.getByRole('button', { name: /send code/i }));
      await screen.findByLabelText(/verification code/i);
    }

    // Otherwise a click on "Start over" while verifyOtp() is still in flight resets
    // step/sessionId/confirmation, and the stale call's own success path (setStep('confirm')) can
    // land afterward -- reaching the delete-confirmation step with sessionId already wiped out from
    // under it, so submitDelete()'s own `if (!sessionId ...) return` guard silently no-ops and the
    // user is stuck on a screen that cannot submit.
    it('disables "Start over" while verifyOtp() is in flight', async () => {
      const user = userEvent.setup();
      let resolveVerify!: (v: { message: string }) => void;
      vi.mocked(passwordChangeApi.verifyOtp).mockReset().mockReturnValue(
        new Promise((resolve) => { resolveVerify = resolve; }) as never
      );
      await advanceToOtpStep(user);

      await user.type(screen.getByLabelText(/verification code/i), '654321');
      await user.click(screen.getByRole('button', { name: /^verify$/i }));

      expect(await screen.findByRole('button', { name: /start over/i })).toBeDisabled();

      resolveVerify({ message: 'ok' });
      await waitFor(() =>
        expect(screen.getByRole('button', { name: /permanently delete account/i })).toBeInTheDocument()
      );
    });
  });

  /** Gate 2a spec §5: one optional question on the confirm step. */
  describe('the reason for leaving', () => {
    async function advanceToConfirmStep(user: ReturnType<typeof userEvent.setup>) {
      render(<DeleteAccountModal onClose={vi.fn()} onDeleted={vi.fn()} signInMethod="PASSWORD" />);
      await user.type(screen.getByLabelText(/current password/i), 'OldPass123!');
      await user.click(screen.getByRole('button', { name: /send code/i }));
      await user.type(await screen.findByLabelText(/verification code/i), '654321');
      await user.click(screen.getByRole('button', { name: /^verify$/i }));
      await screen.findByRole('button', { name: /permanently delete account/i });
    }

    it('asks the exact question, with the six answers in order', async () => {
      const user = userEvent.setup();
      await advanceToConfirmStep(user);

      const group = screen.getByRole('radiogroup', { name: "Before you go: what's the main reason?" });
      expect(Array.from(group.querySelectorAll('input[type="radio"]')).map((r) => (r as HTMLInputElement).labels?.[0]?.textContent))
        .toEqual([
          "I couldn't get my statements in", "I'm not comfortable with my data here", "It didn't show me anything useful",
          'I use another app', "I don't need it anymore", 'Something else',
        ]);
      expect(screen.getAllByRole('radio').every((r) => !(r as HTMLInputElement).checked)).toBe(true);
    });

    it('sendsTheChosenReason', async () => {
      const user = userEvent.setup();
      await advanceToConfirmStep(user);

      await user.click(screen.getByRole('radio', { name: 'I use another app' }));
      await user.click(screen.getByRole('checkbox'));
      await user.click(screen.getByRole('button', { name: /permanently delete account/i }));

      await waitFor(() => expect(accountLifecycleApi.deleteAccount).toHaveBeenCalledWith('session-1', 'USE_ANOTHER_APP'));
    });

    it('sendsNoReasonWhenNoneChosen', async () => {
      const user = userEvent.setup();
      await advanceToConfirmStep(user);

      await user.click(screen.getByRole('checkbox'));
      await user.click(screen.getByRole('button', { name: /permanently delete account/i }));

      await waitFor(() => expect(accountLifecycleApi.deleteAccount).toHaveBeenCalledWith('session-1', undefined));
    });

    // Skipping is always allowed: a tap on the chosen answer clears it, so a stray tap is not sent.
    it('tapping the chosen answer again clears it', async () => {
      const user = userEvent.setup();
      await advanceToConfirmStep(user);

      await user.click(screen.getByRole('radio', { name: 'Something else' }));
      await user.click(screen.getByRole('radio', { name: 'Something else' }));
      await user.click(screen.getByRole('checkbox'));
      await user.click(screen.getByRole('button', { name: /permanently delete account/i }));

      expect(screen.getByRole('radio', { name: 'Something else' })).not.toBeChecked();
      await waitFor(() => expect(accountLifecycleApi.deleteAccount).toHaveBeenCalledWith('session-1', undefined));
    });

    it('deleteEnabledWithoutAReason', async () => {
      const user = userEvent.setup();
      await advanceToConfirmStep(user);

      await user.click(screen.getByRole('checkbox'));

      expect(screen.getByRole('button', { name: /permanently delete account/i })).toBeEnabled();
    });

    it('has no text input on the confirm step', async () => {
      const user = userEvent.setup();
      await advanceToConfirmStep(user);

      expect(screen.queryByRole('textbox')).toBeNull();
    });
  });
});
