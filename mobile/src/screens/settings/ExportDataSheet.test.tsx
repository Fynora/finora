import { act, fireEvent, render, screen, waitFor } from '@testing-library/react-native';
import { GoogleSignin } from '@react-native-google-signin/google-signin';
import * as AppleAuthentication from 'expo-apple-authentication';
import { ExportDataSheet } from './ExportDataSheet';
import { accountLifecycleApi } from '../../api/endpoints';
import { ThemeProvider } from '../../theme';
import { expectHaptics } from '../../test/haptics';

jest.mock('../../api/endpoints', () => ({
  accountLifecycleApi: { exportData: jest.fn() },
}));

const lifecycleApi = accountLifecycleApi as jest.Mocked<typeof accountLifecycleApi>;
const mockedGoogleSignin = GoogleSignin as jest.Mocked<typeof GoogleSignin>;

const onClose = jest.fn();

function renderSheet(signInMethod: 'PASSWORD' | 'GOOGLE' | 'APPLE' = 'PASSWORD') {
  // The Google/Apple buttons read useThemeSetting(), which needs a real ThemeProvider.
  return render(
    <ThemeProvider>
      <ExportDataSheet onClose={onClose} signInMethod={signInMethod} />
    </ThemeProvider>
  );
}

async function settle() {
  await act(async () => {});
}

describe('ExportDataSheet', () => {
  const originalGoogleEnv = process.env.EXPO_PUBLIC_GOOGLE_WEB_CLIENT_ID;

  beforeEach(() => {
    onClose.mockReset();
    lifecycleApi.exportData.mockReset().mockResolvedValue(undefined);
    process.env.EXPO_PUBLIC_GOOGLE_WEB_CLIENT_ID = 'test-web-client-id.apps.googleusercontent.com';
  });

  afterEach(() => {
    process.env.EXPO_PUBLIC_GOOGLE_WEB_CLIENT_ID = originalGoogleEnv;
  });

  it('shows the password form for a PASSWORD account', () => {
    renderSheet('PASSWORD');

    expect(screen.getByLabelText('Current password')).toBeTruthy();
  });

  it('asks a GOOGLE account to verify with Google, not for a password', () => {
    renderSheet('GOOGLE');

    expect(screen.getByText('Sign in with Google')).toBeTruthy();
    expect(screen.queryByLabelText('Current password')).toBeNull();
    expect(screen.queryByText(/isn.t available in the app yet/)).toBeNull();
  });

  it('exports with a fresh Google credential in place of a password', async () => {
    mockedGoogleSignin.signIn.mockResolvedValue({
      type: 'success',
      data: { idToken: 'a-google-id-token', user: {}, scopes: [], serverAuthCode: null },
    } as never);
    renderSheet('GOOGLE');

    fireEvent.press(screen.getByText('Sign in with Google'));
    await settle();

    await waitFor(() => expect(lifecycleApi.exportData).toHaveBeenCalledWith(null, 'a-google-id-token', null));
    expect(onClose).toHaveBeenCalled();
  });

  it('exports with a fresh Apple credential in place of a password', async () => {
    jest.mocked(AppleAuthentication.signInAsync).mockResolvedValue({
      identityToken: 'an-apple-id-token', fullName: null,
    } as never);
    renderSheet('APPLE');

    fireEvent.press(screen.getByText('Sign in with Apple'));
    await settle();

    await waitFor(() => expect(lifecycleApi.exportData).toHaveBeenCalledWith(null, null, 'an-apple-id-token'));
    expect(onClose).toHaveBeenCalled();
  });

  it("shows the server's message and stays open when a Google credential is refused", async () => {
    lifecycleApi.exportData.mockReset().mockRejectedValue(
      Object.assign(new Error('bad'), {
        isAxiosError: true,
        response: { status: 400, data: { message: "We couldn't verify your Google account. Please try again." } },
      })
    );
    mockedGoogleSignin.signIn.mockResolvedValue({
      type: 'success',
      data: { idToken: 'someone-elses-token', user: {}, scopes: [], serverAuthCode: null },
    } as never);
    renderSheet('GOOGLE');

    fireEvent.press(screen.getByText('Sign in with Google'));
    await settle();

    expect(await screen.findByText("We couldn't verify your Google account. Please try again.")).toBeTruthy();
    expect(onClose).not.toHaveBeenCalled();
    // The prompt is back, so the user can try again.
    expect(screen.getByText('Sign in with Google')).toBeTruthy();
  });

  it('will not submit until a password is typed', () => {
    renderSheet();

    expect(
      screen.getByRole('button', { name: /Export My Data/ }).props.accessibilityState.disabled
    ).toBe(true);

    fireEvent.changeText(screen.getByLabelText('Current password'), 'CurrentPw1!');

    expect(
      screen.getByRole('button', { name: /Export My Data/ }).props.accessibilityState.disabled
    ).toBe(false);
  });

  it('exports with the given password and closes once the share hand-off completes', async () => {
    renderSheet();
    fireEvent.changeText(screen.getByLabelText('Current password'), 'CurrentPw1!');

    fireEvent.press(screen.getByRole('button', { name: /Export My Data/ }));
    await settle();

    await waitFor(() => expect(lifecycleApi.exportData).toHaveBeenCalledWith('CurrentPw1!', null, null));
    expect(onClose).toHaveBeenCalled();
    expectHaptics({ success: 1 });
  });

  it('shows an error and stays open when the export fails', async () => {
    lifecycleApi.exportData.mockReset().mockRejectedValue(
      Object.assign(new Error('bad'), {
        isAxiosError: true,
        response: { status: 400, data: { message: 'Current password is incorrect.' } },
      })
    );
    renderSheet();
    fireEvent.changeText(screen.getByLabelText('Current password'), 'wrong');

    fireEvent.press(screen.getByRole('button', { name: /Export My Data/ }));
    await settle();

    expect(await screen.findByText('Current password is incorrect.')).toBeTruthy();
    expect(onClose).not.toHaveBeenCalled();
    expectHaptics({ error: 1 });
  });
});
