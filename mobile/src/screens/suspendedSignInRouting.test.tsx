import type { ReactElement } from 'react';
import { act, fireEvent, render, screen } from '@testing-library/react-native';
import type { NativeStackScreenProps } from '@react-navigation/native-stack';
import { AuthEntryScreen } from './AuthEntryScreen';
import { LoginScreen } from './LoginScreen';
import { RegisterScreen } from './RegisterScreen';
import { AUTH_ACCOUNT_SUSPENDED } from '../api/errorCodes';
import { ThemeProvider } from '../theme';
import type { AuthStackParamList } from '../navigation/types';

/**
 * Privacy workstream 10: every mobile sign-in path sends a suspended account's refusal (AUTH_018
 * carrying details.suspendedAccessToken) to the suspended screen instead of showing an error.
 * Password, email code and phone code on Login; Google and Apple on Login, AuthEntry and Register.
 */

const mockAuth = {
  login: jest.fn(),
  reactivate: jest.fn(),
  register: jest.fn(),
  loginWithGoogle: jest.fn(),
  loginWithApple: jest.fn(),
  loginWithEmailOtpRequest: jest.fn(),
  loginWithEmailOtpVerify: jest.fn(),
  loginWithPhoneOtp: jest.fn(),
  signedOutMessage: null as string | null,
  clearSignedOutMessage: jest.fn(),
};
jest.mock('../context/AuthContext', () => ({ useAuth: () => mockAuth }));

const mockSendPhoneCode = jest.fn();
const mockConfirmPhoneCode = jest.fn();
jest.mock('../lib/phoneAuth', () => ({
  sendPhoneVerificationCode: (...args: unknown[]) => mockSendPhoneCode(...args),
  confirmPhoneVerificationCode: (...args: unknown[]) => mockConfirmPhoneCode(...args),
}));

// Stand-ins that hand back a credential on press, so the tests drive each screen's own handlers.
jest.mock('../components/GoogleSignInButton', () => {
  const { Text } = jest.requireActual('react-native');
  return {
    isGoogleSignInConfigured: () => true,
    GoogleSignInButton: ({ onCredential }: { onCredential: (t: string) => void }) => (
      <Text onPress={() => onCredential('google-id-token')}>Google stand-in</Text>
    ),
  };
});
jest.mock('../components/AppleSignInButton', () => {
  const { Text } = jest.requireActual('react-native');
  return {
    AppleSignInButton: ({ onCredential }: { onCredential: (t: string, n?: string) => void }) => (
      <Text onPress={() => onCredential('apple-id-token')}>Apple stand-in</Text>
    ),
  };
});

const SUSPENDED_TOKEN = 'suspended-screen-token';
const REFUSAL_MESSAGE = 'Your account has been suspended.';

function suspendedError(details: Record<string, unknown> | null = { suspendedAccessToken: SUSPENDED_TOKEN }) {
  return Object.assign(new Error('Request failed'), {
    isAxiosError: true,
    response: { status: 403, data: { errorCode: AUTH_ACCOUNT_SUSPENDED, message: REFUSAL_MESSAGE, details } },
  });
}

const mockNavigate = jest.fn();

function renderAuthScreen(name: 'Login' | 'AuthEntry' | 'Register') {
  const navigation = { navigate: mockNavigate } as never;
  const route = { key: name, name, params: undefined } as never;
  const Screen = { Login: LoginScreen, AuthEntry: AuthEntryScreen, Register: RegisterScreen }[name] as (
    p: NativeStackScreenProps<AuthStackParamList, typeof name>
  ) => ReactElement;
  return render(
    <ThemeProvider>
      <Screen navigation={navigation} route={route} />
    </ThemeProvider>
  );
}

async function settle() {
  await act(async () => {});
}

function expectSuspendedScreenOpened() {
  expect(mockNavigate).toHaveBeenCalledWith('SuspendedAccount', { token: SUSPENDED_TOKEN });
  expect(screen.queryByText(REFUSAL_MESSAGE)).toBeNull();
}

beforeEach(() => {
  Object.values(mockAuth).forEach((v) => (typeof v === 'function' ? (v as jest.Mock).mockReset() : undefined));
  mockAuth.signedOutMessage = null;
  mockNavigate.mockReset();
  mockSendPhoneCode.mockReset();
  mockConfirmPhoneCode.mockReset();
});

