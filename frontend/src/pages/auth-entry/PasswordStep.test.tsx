import { describe, it, expect, vi } from 'vitest';
import { render, screen, waitFor } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { MemoryRouter } from 'react-router-dom';
import { PasswordStep } from './PasswordStep';
import { AuthProvider } from '../../context/AuthContext';
import { authApi } from '../../api/endpoints';

vi.mock('../../api/endpoints', () => ({
  authApi: { login: vi.fn(), google: vi.fn(), apple: vi.fn(), logout: vi.fn() },
  userApi: { get: vi.fn(), update: vi.fn() },
}));

function renderStep(props: Partial<Parameters<typeof PasswordStep>[0]> = {}) {
  const onSuccess = vi.fn();
  const onNotYou = vi.fn();
  render(
    <MemoryRouter>
      <AuthProvider>
        <PasswordStep identifier="jane@example.com" banner={null} onSuccess={onSuccess} onNotYou={onNotYou} {...props} />
      </AuthProvider>
    </MemoryRouter>
  );
  return { onSuccess, onNotYou };
}

describe('PasswordStep', () => {
  it('prefills the identifier field from props', () => {
    renderStep();
    expect(screen.getByLabelText('Email or mobile number')).toHaveValue('jane@example.com');
  });

  it('shows the banner prop when present', () => {
    renderStep({ banner: 'Password updated successfully. Please sign in using your new password.' });
    expect(screen.getByText('Password updated successfully. Please sign in using your new password.')).toBeInTheDocument();
  });

  it('calls onSuccess with phoneVerified on a successful login', async () => {
    vi.mocked(authApi.login).mockResolvedValue({
      data: { token: 't', refreshToken: 'r', email: 'jane@example.com', fullName: 'Jane', phoneVerified: true },
    } as any);
    const { onSuccess } = renderStep();

    await userEvent.type(screen.getByLabelText('Password'), 'correct-password-1');
    await userEvent.click(screen.getByRole('button', { name: /sign in/i }));

    await waitFor(() => expect(onSuccess).toHaveBeenCalledWith(true));
  });

  it('does NOT call onSuccess when login() rejects -- forcing the password step to render is not authentication', async () => {
    vi.mocked(authApi.login).mockImplementation(async () => {
      throw Object.assign(new Error('Invalid credentials.'), {
        response: { data: { message: 'Invalid credentials.' } },
      });
    });
    const { onSuccess } = renderStep();

    await userEvent.type(screen.getByLabelText('Password'), 'wrong-password');
    await userEvent.click(screen.getByRole('button', { name: /sign in/i }));

    await waitFor(() => expect(screen.getByText('Invalid credentials.')).toBeInTheDocument());
    expect(onSuccess).not.toHaveBeenCalled();
  });

  // PasswordStep's identifier field is editable (see its own doc comment -- "lets the user
  // correct a mistyped identifier without going all the way back to IDENTIFY"), so it needs the
  // same format gate IdentifyStep does: editing it into something that's neither a real email nor
  // a real phone number should be caught here too, not sent straight to /auth/login.
  it('shows an error and does not call login() when the identifier is edited into something that looks like neither an email nor a phone number', async () => {
    vi.mocked(authApi.login).mockClear(); // clean call count for the assertion below
    const { onSuccess } = renderStep();
    const identifierField = screen.getByLabelText('Email or mobile number');

    await userEvent.clear(identifierField);
    await userEvent.type(identifierField, '123@');
    await userEvent.type(screen.getByLabelText('Password'), 'some-password');
    await userEvent.click(screen.getByRole('button', { name: /sign in/i }));

    expect(await screen.findByText('Enter a valid email address or 10-digit mobile number.')).toBeInTheDocument();
    expect(authApi.login).not.toHaveBeenCalled();
    expect(onSuccess).not.toHaveBeenCalled();
  });

  it('calls onNotYou when "Not you?" is clicked', async () => {
    const { onNotYou } = renderStep();
    await userEvent.click(screen.getByRole('button', { name: /not you/i }));
    expect(onNotYou).toHaveBeenCalled();
  });

  // Otherwise a click on "Not you?" while login() is still in flight fires the parent's
  // handleNotYou -- which unmounts this step and clears identifier/banner state, explicitly to
  // protect a shared computer (see AuthEntry.tsx's own doc comment on handleNotYou) -- while the
  // stale login() call keeps running in its own closure regardless of the unmount. If it then
  // succeeds, onSuccess still fires and navigates the browser into the authenticated app under
  // the credentials just typed, defeating the exact "not me, back out" the button promises.
  it('disables "Not you?" while login() is in flight', async () => {
    let resolveLogin!: (v: unknown) => void;
    vi.mocked(authApi.login).mockReturnValue(
      new Promise((resolve) => { resolveLogin = resolve; }) as never
    );
    renderStep();

    await userEvent.type(screen.getByLabelText('Password'), 'correct-password-1');
    await userEvent.click(screen.getByRole('button', { name: /sign in/i }));

    expect(screen.getByRole('button', { name: /not you/i })).toBeDisabled();

    resolveLogin({
      data: { token: 't', refreshToken: 'r', email: 'jane@example.com', fullName: 'Jane', phoneVerified: true },
    });
    await waitFor(() => expect(screen.getByRole('button', { name: /not you/i })).not.toBeDisabled());
  });
});
