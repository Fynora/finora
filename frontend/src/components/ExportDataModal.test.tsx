import { describe, it, expect, vi, beforeEach } from 'vitest';
import { render, screen, waitFor, fireEvent } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { ExportDataModal } from './ExportDataModal';
import { accountLifecycleApi } from '../api/endpoints';
import { isGoogleLoginConfigured, loadGoogleIdentityServices } from '../lib/googleIdentity';
import { isAppleLoginConfigured, loadAppleIdServices } from '../lib/appleIdentity';

vi.mock('../api/endpoints', () => ({
  accountLifecycleApi: { exportData: vi.fn() },
}));

// See GoogleSignInButton.test.tsx's own doc comment for why this is mocked wholesale.
vi.mock('../lib/googleIdentity', () => ({
  isGoogleLoginConfigured: vi.fn(),
  loadGoogleIdentityServices: vi.fn(),
}));

// Same wholesale mock for Apple's script -- see AppleSignInButton.test.tsx.
vi.mock('../lib/appleIdentity', () => ({
  isAppleLoginConfigured: vi.fn(),
  loadAppleIdServices: vi.fn(),
}));

describe('ExportDataModal', () => {
  beforeEach(() => {
    vi.mocked(accountLifecycleApi.exportData).mockReset().mockResolvedValue(undefined);
  });

  describe('a password account', () => {
    it('requests the export with the entered password', async () => {
      const user = userEvent.setup();
      const onClose = vi.fn();
      render(<ExportDataModal onClose={onClose} signInMethod="PASSWORD" />);

      await user.type(screen.getByLabelText(/current password/i), 'OldPass123!');
      await user.click(screen.getByRole('button', { name: /export my data/i }));

      await waitFor(() => expect(accountLifecycleApi.exportData).toHaveBeenCalledWith('OldPass123!', null, null));
      expect(onClose).toHaveBeenCalled();
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

    it('offers no password field, and no separate submit button', async () => {
      render(<ExportDataModal onClose={vi.fn()} signInMethod="GOOGLE" />);

      await waitFor(() => expect(loadGoogleIdentityServices).toHaveBeenCalled());
      expect(screen.queryByLabelText(/current password/i)).not.toBeInTheDocument();
      expect(screen.queryByRole('button', { name: /export my data/i })).not.toBeInTheDocument();
    });

    it('requests the export with the fresh Google credential instead of a password', async () => {
      const initialize = vi.fn();
      vi.mocked(loadGoogleIdentityServices).mockResolvedValue({ initialize, renderButton: vi.fn() } as any);
      const onClose = vi.fn();
      render(<ExportDataModal onClose={onClose} signInMethod="GOOGLE" />);
      await waitFor(() => expect(initialize).toHaveBeenCalled());

      const { callback } = initialize.mock.calls[0][0];
      callback({ credential: 'fresh-google-id-token' });

      await waitFor(() => expect(accountLifecycleApi.exportData).toHaveBeenCalledWith(null, 'fresh-google-id-token', null));
      expect(onClose).toHaveBeenCalled();
    });
  });

  describe('an Apple Sign-In account', () => {
    beforeEach(() => {
      vi.mocked(isAppleLoginConfigured).mockReturnValue(true);
      vi.mocked(loadAppleIdServices).mockResolvedValue({
        init: vi.fn(),
        signIn: vi.fn().mockResolvedValue({ authorization: { id_token: 'fresh-apple-id-token' } }),
      } as any);
    });

    it('asks to verify with Apple, with no password field', () => {
      render(<ExportDataModal onClose={vi.fn()} signInMethod="APPLE" />);

      expect(screen.queryByLabelText(/current password/i)).not.toBeInTheDocument();
      expect(screen.getByText(/Verify with Apple to confirm/)).toBeInTheDocument();
    });

    it('requests the export with a fresh Apple credential instead of a password', async () => {
      const onClose = vi.fn();
      render(<ExportDataModal onClose={onClose} signInMethod="APPLE" />);

      fireEvent.click(screen.getByRole('button', { name: /sign in with apple/i }));

      await waitFor(() => expect(accountLifecycleApi.exportData).toHaveBeenCalledWith(null, null, 'fresh-apple-id-token'));
      expect(onClose).toHaveBeenCalled();
    });
  });

  describe('keyboard', () => {
    it('closes on Escape', async () => {
      const user = userEvent.setup();
      const onClose = vi.fn();
      render(<ExportDataModal onClose={onClose} signInMethod="PASSWORD" />);

      expect(screen.getByRole('dialog', { name: 'Export My Data' })).toBeInTheDocument();
      await user.keyboard('{Escape}');
      expect(onClose).toHaveBeenCalledTimes(1);
    });

    // Same rule as the backdrop and the Close button: an export already in flight is not
    // abandoned by dismissing its dialog.
    it('ignores Escape while the export request is in flight', async () => {
      const user = userEvent.setup();
      const onClose = vi.fn();
      vi.mocked(accountLifecycleApi.exportData).mockReturnValue(new Promise(() => {}));
      render(<ExportDataModal onClose={onClose} signInMethod="PASSWORD" />);

      await user.type(screen.getByLabelText(/current password/i), 'OldPass123!');
      await user.click(screen.getByRole('button', { name: /export my data/i }));
      await waitFor(() => expect(accountLifecycleApi.exportData).toHaveBeenCalled());
      await user.keyboard('{Escape}');

      expect(onClose).not.toHaveBeenCalled();
    });
  });
});
