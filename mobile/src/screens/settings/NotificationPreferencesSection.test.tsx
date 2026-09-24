import { fireEvent, render, screen, waitFor } from '@testing-library/react-native';
import { NotificationPreferencesSection } from './NotificationPreferencesSection';
import { notificationPreferencesApi } from '../../api/endpoints';
import { ThemeProvider } from '../../theme';

jest.mock('../../api/endpoints', () => ({
  notificationPreferencesApi: { list: jest.fn(), set: jest.fn() },
}));

const api = notificationPreferencesApi as jest.Mocked<typeof notificationPreferencesApi>;

const both = (email: boolean, push: boolean) => [
  { category: 'FINANCIAL' as const, channel: 'EMAIL' as const, enabled: email },
  { category: 'FINANCIAL' as const, channel: 'PUSH' as const, enabled: push },
];

function renderSection() {
  return render(<ThemeProvider><NotificationPreferencesSection /></ThemeProvider>);
}

describe('NotificationPreferencesSection', () => {
  beforeEach(() => {
    api.list.mockReset();
    api.set.mockReset();
  });

  it('shows a switch per channel reflecting the saved state', async () => {
    api.list.mockResolvedValue(both(true, false));
    renderSection();

    expect(await screen.findByLabelText('Email')).toHaveProp('value', true);
    expect(screen.getByLabelText('Push notifications')).toHaveProp('value', false);
    expect(screen.getByText(/security messages/i)).toBeTruthy();
  });

  it('turning email off saves it and shows what the server returned', async () => {
    api.list.mockResolvedValue(both(true, true));
    api.set.mockResolvedValue(both(false, true));
    renderSection();

    fireEvent(await screen.findByLabelText('Email'), 'valueChange', false);

    await waitFor(() => expect(screen.getByLabelText('Email')).toHaveProp('value', false));
    expect(api.set).toHaveBeenCalledWith('EMAIL', false);
    expect(screen.getByLabelText('Push notifications')).toHaveProp('value', true);
  });

  it('keeps the old state and says so when saving fails', async () => {
    api.list.mockResolvedValue(both(true, true));
    api.set.mockRejectedValue(new Error('network'));
    renderSection();

    fireEvent(await screen.findByLabelText('Email'), 'valueChange', false);

    expect(await screen.findByText(/couldn't save/i)).toBeTruthy();
    expect(screen.getByLabelText('Email')).toHaveProp('value', true);
  });

  it('shows a load error instead of switches when the settings cannot be fetched', async () => {
    api.list.mockRejectedValue(new Error('network'));
    renderSection();

    expect(await screen.findByText(/couldn't load your notification settings/i)).toBeTruthy();
    expect(screen.queryByLabelText('Email')).not.toBeOnTheScreen();
  });
});
