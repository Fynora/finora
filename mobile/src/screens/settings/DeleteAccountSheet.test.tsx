import { act, fireEvent, render, screen, waitFor } from '@testing-library/react-native';
import { DeleteAccountSheet } from './DeleteAccountSheet';
import { accountLifecycleApi, passwordChangeApi } from '../../api/endpoints';
import { confirmPhoneVerificationCode, sendPhoneVerificationCode } from '../../lib/phoneAuth';

jest.mock('../../api/endpoints', () => ({
  passwordChangeApi: { start: jest.fn(), verifyOtp: jest.fn() },
  accountLifecycleApi: { deleteAccount: jest.fn() },
}));

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

const onClose = jest.fn();
const onDeleted = jest.fn();
const onContactSupport = jest.fn();

function renderSheet(signInMethod: 'PASSWORD' | 'GOOGLE' | 'APPLE' = 'PASSWORD') {
  return render(
    <DeleteAccountSheet
      onClose={onClose}
      onDeleted={onDeleted}
      signInMethod={signInMethod}
      onContactSupport={onContactSupport}
    />
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
  beforeEach(() => {
    onClose.mockReset();
    onDeleted.mockReset();
    onContactSupport.mockReset();
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

  // Store-readiness gate (S1): Google/Apple accounts get an honest "not yet available" message
  // instead of a re-auth form that has no password field to submit.
  it.each(['GOOGLE', 'APPLE'] as const)(
    'shows a support message instead of the password form for a %s account',
    (signInMethod) => {
      renderSheet(signInMethod);

      expect(screen.queryByLabelText('Current password')).toBeNull();
      expect(screen.getByText(/isn.t available in the app yet/)).toBeTruthy();

      fireEvent.press(screen.getByText('Contact Support'));
      expect(onContactSupport).toHaveBeenCalled();
    }
  );

  it('asks the backend to start, then has Firebase send the code', async () => {
    renderSheet();

    fireEvent.changeText(screen.getByLabelText('Current password'), 'CurrentPw1!');
    fireEvent.press(screen.getByRole('button', { name: /Send code/ }));
    await settle();

    await waitFor(() => expect(passwordApi.start).toHaveBeenCalledWith('CurrentPw1!'));
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

    await waitFor(() => expect(lifecycleApi.deleteAccount).toHaveBeenCalledWith('sess-1'));
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
});
