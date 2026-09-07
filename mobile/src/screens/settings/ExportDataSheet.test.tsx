import { act, fireEvent, render, screen, waitFor } from '@testing-library/react-native';
import { ExportDataSheet } from './ExportDataSheet';
import { accountLifecycleApi } from '../../api/endpoints';

jest.mock('../../api/endpoints', () => ({
  accountLifecycleApi: { exportData: jest.fn() },
}));

const lifecycleApi = accountLifecycleApi as jest.Mocked<typeof accountLifecycleApi>;

const onClose = jest.fn();
const onContactSupport = jest.fn();

function renderSheet(signInMethod: 'PASSWORD' | 'GOOGLE' | 'APPLE' = 'PASSWORD') {
  return render(
    <ExportDataSheet onClose={onClose} signInMethod={signInMethod} onContactSupport={onContactSupport} />
  );
}

async function settle() {
  await act(async () => {});
}

describe('ExportDataSheet', () => {
  beforeEach(() => {
    onClose.mockReset();
    onContactSupport.mockReset();
    lifecycleApi.exportData.mockReset().mockResolvedValue(undefined);
  });

  it('shows the password form for a PASSWORD account', () => {
    renderSheet('PASSWORD');

    expect(screen.getByLabelText('Current password')).toBeTruthy();
  });

  // Store-readiness gate (S1): Google/Apple accounts get an honest "not yet available" message
  // instead of a re-auth form with no password field to submit -- same posture as
  // DeactivateAccountSheet and DeleteAccountSheet.
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

    await waitFor(() => expect(lifecycleApi.exportData).toHaveBeenCalledWith('CurrentPw1!', null));
    expect(onClose).toHaveBeenCalled();
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
  });
});
