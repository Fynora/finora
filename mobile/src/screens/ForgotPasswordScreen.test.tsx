import { act, fireEvent, render, screen } from '@testing-library/react-native';
import type { NativeStackScreenProps } from '@react-navigation/native-stack';
import { ForgotPasswordScreen } from './ForgotPasswordScreen';
import { authApi } from '../api/endpoints';
import { ThemeProvider } from '../theme';
import type { AuthStackParamList } from '../navigation/types';

jest.mock('../api/endpoints', () => ({
  authApi: { forgotPassword: jest.fn() },
}));

const api = authApi as jest.Mocked<typeof authApi>;

type Props = NativeStackScreenProps<AuthStackParamList, 'ForgotPassword'>;

const mockNavigate = jest.fn();

function renderScreen() {
  const navigation = { navigate: mockNavigate } as unknown as Props['navigation'];
  const route = { key: 'ForgotPassword', name: 'ForgotPassword', params: undefined } as Props['route'];
  return render(
    <ThemeProvider>
      <ForgotPasswordScreen navigation={navigation} route={route} />
    </ThemeProvider>
  );
}

async function settle() {
  await act(async () => {});
}

describe('ForgotPasswordScreen', () => {
  beforeEach(() => {
    jest.clearAllMocks();
    api.forgotPassword.mockResolvedValue({ message: 'ok', devResetLink: null });
  });

  it('asks the backend for a reset email for the address entered', async () => {
    renderScreen();
    fireEvent.changeText(screen.getByLabelText('Email'), 'someone@example.com');
    fireEvent.press(screen.getByText('Send reset link'));
    await settle();

    expect(api.forgotPassword).toHaveBeenCalledWith('someone@example.com');
  });

  it('does not call the backend for an invalid address', async () => {
    renderScreen();
    fireEvent.changeText(screen.getByLabelText('Email'), 'not-an-email');
    fireEvent.press(screen.getByText('Send reset link'));
    await settle();

    expect(api.forgotPassword).not.toHaveBeenCalled();
  });

  it('no longer sends users to the web to finish, and does not assume which device reads the email', async () => {
    renderScreen();
    fireEvent.changeText(screen.getByLabelText('Email'), 'someone@example.com');
    fireEvent.press(screen.getByText('Send reset link'));
    await settle();

    expect(screen.getByText('Check your email')).toBeTruthy();
    // The link works from any device (it opens in the app on a phone that has it, on the web page
    // otherwise), so the copy must not tell someone reading it on a laptop to use "this phone".
    expect(screen.queryByText(/on the web/i)).toBeNull();
    expect(screen.queryByText(/this phone/i)).toBeNull();
    expect(screen.getByText(/open the link to choose a new password/i)).toBeTruthy();
  });
});
