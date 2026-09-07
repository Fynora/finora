import { act, fireEvent, render, screen, waitFor } from '@testing-library/react-native';
import { DeactivateAccountSheet } from './DeactivateAccountSheet';
import { accountLifecycleApi } from '../../api/endpoints';

jest.mock('../../api/endpoints', () => ({
  accountLifecycleApi: { deactivate: jest.fn() },
}));

const lifecycleApi = accountLifecycleApi as jest.Mocked<typeof accountLifecycleApi>;

const onClose = jest.fn();
const onDeactivated = jest.fn();
const onContactSupport = jest.fn();

function renderSheet(signInMethod: 'PASSWORD' | 'GOOGLE' | 'APPLE' = 'PASSWORD') {
  return render(
    <DeactivateAccountSheet
      onClose={onClose}
      onDeactivated={onDeactivated}
      signInMethod={signInMethod}
      onContactSupport={onContactSupport}
    />
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
  beforeEach(() => {
    onClose.mockReset();
    onDeactivated.mockReset();
    onContactSupport.mockReset();
    lifecycleApi.deactivate.mockReset().mockResolvedValue({ message: 'Account deactivated.' });
  });

  it('shows the password + reason form for a PASSWORD account', () => {
    renderSheet('PASSWORD');

    expect(screen.getByLabelText('Current password')).toBeTruthy();
    expect(screen.getByLabelText('Reason for deactivating')).toBeTruthy();
  });

  // Store-readiness gate (S1/S2): Google/Apple accounts get an honest "not yet available"
  // message instead of a re-auth form with no password field to submit -- same posture as
  // DeleteAccountSheet and ExportDataSheet.
  it.each(['GOOGLE', 'APPLE'] as const)(
    'shows a support message instead of the form for a %s account',
    (signInMethod) => {
      renderSheet(signInMethod);

      expect(screen.queryByLabelText('Current password')).toBeNull();
      expect(screen.getByText(/isn.t available in the app yet/)).toBeTruthy();

      fireEvent.press(screen.getByText('Contact Support'));
      expect(onContactSupport).toHaveBeenCalled();
    }
  );

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
      expect(lifecycleApi.deactivate).toHaveBeenCalledWith('CurrentPw1!', null, 'TAKING_A_BREAK', 'back in a month')
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
      expect(lifecycleApi.deactivate).toHaveBeenCalledWith('CurrentPw1!', null, 'NOT_USING_ANYMORE', undefined)
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
