import { describe, it, expect, vi, beforeEach } from 'vitest';
import { render, screen, waitFor, fireEvent } from '@testing-library/react';
import { AppleReauthPrompt } from './AppleReauthPrompt';
import { isAppleLoginConfigured, loadAppleIdServices } from '../lib/appleIdentity';

// Mocked wholesale, as in AppleSignInButton.test.tsx: the script mechanics are covered by
// appleIdentity.test.ts.
vi.mock('../lib/appleIdentity', () => ({
  isAppleLoginConfigured: vi.fn(),
  loadAppleIdServices: vi.fn(),
}));

beforeEach(() => {
  vi.mocked(isAppleLoginConfigured).mockReset();
  vi.mocked(loadAppleIdServices).mockReset();
});

describe('AppleReauthPrompt', () => {
  it('explains itself instead of rendering nothing when Sign in with Apple is not configured', () => {
    vi.mocked(isAppleLoginConfigured).mockReturnValue(false);

    render(<AppleReauthPrompt onCredential={vi.fn()} onError={vi.fn()} />);

    expect(screen.getByText(/Sign in with Apple isn't available right now/)).toBeInTheDocument();
    expect(screen.queryByRole('button', { name: /sign in with apple/i })).not.toBeInTheDocument();
  });

  it('hands back only the fresh identity token, not the display name', async () => {
    vi.mocked(isAppleLoginConfigured).mockReturnValue(true);
    vi.mocked(loadAppleIdServices).mockResolvedValue({
      init: vi.fn(),
      signIn: vi.fn().mockResolvedValue({
        authorization: { id_token: 'fresh-apple-id-token' },
        user: { name: { firstName: 'Test', lastName: 'User' } },
      }),
    } as any);
    const onCredential = vi.fn();

    render(<AppleReauthPrompt onCredential={onCredential} onError={vi.fn()} />);
    expect(screen.getByText(/This account signs in with Apple/)).toBeInTheDocument();
    fireEvent.click(screen.getByRole('button', { name: /sign in with apple/i }));

    await waitFor(() => expect(onCredential).toHaveBeenCalledWith('fresh-apple-id-token'));
    expect(onCredential.mock.calls[0]).toHaveLength(1);
  });
});
