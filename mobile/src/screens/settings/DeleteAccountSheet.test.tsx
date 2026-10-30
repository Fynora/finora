import { act, fireEvent, render, screen, waitFor } from '@testing-library/react-native';
import { GoogleSignin } from '@react-native-google-signin/google-signin';
import * as AppleAuthentication from 'expo-apple-authentication';
import { DeleteAccountSheet } from './DeleteAccountSheet';
import { accountLifecycleApi, passwordChangeApi } from '../../api/endpoints';
import { confirmPhoneVerificationCode, sendPhoneVerificationCode } from '../../lib/phoneAuth';
import { ThemeProvider } from '../../theme';

jest.mock('../../api/endpoints', () => ({
  passwordChangeApi: { start: jest.fn(), verifyOtp: jest.fn() },
  accountLifecycleApi: { deleteAccount: jest.fn() },
}));

// Has its own coverage (StoreSubscriptionDeletionWarning.test.tsx); this suite pins the deletion
// flow itself, which renders the same with or without it.
jest.mock('./StoreSubscriptionDeletionWarning', () => ({ StoreSubscriptionDeletionWarning: () => null }));

jest.mock('../../lib/phoneAuth', () => ({
  sendPhoneVerificationCode: jest.fn(),
  confirmPhoneVerificationCode: jest.fn(),
}));

const passwordApi = passwordChangeApi as jest.Mocked<typeof passwordChangeApi>;
const lifecycleApi = accountLifecycleApi as jest.Mocked<typeof accountLifecycleApi>;
const sendCode = sendPhoneVerificationCode as jest.MockedFunction<typeof sendPhoneVerificationCode>;
const confirmCode = confirmPhoneVerificationCode as jest.MockedFunction<typeof confirmPhoneVerificationCode>;

// Invented, matching the value ChangePasswordSheet.test.tsx's suite uses for the same fixture.
const PHONE = '+919876543210'; // synthetic-ok: invented test number
const MASKED_PHONE = '+•••••••••210';

const mockedGoogleSignin = GoogleSignin as jest.Mocked<typeof GoogleSignin>;

const onClose = jest.fn();
const onDeleted = jest.fn();

function renderSheet(signInMethod: 'PASSWORD' | 'GOOGLE' | 'APPLE' = 'PASSWORD') {
  // The Google/Apple buttons read useThemeSetting(), which needs a real ThemeProvider.
  return render(
    <ThemeProvider>
      <DeleteAccountSheet
        onClose={onClose}
        onDeleted={onDeleted}
        signInMethod={signInMethod}
      />
    </ThemeProvider>
  );
}

async function settle() {
  await act(async () => {});
}

/** Drives the flow up to the confirm step, same shape as ChangePasswordSheet.test.tsx's
 *  reachNewPasswordStep helper. */
async function reachConfirmStep() {
  fireEvent.changeText(screen.getByLabelText('Current password'), 'CurrentPw1!');
  fireEvent.press(screen.getByRole('button', { name: /Send code/ }));
  await settle();
  fireEvent.changeText(screen.getByLabelText('Verification code'), '123456');
  fireEvent.press(screen.getByRole('button', { name: /Verify/ }));
  await settle();
}

