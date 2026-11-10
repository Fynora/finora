import { act, fireEvent, render, screen } from '@testing-library/react-native';
import type { NativeStackScreenProps } from '@react-navigation/native-stack';
import { SuspendedAccountScreen } from './SuspendedAccountScreen';
import { suspensionApi, type SuspensionStatus } from '../api/endpoints';
import { ThemeProvider } from '../theme';
import type { AuthStackParamList } from '../navigation/types';

jest.mock('../api/endpoints', () => ({
  suspensionApi: { status: jest.fn(), raiseRequest: jest.fn(), exportData: jest.fn() },
  accountLifecycleApi: { exportData: jest.fn() },
}));

const api = suspensionApi as jest.Mocked<typeof suspensionApi>;

const TOKEN = 'suspended-screen-token';
const TIMED_OUT = 'This page timed out. Copy anything you typed, then go back and sign in again to send it.';

type Props = NativeStackScreenProps<AuthStackParamList, 'SuspendedAccount'>;
const mockGoBack = jest.fn();

function statusOf(overrides: Partial<SuspensionStatus> = {}): SuspensionStatus {
  return {
    suspendedAt: '2026-11-08T10:00:00Z',
    reason: 'TERMS_VIOLATION',
    reasonText: 'Your account was used in a way our Terms do not allow.',
    message: null,
    storePlatform: null,
    signInMethod: 'PASSWORD',
    requests: [],
    ...overrides,
  };
}

function expiredError() {
  return Object.assign(new Error('Request failed with status code 401'), {
    isAxiosError: true,
    response: { status: 401, data: { message: 'Unauthorized' } },
  });
}

function renderScreen() {
  const navigation = { goBack: mockGoBack } as unknown as Props['navigation'];
  const route = { key: 'SuspendedAccount', name: 'SuspendedAccount', params: { token: TOKEN } } as Props['route'];
  return render(
    <ThemeProvider>
      <SuspendedAccountScreen navigation={navigation} route={route} />
    </ThemeProvider>
  );
}

async function settle() {
  await act(async () => {});
}

beforeEach(() => {
  api.status.mockReset().mockResolvedValue(statusOf());
  api.raiseRequest.mockReset();
  api.exportData.mockReset().mockResolvedValue(undefined);
  mockGoBack.mockReset();
});

