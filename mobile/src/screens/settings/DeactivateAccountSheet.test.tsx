import { act, fireEvent, render, screen, waitFor } from '@testing-library/react-native';
import { GoogleSignin } from '@react-native-google-signin/google-signin';
import * as AppleAuthentication from 'expo-apple-authentication';
import { DeactivateAccountSheet } from './DeactivateAccountSheet';
import { accountLifecycleApi } from '../../api/endpoints';
import { ThemeProvider } from '../../theme';

jest.mock('../../api/endpoints', () => ({
  accountLifecycleApi: { deactivate: jest.fn() },
}));

const lifecycleApi = accountLifecycleApi as jest.Mocked<typeof accountLifecycleApi>;
const mockedGoogleSignin = GoogleSignin as jest.Mocked<typeof GoogleSignin>;

const onClose = jest.fn();
const onDeactivated = jest.fn();

function renderSheet(signInMethod: 'PASSWORD' | 'GOOGLE' | 'APPLE' = 'PASSWORD') {
  // The Google/Apple buttons read useThemeSetting(), which needs a real ThemeProvider.
  return render(
    <ThemeProvider>
      <DeactivateAccountSheet
        onClose={onClose}
        onDeactivated={onDeactivated}
        signInMethod={signInMethod}
      />
    </ThemeProvider>
  );
}

async function settle() {
  await act(async () => {});
}

/** OptionPickerModal rows carry a testID of `option-${label}`, disambiguated from any
 *  same-named text elsewhere on screen -- see OptionPickerModal.tsx's own doc comment. */
async function chooseReason(label: string) {
  fireEvent.press(screen.getByLabelText('Reason for deactivating'));
  fireEvent.press(screen.getByTestId(`option-${label}`));
  await settle();
}

describe('DeactivateAccountSheet', () => {
  const originalGoogleEnv = process.env.EXPO_PUBLIC_GOOGLE_WEB_CLIENT_ID;

  beforeEach(() => {
    onClose.mockReset();
    onDeactivated.mockReset();
    lifecycleApi.deactivate.mockReset().mockResolvedValue({ message: 'Account deactivated.' });
    process.env.EXPO_PUBLIC_GOOGLE_WEB_CLIENT_ID = 'test-web-client-id.apps.googleusercontent.com';
  });

  afterEach(() => {
    process.env.EXPO_PUBLIC_GOOGLE_WEB_CLIENT_ID = originalGoogleEnv;
  });

  it('shows the password + reason form for a PASSWORD account', () => {
    renderSheet('PASSWORD');

    expect(screen.getByLabelText('Current password')).toBeTruthy();
    expect(screen.getByLabelText('Reason for deactivating')).toBeTruthy();
  });

  // Google/Apple accounts have no password anyone knows: they choose a reason first, then a fresh
  // Google/Apple credential is what deactivates.
  it.each(['GOOGLE', 'APPLE'] as const)(
    'asks a %s account for a reason first, with no password field',
    (signInMethod) => {
      renderSheet(signInMethod);

      expect(screen.queryByLabelText('Current password')).toBeNull();
      expect(screen.getByLabelText('Reason for deactivating')).toBeTruthy();
      expect(screen.getByText(/Choose a reason, then verify with/)).toBeTruthy();
      expect(screen.queryByText(/Sign in with (Google|Apple)/)).toBeNull();
      expect(screen.queryByText(/isn.t available in the app yet/)).toBeNull();
    }
  );

  it('deactivates a GOOGLE account with a fresh Google credential once a reason is chosen', async () => {
    mockedGoogleSignin.signIn.mockResolvedValue({
      type: 'success',
      data: { idToken: 'a-google-id-token', user: {}, scopes: [], serverAuthCode: null },
    } as never);
    renderSheet('GOOGLE');
    await chooseReason('Taking a break');

    fireEvent.press(screen.getByText('Sign in with Google'));
    await settle();

    await waitFor(() =>
      expect(lifecycleApi.deactivate).toHaveBeenCalledWith(null, 'a-google-id-token', null, 'TAKING_A_BREAK', undefined)
    );
    expect(onDeactivated).toHaveBeenCalled();
  });

  it('deactivates an APPLE account with a fresh Apple credential once a reason is chosen', async () => {
    jest.mocked(AppleAuthentication.signInAsync).mockResolvedValue({
      identityToken: 'an-apple-id-token', fullName: null,
    } as never);
    renderSheet('APPLE');
    await chooseReason('Privacy concerns');

    fireEvent.press(screen.getByText('Sign in with Apple'));
    await settle();

    await waitFor(() =>
      expect(lifecycleApi.deactivate).toHaveBeenCalledWith(null, null, 'an-apple-id-token', 'PRIVACY_CONCERNS', undefined)
    );
    expect(onDeactivated).toHaveBeenCalled();
  });

  it('will not submit until both a password and a reason are given', async () => {
    renderSheet();

    expect(
      screen.getByRole('button', { name: /Deactivate Account/ }).props.accessibilityState.disabled
    ).toBe(true);

    fireEvent.changeText(screen.getByLabelText('Current password'), 'CurrentPw1!');
    expect(
      screen.getByRole('button', { name: /Deactivate Account/ }).props.accessibilityState.disabled
    ).toBe(true);

    await chooseReason('Taking a break');
    expect(
      screen.getByRole('button', { name: /Deactivate Account/ }).props.accessibilityState.disabled
    ).toBe(false);
  });

  it('shows the chosen reason once picked', async () => {
    renderSheet();

    await chooseReason('Privacy concerns');

    expect(screen.getByText('Privacy concerns')).toBeTruthy();
  });

  it('deactivates with the password, reason code, and trimmed note', async () => {
    renderSheet();
    fireEvent.changeText(screen.getByLabelText('Current password'), 'CurrentPw1!');
    await chooseReason('Taking a break');
    fireEvent.changeText(screen.getByLabelText('Anything else? (optional)'), '  back in a month  ');

    fireEvent.press(screen.getByRole('button', { name: /Deactivate Account/ }));
    await settle();

    await waitFor(() =>
      expect(lifecycleApi.deactivate).toHaveBeenCalledWith('CurrentPw1!', null, null, 'TAKING_A_BREAK', 'back in a month')
    );
    expect(onDeactivated).toHaveBeenCalled();
  });

  it('sends undefined, not an empty string, when the note is left blank', async () => {
    renderSheet();
    fireEvent.changeText(screen.getByLabelText('Current password'), 'CurrentPw1!');
    await chooseReason("I'm not using it anymore");

    fireEvent.press(screen.getByRole('button', { name: /Deactivate Account/ }));
    await settle();

    await waitFor(() =>
      expect(lifecycleApi.deactivate).toHaveBeenCalledWith('CurrentPw1!', null, null, 'NOT_USING_ANYMORE', undefined)
    );
  });

  it('shows an error and does not sign the user out when deactivation fails', async () => {
    lifecycleApi.deactivate.mockReset().mockRejectedValue(
      Object.assign(new Error('bad'), {
        isAxiosError: true,
        response: { status: 400, data: { message: 'Current password is incorrect.' } },
      })
    );
    renderSheet();
    fireEvent.changeText(screen.getByLabelText('Current password'), 'wrong');
    await chooseReason('Taking a break');

    fireEvent.press(screen.getByRole('button', { name: /Deactivate Account/ }));
    await settle();

    expect(await screen.findByText('Current password is incorrect.')).toBeTruthy();
    expect(onDeactivated).not.toHaveBeenCalled();
  });
});