describe('DeleteAccountSheet', () => {
  const originalGoogleEnv = process.env.EXPO_PUBLIC_GOOGLE_WEB_CLIENT_ID;

  afterEach(() => {
    process.env.EXPO_PUBLIC_GOOGLE_WEB_CLIENT_ID = originalGoogleEnv;
  });

  beforeEach(() => {
    process.env.EXPO_PUBLIC_GOOGLE_WEB_CLIENT_ID = 'test-web-client-id.apps.googleusercontent.com';
    onClose.mockReset();
    onDeleted.mockReset();
    passwordApi.start.mockReset().mockResolvedValue({
      sessionId: 'sess-1', phoneNumber: PHONE, maskedPhone: MASKED_PHONE,
    });
    passwordApi.verifyOtp.mockReset().mockResolvedValue({ message: 'ok' });
    lifecycleApi.deleteAccount.mockReset().mockResolvedValue({ message: 'Account deleted.' });
    sendCode.mockReset().mockResolvedValue({ confirm: jest.fn() } as never);
    confirmCode.mockReset().mockResolvedValue('firebase-id-token');
  });

  it('starts by asking for the current password for a PASSWORD account', () => {
    renderSheet('PASSWORD');

    expect(screen.getByLabelText('Current password')).toBeTruthy();
    expect(screen.queryByLabelText('Verification code')).toBeNull();
  });

  // Google/Apple accounts have no password anyone knows: a fresh Google/Apple credential starts
  // the same session, and the phone code and confirm steps are the same as for everyone.
  it('asks a GOOGLE account to verify with Google, not for a password', () => {
    renderSheet('GOOGLE');

    expect(screen.getByText('Sign in with Google')).toBeTruthy();
    expect(screen.queryByLabelText('Current password')).toBeNull();
    expect(screen.queryByText(/isn.t available in the app yet/)).toBeNull();
  });

  it('deletes a GOOGLE account end to end: Google credential, phone code, confirm', async () => {
    mockedGoogleSignin.signIn.mockResolvedValue({
      type: 'success',
      data: { idToken: 'a-google-id-token', user: {}, scopes: [], serverAuthCode: null },
    } as never);
    renderSheet('GOOGLE');

    fireEvent.press(screen.getByText('Sign in with Google'));
    await settle();
    await waitFor(() => expect(passwordApi.start).toHaveBeenCalledWith(null, 'a-google-id-token', null));
    expect(sendCode).toHaveBeenCalledWith(PHONE);

    fireEvent.changeText(await screen.findByLabelText('Verification code'), '123456');
    fireEvent.press(screen.getByRole('button', { name: /Verify/ }));
    await settle();
    await waitFor(() => expect(passwordApi.verifyOtp).toHaveBeenCalledWith('sess-1', 'firebase-id-token'));

    fireEvent.press(screen.getByRole('checkbox'));
    fireEvent.press(screen.getByRole('button', { name: /Permanently Delete/i }));
    await settle();

    await waitFor(() => expect(lifecycleApi.deleteAccount).toHaveBeenCalledWith('sess-1', undefined));
    expect(onDeleted).toHaveBeenCalled();
  });

  it('starts an APPLE account with a fresh Apple credential', async () => {
    jest.mocked(AppleAuthentication.signInAsync).mockResolvedValue({
      identityToken: 'an-apple-id-token', fullName: null,
    } as never);
    renderSheet('APPLE');

    fireEvent.press(screen.getByText('Sign in with Apple'));
    await settle();

    await waitFor(() => expect(passwordApi.start).toHaveBeenCalledWith(null, null, 'an-apple-id-token'));
    expect(await screen.findByLabelText('Verification code')).toBeTruthy();
  });

  /** A Google account with no phone on file: the backend refuses the start with its own message. */
  it("shows the server's message when the account has no phone number to send a code to", async () => {
    passwordApi.start.mockReset().mockRejectedValue(
      Object.assign(new Error('bad'), {
        isAxiosError: true,
        response: { status: 400, data: { message: 'Add a phone number to your account before changing your password or deleting your account.' } },
      })
    );
    mockedGoogleSignin.signIn.mockResolvedValue({
      type: 'success',
      data: { idToken: 'a-google-id-token', user: {}, scopes: [], serverAuthCode: null },
    } as never);
    renderSheet('GOOGLE');

    fireEvent.press(screen.getByText('Sign in with Google'));
    await settle();

    expect(await screen.findByText(/Add a phone number to your account/)).toBeTruthy();
    expect(sendCode).not.toHaveBeenCalled();
    expect(screen.getByText('Sign in with Google')).toBeTruthy();
  });

  it('asks the backend to start, then has Firebase send the code', async () => {
    renderSheet();

    fireEvent.changeText(screen.getByLabelText('Current password'), 'CurrentPw1!');
    fireEvent.press(screen.getByRole('button', { name: /Send code/ }));
    await settle();

    await waitFor(() => expect(passwordApi.start).toHaveBeenCalledWith('CurrentPw1!', null, null));
    expect(sendCode).toHaveBeenCalledWith(PHONE);
    expect(await screen.findByLabelText('Verification code')).toBeTruthy();
  });

  it('stays on the password step and explains when the current password is rejected', async () => {
    passwordApi.start.mockReset().mockRejectedValue(
      Object.assign(new Error('bad'), {
        isAxiosError: true,
        response: { status: 400, data: { message: 'Current password is incorrect.' } },
      })
    );
    renderSheet();

    fireEvent.changeText(screen.getByLabelText('Current password'), 'wrong');
    fireEvent.press(screen.getByRole('button', { name: /Send code/ }));
    await settle();

    expect(await screen.findByText('Current password is incorrect.')).toBeTruthy();
    expect(screen.queryByLabelText('Verification code')).toBeNull();
  });

  // Bug fix (review): "Didn't get a code? Start over" had no disabled={submitting} guard, so it
  // could reset step/sessionId/confirmation while submitOtp() was still in flight -- and the
  // stale call's own success path (setStep('confirm')) could then land after the reset, yanking
  // the UI forward again with state the user had just abandoned.
  it('disables Start over while verifyOtp() is in flight, so it cannot race a stale response', async () => {
    let resolveVerify: (() => void) | undefined;
    passwordApi.verifyOtp.mockReset().mockReturnValue(
      new Promise((resolve) => { resolveVerify = () => resolve({ message: 'ok' }); })
    );
    renderSheet();
    fireEvent.changeText(screen.getByLabelText('Current password'), 'CurrentPw1!');
    fireEvent.press(screen.getByRole('button', { name: /Send code/ }));
    await settle();
    fireEvent.changeText(screen.getByLabelText('Verification code'), '123456');
    fireEvent.press(screen.getByRole('button', { name: /Verify/ }));
    await settle();

    expect(
      screen.getByRole('button', { name: /Start over/ }).props.accessibilityState.disabled
    ).toBe(true);

    resolveVerify?.();
    await settle();
  });

  it('reaches the danger confirm step once the OTP verifies', async () => {
    renderSheet();
    await reachConfirmStep();

    await waitFor(() => expect(passwordApi.verifyOtp).toHaveBeenCalledWith('sess-1', 'firebase-id-token'));
    expect(await screen.findByText('This cannot be undone.')).toBeTruthy();
    expect(screen.getByText('Permanently Delete Account')).toBeTruthy();
  });

  it('will not delete until the understanding checkbox is checked', async () => {
    renderSheet();
    await reachConfirmStep();

    expect(
      screen.getByRole('button', { name: /Permanently Delete Account/ }).props.accessibilityState.disabled
    ).toBe(true);

    fireEvent.press(screen.getByLabelText('I understand this permanently deletes my account and cannot be undone.'));

    expect(
      screen.getByRole('button', { name: /Permanently Delete Account/ }).props.accessibilityState.disabled
    ).toBe(false);
  });

  it('deletes the account with the OTP-verified session once confirmed', async () => {
    renderSheet();
    await reachConfirmStep();

    fireEvent.press(screen.getByLabelText('I understand this permanently deletes my account and cannot be undone.'));
    fireEvent.press(screen.getByRole('button', { name: /Permanently Delete Account/ }));
    await settle();

    // No reason chosen: the optional reason is left undefined (Gate 2a spec §5).
    await waitFor(() => expect(lifecycleApi.deleteAccount).toHaveBeenCalledWith('sess-1', undefined));
    expect(onDeleted).toHaveBeenCalled();
  });

  it('shows an error and does not sign the user out when deletion fails', async () => {
    lifecycleApi.deleteAccount.mockReset().mockRejectedValue(
      Object.assign(new Error('bad'), {
        isAxiosError: true,
        response: { status: 409, data: { message: 'This session has expired. Please start over.' } },
      })
    );
    renderSheet();
    await reachConfirmStep();

    fireEvent.press(screen.getByLabelText('I understand this permanently deletes my account and cannot be undone.'));
    fireEvent.press(screen.getByRole('button', { name: /Permanently Delete Account/ }));
    await settle();

    expect(await screen.findByText('This session has expired. Please start over.')).toBeTruthy();
    expect(onDeleted).not.toHaveBeenCalled();
  });

  it('starts over back at the current-password step', async () => {
    renderSheet();
    fireEvent.changeText(screen.getByLabelText('Current password'), 'CurrentPw1!');
    fireEvent.press(screen.getByRole('button', { name: /Send code/ }));
    await settle();

    fireEvent.press(screen.getByText(/Start over/));
    await settle();

    expect(screen.getByLabelText('Current password')).toBeTruthy();
    expect(screen.queryByLabelText('Verification code')).toBeNull();
  });

  // Bug fix (review): the confirm step used to have no explicit recovery from a failed
  // submitDelete() beyond the generic Cancel button (which closes the whole sheet rather than
  // visibly offering a retry) -- it now gets the same "Start over" link the OTP step already had.
  it('offers Start over on the confirm step too, resetting the understanding checkbox', async () => {
    renderSheet();
    await reachConfirmStep();
    fireEvent.press(screen.getByLabelText('I understand this permanently deletes my account and cannot be undone.'));

    fireEvent.press(screen.getByRole('button', { name: /Start over/ }));
    await settle();

    expect(screen.getByLabelText('Current password')).toBeTruthy();
    expect(screen.queryByText('This cannot be undone.')).toBeNull();

    // A fresh flow through to a new confirm step must not carry the earlier checkbox state --
    // otherwise a second attempt could reach "Permanently Delete Account" pre-confirmed.
    await reachConfirmStep();
    expect(
      screen.getByRole('button', { name: /Permanently Delete Account/ }).props.accessibilityState.disabled
    ).toBe(true);
  });

  /** Gate 2a spec §5: one optional question on the confirm step. */
  describe('the reason for leaving', () => {
    const UNDERSTOOD = 'I understand this permanently deletes my account and cannot be undone.';

    function radioLabels(): string[] {
      return screen.getAllByRole('radio').map((r) => String(r.props.accessibilityLabel));
    }

    it('asks the exact question, with the six answers in order and none chosen', async () => {
      renderSheet();
      await reachConfirmStep();

      expect(screen.getByText("Before you go: what's the main reason?")).toBeTruthy();
      expect(radioLabels()).toEqual([
        "I couldn't get my statements in", "I'm not comfortable with my data here", "It didn't show me anything useful",
        'I use another app', "I don't need it anymore", 'Something else',
      ]);
      expect(screen.getAllByRole('radio').every((r) => r.props.accessibilityState?.checked === false)).toBe(true);
    });

    it('sendsTheChosenReason', async () => {
      renderSheet();
      await reachConfirmStep();

      fireEvent.press(screen.getByRole('radio', { name: 'I use another app' }));
      fireEvent.press(screen.getByLabelText(UNDERSTOOD));
      fireEvent.press(screen.getByRole('button', { name: /Permanently Delete Account/ }));
      await settle();

      await waitFor(() => expect(lifecycleApi.deleteAccount).toHaveBeenCalledWith('sess-1', 'USE_ANOTHER_APP'));
    });

    it('sendsNoReasonWhenNoneChosen', async () => {
      renderSheet();
      await reachConfirmStep();

      fireEvent.press(screen.getByLabelText(UNDERSTOOD));
      fireEvent.press(screen.getByRole('button', { name: /Permanently Delete Account/ }));
      await settle();

      await waitFor(() => expect(lifecycleApi.deleteAccount).toHaveBeenCalled());
      expect(lifecycleApi.deleteAccount.mock.calls[0][1]).toBeUndefined();
    });

    // Skipping is always allowed: a tap on the chosen answer clears it, so a stray tap is not sent.
    it('tapping the chosen answer again clears it', async () => {
      renderSheet();
      await reachConfirmStep();

      fireEvent.press(screen.getByRole('radio', { name: 'Something else' }));
      expect(screen.getByRole('radio', { name: 'Something else' }).props.accessibilityState.checked).toBe(true);
      fireEvent.press(screen.getByRole('radio', { name: 'Something else' }));
      expect(screen.getByRole('radio', { name: 'Something else' }).props.accessibilityState.checked).toBe(false);

      fireEvent.press(screen.getByLabelText(UNDERSTOOD));
      fireEvent.press(screen.getByRole('button', { name: /Permanently Delete Account/ }));
      await settle();
      await waitFor(() => expect(lifecycleApi.deleteAccount).toHaveBeenCalled());
      expect(lifecycleApi.deleteAccount.mock.calls[0][1]).toBeUndefined();
    });

    it('deleteEnabledWithoutAReason', async () => {
      renderSheet();
      await reachConfirmStep();

      fireEvent.press(screen.getByLabelText(UNDERSTOOD));

      expect(
        screen.getByRole('button', { name: /Permanently Delete Account/ }).props.accessibilityState.disabled
      ).toBe(false);
    });

    it('Start over clears the chosen reason', async () => {
      renderSheet();
      await reachConfirmStep();
      fireEvent.press(screen.getByRole('radio', { name: 'I use another app' }));

      fireEvent.press(screen.getByRole('button', { name: /Start over/ }));
      await settle();
      await reachConfirmStep();

      expect(screen.getByRole('radio', { name: 'I use another app' }).props.accessibilityState.checked).toBe(false);
    });
  });
});
