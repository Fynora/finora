import { act, fireEvent, render, screen, waitFor } from '@testing-library/react-native';
import type { NativeStackScreenProps } from '@react-navigation/native-stack';
import { ResetPasswordScreen } from './ResetPasswordScreen';
import { authApi } from '../api/endpoints';
import { confirmPhoneVerificationCode, sendPhoneVerificationCode, type PhoneConfirmation } from '../lib/phoneAuth';
import { ThemeProvider } from '../theme';
import type { AuthStackParamList } from '../navigation/types';

jest.mock('../api/endpoints', () => ({
  authApi: { verifyResetPasswordPhone: jest.fn(), resetPassword: jest.fn() },
}));
jest.mock('../lib/phoneAuth', () => ({
  sendPhoneVerificationCode: jest.fn(),
  confirmPhoneVerificationCode: jest.fn(),
}));

const api = authApi as jest.Mocked<typeof authApi>;
const sendCode = sendPhoneVerificationCode as jest.MockedFunction<typeof sendPhoneVerificationCode>;
const confirmCode = confirmPhoneVerificationCode as jest.MockedFunction<typeof confirmPhoneVerificationCode>;

type Props = NativeStackScreenProps<AuthStackParamList, 'ResetPassword'>;

const TOKEN = 'reset-token-abc';
// Invented, matching the value the rest of the suite uses. Declared once so the hygiene marker sits
// in one place rather than on every line that mentions it.
const LOCAL_PHONE = '9876543210'; // synthetic-ok: invented test number
const FULL_PHONE = '+919876543210'; // synthetic-ok: invented test number
const CONFIRMATION = { confirm: jest.fn() } as unknown as PhoneConfirmation;
const ID_TOKEN = 'firebase-id-token';

const mockReset = jest.fn();

// After a finished (or abandoned) reset the auth stack is rebuilt as [AuthEntry, Login]: a plain
// navigate('Login') would leave this screen -- with the typed password and a spent token -- one back
// press away, because on a cold start it sits directly above AuthEntry.
function signInStack(params?: { message: string }) {
  return { index: 1, routes: [{ name: 'AuthEntry' }, params ? { name: 'Login', params } : { name: 'Login' }] };
}

function renderScreen() {
  const navigation = { reset: mockReset } as unknown as Props['navigation'];
  const route = { key: 'ResetPassword', name: 'ResetPassword', params: { token: TOKEN } } as Props['route'];
  return render(
    <ThemeProvider>
      <ResetPasswordScreen navigation={navigation} route={route} />
    </ThemeProvider>
  );
}

function serverError(message: string) {
  return Object.assign(new Error('Request failed'), {
    isAxiosError: true,
    response: { status: 400, data: { message } },
  });
}

function firebaseError(code: string) {
  return Object.assign(new Error('firebase'), { code });
}

async function settle() {
  await act(async () => {});
}

async function press(label: string) {
  fireEvent.press(screen.getByText(label));
  await settle();
}

async function submitPhone() {
  fireEvent.changeText(screen.getByLabelText('Mobile number'), LOCAL_PHONE);
  await press('Send code');
}

async function submitCode(code = '123456') {
  fireEvent.changeText(screen.getByLabelText('Verification code'), code);
  await press('Verify code');
}

async function reachPasswordStep() {
  await submitPhone();
  await submitCode();
}

function typePasswords(password: string, confirm = password) {
  fireEvent.changeText(screen.getByLabelText('New password'), password);
  fireEvent.changeText(screen.getByLabelText('Confirm new password'), confirm);
}

