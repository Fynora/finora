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

    expect(notificationPreferencesApi.set).toHaveBeenCalledWith('FINANCIAL', 'EMAIL', false);
    await waitFor(() => expect(screen.getByRole('switch', { name: 'Email' })).toHaveAttribute('aria-checked', 'false'));
    expect(screen.getByRole('switch', { name: 'Push notifications' })).toHaveAttribute('aria-checked', 'true');
  });

  // Gate 2b PR 5 (spec §6): a separate switch for statement reminders, push only.
  it('togglesStatementReminders', async () => {
    const user = userEvent.setup();
    const withReminders = (reminders: boolean) => [
      ...both(true, true), { category: 'REMINDERS' as const, channel: 'PUSH' as const, enabled: reminders },
    ];
    vi.mocked(notificationPreferencesApi.list).mockResolvedValue(withReminders(true));
    vi.mocked(notificationPreferencesApi.set).mockResolvedValue(withReminders(false));
    render(<NotificationsPane />);

    const reminders = await screen.findByRole('switch', { name: 'Statement reminders' });
    expect(reminders).toHaveAttribute('aria-checked', 'true');
    expect(screen.getByText('A push at the start of the month when a new statement has probably arrived.')).toBeInTheDocument();
    await user.click(reminders);

    expect(notificationPreferencesApi.set).toHaveBeenCalledWith('REMINDERS', 'PUSH', false);
    await waitFor(() => expect(screen.getByRole('switch', { name: 'Statement reminders' })).toHaveAttribute('aria-checked', 'false'));
    expect(screen.getByRole('switch', { name: 'Push notifications' })).toHaveAttribute('aria-checked', 'true');
  });

  // V305: "Offers and news" is opt-in. Promotional push campaigns reach only people who turned it on.
  it('shows offers and news off until turned on, and turning it on saves MARKETING push only', async () => {
    const user = userEvent.setup();
    const withOffers = (offers: boolean) => [
      ...both(true, true), { category: 'MARKETING' as const, channel: 'PUSH' as const, enabled: offers },
    ];
    vi.mocked(notificationPreferencesApi.list).mockResolvedValue(withOffers(false));
    vi.mocked(notificationPreferencesApi.set).mockResolvedValue(withOffers(true));
    render(<NotificationsPane />);

    const offers = await screen.findByRole('switch', { name: 'Offers and news' });
    expect(offers).toHaveAttribute('aria-checked', 'false');
    await user.click(offers);

    expect(notificationPreferencesApi.set).toHaveBeenCalledWith('MARKETING', 'PUSH', true);
    await waitFor(() => expect(screen.getByRole('switch', { name: 'Offers and news' })).toHaveAttribute('aria-checked', 'true'));
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
