import { describe, it, expect, vi, beforeEach } from 'vitest';
import { render, screen, waitFor } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { NotificationsPane } from './NotificationsPane';
import { notificationPreferencesApi } from '../../api/endpoints';

vi.mock('../../api/endpoints', () => ({
  notificationPreferencesApi: { list: vi.fn(), set: vi.fn() },
}));

const both = (email: boolean, push: boolean) => [
  { category: 'FINANCIAL' as const, channel: 'EMAIL' as const, enabled: email },
  { category: 'FINANCIAL' as const, channel: 'PUSH' as const, enabled: push },
];

describe('NotificationsPane', () => {
  beforeEach(() => {
    vi.mocked(notificationPreferencesApi.list).mockReset();
    vi.mocked(notificationPreferencesApi.set).mockReset();
  });

  it('shows each channel as a switch reflecting the saved state', async () => {
    vi.mocked(notificationPreferencesApi.list).mockResolvedValue(both(true, false));
    render(<NotificationsPane />);

    expect(await screen.findByRole('switch', { name: 'Email' })).toHaveAttribute('aria-checked', 'true');
    expect(screen.getByRole('switch', { name: 'Push notifications' })).toHaveAttribute('aria-checked', 'false');
    expect(screen.getByText(/security messages/i)).toBeInTheDocument();
  });

  it('turning email off saves it and shows what the server returned', async () => {
    const user = userEvent.setup();
    vi.mocked(notificationPreferencesApi.list).mockResolvedValue(both(true, true));
    vi.mocked(notificationPreferencesApi.set).mockResolvedValue(both(false, true));
    render(<NotificationsPane />);

    await user.click(await screen.findByRole('switch', { name: 'Email' }));

    expect(notificationPreferencesApi.set).toHaveBeenCalledWith('EMAIL', false);
    await waitFor(() => expect(screen.getByRole('switch', { name: 'Email' })).toHaveAttribute('aria-checked', 'false'));
    expect(screen.getByRole('switch', { name: 'Push notifications' })).toHaveAttribute('aria-checked', 'true');
  });

  it('keeps the old state and says so when saving fails', async () => {
    const user = userEvent.setup();
    vi.mocked(notificationPreferencesApi.list).mockResolvedValue(both(true, true));
    vi.mocked(notificationPreferencesApi.set).mockRejectedValue(new Error('network'));
    render(<NotificationsPane />);

    await user.click(await screen.findByRole('switch', { name: 'Email' }));

    expect(await screen.findByRole('alert')).toHaveTextContent(/couldn't save/i);
    expect(screen.getByRole('switch', { name: 'Email' })).toHaveAttribute('aria-checked', 'true');
    expect(screen.getByRole('switch', { name: 'Email' })).not.toBeDisabled();
  });

  it('shows a load error instead of switches when the settings cannot be fetched', async () => {
    vi.mocked(notificationPreferencesApi.list).mockRejectedValue(new Error('network'));
    render(<NotificationsPane />);

    expect(await screen.findByText(/couldn't load your notification settings/i)).toBeInTheDocument();
    expect(screen.queryByRole('switch')).not.toBeInTheDocument();
  });
});