describe('SuspendedAccountScreen', () => {
  it('shows the screen-shaped skeleton while loading, then the details', async () => {
    renderScreen();
    expect(screen.getByTestId('suspended-account-skeleton')).toBeTruthy();

    await settle();

    expect(screen.queryByTestId('suspended-account-skeleton')).toBeNull();
    expect(screen.getByText('Your account was used in a way our Terms do not allow.')).toBeTruthy();
  });

  it('loads the suspension with its own token and shows why, the message and the date', async () => {
    api.status.mockResolvedValue(statusOf({ message: 'Please contact us about the payments.' }));
    renderScreen();
    await settle();

    expect(api.status).toHaveBeenCalledWith(TOKEN);
    expect(screen.getByText('Your account was used in a way our Terms do not allow.')).toBeTruthy();
    expect(screen.getByText('Message from Fynora: Please contact us about the payments.')).toBeTruthy();
    expect(screen.getByText(/Since 8 November 2026, you can't use Fynora\./)).toBeTruthy();
  });

  it.each([
    ['IOS', 'the App Store'],
    ['ANDROID', 'Google Play'],
  ] as const)('warns that a %s store subscription has to be cancelled there', async (platform, store) => {
    api.status.mockResolvedValue(statusOf({ storePlatform: platform }));
    renderScreen();
    await settle();

    expect(screen.getByText(new RegExp(`paid through ${store}`))).toBeTruthy();
  });

  it('shows no store warning when Fynora bills the subscription itself', async () => {
    renderScreen();
    await settle();

    expect(screen.queryByText(/paid through/)).toBeNull();
  });

  it('says to sign in again when the details cannot be loaded', async () => {
    api.status.mockRejectedValue(expiredError());
    renderScreen();
    await settle();

    expect(screen.getByText("We couldn't load the details. Sign in again to see them.")).toBeTruthy();
  });

  it('sends an appeal and then shows its ticket', async () => {
    api.raiseRequest.mockResolvedValue(statusOf({
      requests: [{ type: 'APPEAL', ticketNumber: 'SUP-1042', status: 'OPEN', createdAt: '2026-11-10T09:00:00Z' }],
    }));
    renderScreen();
    await settle();

    fireEvent.changeText(screen.getByTestId('suspended-appeal-text'), '  This was a mistake.  ');
    await act(async () => { fireEvent.press(screen.getByTestId('suspended-send-appeal')); });

    expect(api.raiseRequest).toHaveBeenCalledWith(TOKEN, 'APPEAL', 'This was a mistake.');
    expect(screen.getByText(/Your appeal \(SUP-1042\) is with us\./)).toBeTruthy();
    expect(screen.queryByTestId('suspended-appeal-text')).toBeNull();
  });

  it('does not send an empty appeal', async () => {
    renderScreen();
    await settle();

    fireEvent.changeText(screen.getByTestId('suspended-appeal-text'), '   ');
    await act(async () => { fireEvent.press(screen.getByTestId('suspended-send-appeal')); });

    expect(api.raiseRequest).not.toHaveBeenCalled();
  });

  it('keeps the typed appeal and says the page timed out when the token has expired', async () => {
    api.raiseRequest.mockRejectedValue(expiredError());
    renderScreen();
    await settle();

    fireEvent.changeText(screen.getByTestId('suspended-appeal-text'), 'Please look at this again.');
    await act(async () => { fireEvent.press(screen.getByTestId('suspended-send-appeal')); });

    expect(screen.getByText(TIMED_OUT)).toBeTruthy();
    expect(screen.getByTestId('suspended-appeal-text').props.value).toBe('Please look at this again.');
  });

  it("shows the server's own message for any other failure", async () => {
    api.raiseRequest.mockRejectedValue(Object.assign(new Error('Request failed'), {
      isAxiosError: true,
      response: { status: 409, data: { message: 'You already have an open appeal.' } },
    }));
    renderScreen();
    await settle();

    fireEvent.changeText(screen.getByTestId('suspended-appeal-text'), 'Again.');
    await act(async () => { fireEvent.press(screen.getByTestId('suspended-send-appeal')); });

    expect(screen.getByText('You already have an open appeal.')).toBeTruthy();
  });

  it('asks before sending a deletion request, and can be cancelled', async () => {
    renderScreen();
    await settle();

    fireEvent.press(screen.getByTestId('suspended-ask-deletion'));
    expect(screen.getByText(/once we've checked nothing has to be kept by law/)).toBeTruthy();
    expect(api.raiseRequest).not.toHaveBeenCalled();

    fireEvent.press(screen.getByRole('button', { name: 'Cancel' }));
    expect(screen.getByTestId('suspended-ask-deletion')).toBeTruthy();
    expect(api.raiseRequest).not.toHaveBeenCalled();
  });

  it('sends a deletion request once confirmed and shows its ticket', async () => {
    api.raiseRequest.mockResolvedValue(statusOf({
      requests: [{ type: 'DELETION', ticketNumber: 'SUP-1043', status: 'OPEN', createdAt: '2026-11-10T09:00:00Z' }],
    }));
    renderScreen();
    await settle();

    fireEvent.press(screen.getByTestId('suspended-ask-deletion'));
    await act(async () => { fireEvent.press(screen.getByTestId('suspended-confirm-deletion')); });

    expect(api.raiseRequest).toHaveBeenCalledWith(TOKEN, 'DELETION', undefined);
    expect(screen.getByText(/Your deletion request \(SUP-1043\) is with us\./)).toBeTruthy();
  });

  it('shows requests already open when the screen loads', async () => {
    api.status.mockResolvedValue(statusOf({
      requests: [
        { type: 'APPEAL', ticketNumber: 'SUP-1', status: 'IN_PROGRESS', createdAt: '2026-11-09T09:00:00Z' },
        { type: 'DELETION', ticketNumber: 'SUP-2', status: 'CLOSED', createdAt: '2026-11-09T09:00:00Z' },
      ],
    }));
    renderScreen();
    await settle();

    expect(screen.getByText(/Your appeal \(SUP-1\) is with us\./)).toBeTruthy();
    // A closed deletion request is finished, so a new one can be asked for.
    expect(screen.getByTestId('suspended-ask-deletion')).toBeTruthy();
  });

  it('downloads the data with the suspended token', async () => {
    renderScreen();
    await settle();

    fireEvent.press(screen.getByTestId('suspended-download-data'));
    fireEvent.changeText(screen.getByLabelText('Current password'), 'my-password');
    await act(async () => { fireEvent.press(screen.getByRole('button', { name: 'Export My Data' })); });

    expect(api.exportData).toHaveBeenCalledWith(TOKEN, 'my-password', null, null);
    // The sheet closes once the share hand-off completes.
    expect(screen.queryByLabelText('Current password')).toBeNull();
  });

  it('says the page timed out when the download is refused with a 401', async () => {
    api.exportData.mockRejectedValue(expiredError());
    renderScreen();
    await settle();

    fireEvent.press(screen.getByTestId('suspended-download-data'));
    fireEvent.changeText(screen.getByLabelText('Current password'), 'my-password');
    await act(async () => { fireEvent.press(screen.getByRole('button', { name: 'Export My Data' })); });

    expect(screen.getByText(TIMED_OUT)).toBeTruthy();
  });

  it('goes back to sign in', async () => {
    renderScreen();
    await settle();

    fireEvent.press(screen.getByRole('button', { name: 'Back to sign in' }));

    expect(mockGoBack).toHaveBeenCalled();
  });
});