describe('LoginScreen', () => {
  it('password: opens the suspended screen', async () => {
    mockAuth.login.mockRejectedValue(suspendedError());
    renderAuthScreen('Login');

    fireEvent.changeText(screen.getByLabelText('Email or mobile number'), 'someone@example.com');
    fireEvent.changeText(screen.getByLabelText('Password'), 'correct-password');
    fireEvent.press(screen.getByRole('button', { name: 'Sign in' }));
    await settle();

    expectSuspendedScreenOpened();
  });

  it('email code: opens the suspended screen', async () => {
    mockAuth.loginWithEmailOtpRequest.mockResolvedValue({ devCode: null });
    mockAuth.loginWithEmailOtpVerify.mockRejectedValue(suspendedError());
    renderAuthScreen('Login');

    fireEvent.changeText(screen.getByLabelText('Email or mobile number'), 'someone@example.com');
    fireEvent.press(screen.getByRole('button', { name: 'Login with OTP instead' }));
    await settle();
    fireEvent.press(screen.getByTestId('otp-send-code'));
    await settle();
    fireEvent.changeText(screen.getByTestId('otp-code-field'), '482913');
    fireEvent.press(screen.getByTestId('otp-verify'));
    await settle();

    expectSuspendedScreenOpened();
  });

  it('phone code: opens the suspended screen', async () => {
    mockSendPhoneCode.mockResolvedValue({ confirm: jest.fn() });
    mockConfirmPhoneCode.mockResolvedValue('firebase-id-token');
    mockAuth.loginWithPhoneOtp.mockRejectedValue(suspendedError());
    renderAuthScreen('Login');

    fireEvent.changeText(screen.getByLabelText('Email or mobile number'), '9876543210' /* synthetic-ok */);
    fireEvent.press(screen.getByRole('button', { name: 'Login with OTP instead' }));
    await settle();
    fireEvent.press(screen.getByTestId('otp-send-code'));
    await settle();
    fireEvent.changeText(screen.getByTestId('otp-code-field'), '482913');
    fireEvent.press(screen.getByTestId('otp-verify'));
    await settle();

    expect(mockAuth.loginWithPhoneOtp).toHaveBeenCalledWith('firebase-id-token');
    expectSuspendedScreenOpened();
  });

  it('Google: opens the suspended screen', async () => {
    mockAuth.loginWithGoogle.mockRejectedValue(suspendedError());
    renderAuthScreen('Login');

    await act(async () => { fireEvent.press(screen.getByText('Google stand-in')); });

    expectSuspendedScreenOpened();
  });

  it('Apple: opens the suspended screen', async () => {
    mockAuth.loginWithApple.mockRejectedValue(suspendedError());
    renderAuthScreen('Login');

    await act(async () => { fireEvent.press(screen.getByText('Apple stand-in')); });

    expectSuspendedScreenOpened();
  });

  // A suspended admin account is refused with AUTH_018 and no token: nothing to open, so the
  // refusal is shown as before.
  it('shows the refusal as an error when it carries no token', async () => {
    mockAuth.login.mockRejectedValue(suspendedError(null));
    renderAuthScreen('Login');

    fireEvent.changeText(screen.getByLabelText('Email or mobile number'), 'someone@example.com');
    fireEvent.changeText(screen.getByLabelText('Password'), 'correct-password');
    fireEvent.press(screen.getByRole('button', { name: 'Sign in' }));
    await settle();

    expect(mockNavigate).not.toHaveBeenCalled();
    expect(screen.getByText(REFUSAL_MESSAGE)).toBeTruthy();
  });

  it('shows why the last session was ended, once', async () => {
    mockAuth.signedOutMessage = 'This account has been suspended. Sign in to see why and what you can do.';
    renderAuthScreen('Login');
    await settle();

    expect(screen.getByText('This account has been suspended. Sign in to see why and what you can do.')).toBeTruthy();
    expect(mockAuth.clearSignedOutMessage).toHaveBeenCalledTimes(1);
  });
});

describe('AuthEntryScreen', () => {
  it('Google: opens the suspended screen', async () => {
    mockAuth.loginWithGoogle.mockRejectedValue(suspendedError());
    renderAuthScreen('AuthEntry');

    await act(async () => { fireEvent.press(screen.getByText('Google stand-in')); });

    expectSuspendedScreenOpened();
  });

  it('Apple: opens the suspended screen', async () => {
    mockAuth.loginWithApple.mockRejectedValue(suspendedError());
    renderAuthScreen('AuthEntry');

    await act(async () => { fireEvent.press(screen.getByText('Apple stand-in')); });

    expectSuspendedScreenOpened();
  });
});

describe('RegisterScreen', () => {
  it('Google: opens the suspended screen', async () => {
    mockAuth.loginWithGoogle.mockRejectedValue(suspendedError());
    renderAuthScreen('Register');

    await act(async () => { fireEvent.press(screen.getByText('Google stand-in')); });

    expectSuspendedScreenOpened();
  });

  it('Apple: opens the suspended screen', async () => {
    mockAuth.loginWithApple.mockRejectedValue(suspendedError());
    renderAuthScreen('Register');

    await act(async () => { fireEvent.press(screen.getByText('Apple stand-in')); });

    expectSuspendedScreenOpened();
  });
});