describe('ResetPasswordScreen', () => {
  beforeEach(() => {
    jest.clearAllMocks();
    api.verifyResetPasswordPhone.mockResolvedValue({ message: 'Phone number confirmed.' });
    api.resetPassword.mockResolvedValue({ message: 'ok' });
    sendCode.mockResolvedValue(CONFIRMATION);
    confirmCode.mockResolvedValue(ID_TOKEN);
  });

  describe('phone step', () => {
    it('starts by asking for the phone number on the account', () => {
      renderScreen();

      expect(screen.getByText('Confirm your phone number')).toBeTruthy();
      expect(screen.getByLabelText('Mobile number')).toBeTruthy();
    });

    it('does not call anything for an invalid number', async () => {
      renderScreen();
      fireEvent.changeText(screen.getByLabelText('Mobile number'), '12345');
      await press('Send code');

      expect(api.verifyResetPasswordPhone).not.toHaveBeenCalled();
      expect(sendCode).not.toHaveBeenCalled();
    });

    it('checks the number against the account, and only then sends the SMS to that same number', async () => {
      renderScreen();
      await submitPhone();

      expect(api.verifyResetPasswordPhone).toHaveBeenCalledWith(TOKEN, FULL_PHONE);
      expect(sendCode).toHaveBeenCalledWith(FULL_PHONE);
      expect(api.verifyResetPasswordPhone.mock.invocationCallOrder[0]).toBeLessThan(sendCode.mock.invocationCallOrder[0]);
      expect(screen.getByText('Enter the code')).toBeTruthy();
    });

    it("shows the server's reason and sends no SMS when the number or link is rejected", async () => {
      api.verifyResetPasswordPhone.mockRejectedValue(serverError("That doesn't match the phone number on this account."));
      renderScreen();
      await submitPhone();

      expect(screen.getByText("That doesn't match the phone number on this account.")).toBeTruthy();
      expect(sendCode).not.toHaveBeenCalled();
      expect(screen.getByText('Confirm your phone number')).toBeTruthy();
    });

    it('stays on the phone step with a readable message when Firebase cannot send the SMS', async () => {
      sendCode.mockRejectedValue(firebaseError('auth/too-many-requests'));
      renderScreen();
      await submitPhone();

      expect(screen.getByText(/too many attempts/i)).toBeTruthy();
      expect(screen.getByText('Confirm your phone number')).toBeTruthy();
    });
  });

  describe('code step', () => {
    it('will not verify until six digits are entered', async () => {
      renderScreen();
      await submitPhone();
      await submitCode('123');

      expect(confirmCode).not.toHaveBeenCalled();
    });

    it('confirms the code with Firebase and moves on to choosing a password', async () => {
      renderScreen();
      await submitPhone();
      await submitCode('123456');

      expect(confirmCode).toHaveBeenCalledWith(CONFIRMATION, '123456');
      expect(screen.getByText('Choose a new password')).toBeTruthy();
    });

    it('extracts the digits from a pasted SMS', async () => {
      renderScreen();
      await submitPhone();
      await submitCode('Your code is 654321');

      expect(confirmCode).toHaveBeenCalledWith(CONFIRMATION, '654321');
    });

    it('shows a wrong code as a readable message and stays on the code step', async () => {
      confirmCode.mockRejectedValue(firebaseError('auth/invalid-verification-code'));
      renderScreen();
      await submitPhone();
      await submitCode('000000');

      expect(screen.getByText("That code doesn't match — check and try again.")).toBeTruthy();
      expect(screen.getByText('Enter the code')).toBeTruthy();
    });

    it('sends a fresh SMS to the same number on "Resend code"', async () => {
      renderScreen();
      await submitPhone();
      sendCode.mockClear();
      await press('Resend code');

      expect(sendCode).toHaveBeenCalledTimes(1);
      expect(sendCode).toHaveBeenCalledWith(FULL_PHONE);
    });

    it('goes back to the phone step on "Start over"', async () => {
      renderScreen();
      await submitPhone();
      await press('Start over');

      expect(screen.getByText('Confirm your phone number')).toBeTruthy();
    });
  });

  describe('password step', () => {
    it('will not submit a password shorter than 8 characters', async () => {
      renderScreen();
      await reachPasswordStep();
      typePasswords('short');
      await press('Reset password');

      expect(api.resetPassword).not.toHaveBeenCalled();
    });

    it('will not submit when the two passwords differ, and says so', async () => {
      renderScreen();
      await reachPasswordStep();
      typePasswords('long-enough-1', 'long-enough-2');
      await press('Reset password');

      expect(api.resetPassword).not.toHaveBeenCalled();
      expect(screen.getByText('Passwords do not match.')).toBeTruthy();
    });

    it('resets with the link token, the Firebase ID token and the new password, then returns to sign-in with a banner', async () => {
      renderScreen();
      await reachPasswordStep();
      typePasswords('a-new-password-1');
      await press('Reset password');

      expect(api.resetPassword).toHaveBeenCalledWith(TOKEN, ID_TOKEN, 'a-new-password-1');
      expect(mockReset).toHaveBeenCalledWith(
        signInStack({ message: 'Password reset successfully. Please sign in using your new password.' }),
      );
    });

    it('keeps the user on this step when the server rejects the password, and retries WITHOUT a second SMS', async () => {
      api.resetPassword
        .mockRejectedValueOnce(serverError('New password must be different from your current password.'))
        .mockResolvedValueOnce({ message: 'ok' });
      renderScreen();
      await reachPasswordStep();
      sendCode.mockClear();
      confirmCode.mockClear();

      typePasswords('same-as-current-1');
      await press('Reset password');
      expect(screen.getByText('New password must be different from your current password.')).toBeTruthy();
      expect(mockReset).not.toHaveBeenCalled();

      typePasswords('a-different-one-2');
      await press('Reset password');

      expect(api.resetPassword).toHaveBeenLastCalledWith(TOKEN, ID_TOKEN, 'a-different-one-2');
      expect(sendCode).not.toHaveBeenCalled();
      expect(confirmCode).not.toHaveBeenCalled();
      expect(mockReset).toHaveBeenCalledTimes(1);
    });

    it('submits once even if the button is pressed twice in the same tick', async () => {
      let resolve: (v: { message: string }) => void = () => {};
      api.resetPassword.mockReturnValue(new Promise((r) => { resolve = r; }));
      renderScreen();
      await reachPasswordStep();
      typePasswords('a-new-password-1');

      // One batched act: no re-render (and so no disabled button) can land between the two taps --
      // that gap is exactly what a real double tap exploits, and what useSingleFlight closes.
      const button = screen.getByText('Reset password');
      await act(async () => {
        fireEvent.press(button);
        fireEvent.press(button);
      });
      await act(async () => { resolve({ message: 'ok' }); });

      expect(api.resetPassword).toHaveBeenCalledTimes(1);
    });
  });

  it('offers a way back to sign-in from the first step, without a banner', async () => {
    renderScreen();
    await press('Back to sign in');

    await waitFor(() => expect(mockReset).toHaveBeenCalledWith(signInStack()));
  });
});
