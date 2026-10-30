import { describe, it, expect, vi, beforeEach } from 'vitest';
import { render, screen, waitFor, within } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { MemoryRouter } from 'react-router-dom';
import { QueryClient, QueryClientProvider } from '@tanstack/react-query';
import AppIcon from './AppIcon';
import { useAdminAuth } from '../context/AdminAuthContext';
import { mockAdminAuthState } from '../test/mockAdminAuth';
import { adminAppIconApi } from '../api/endpoints';
import type { AppIconScheduleEntry } from '../types';

vi.mock('../context/ThemeContext', () => ({
  useTheme: () => ({ theme: 'system', resolvedTheme: 'light', setTheme: vi.fn() }),
}));
vi.mock('../context/AdminAuthContext', () => ({
  useAdminAuth: vi.fn(),
}));
vi.mock('../api/endpoints', () => ({
  adminAppIconApi: {
    current: vi.fn(),
    icons: vi.fn(),
    list: vi.fn(),
    create: vi.fn(),
    update: vi.fn(),
    remove: vi.fn(),
  },
}));

const api = vi.mocked(adminAppIconApi);

function renderPage() {
  const queryClient = new QueryClient({ defaultOptions: { queries: { retry: false }, mutations: { retry: false } } });
  return render(
    <QueryClientProvider client={queryClient}>
      <MemoryRouter>
        <AppIcon />
      </MemoryRouter>
    </QueryClientProvider>,
  );
}

function mockAuth(permissions: string[]) {
  vi.mocked(useAdminAuth).mockReturnValue(mockAdminAuthState({
    hasPermission: (p: string) => permissions.includes(p),
    permissions,
  }));
}

// Diwali 2026: 00:00 IST on 8 Nov is 18:30 UTC on 7 Nov.
const ACTIVE: AppIconScheduleEntry = {
  id: 'a1', icon: 'diwali', iconLabel: 'Diwali', label: 'Diwali 2026',
  startsAt: '2026-11-07T18:30:00Z', endsAt: '2026-11-09T18:30:00Z', state: 'ACTIVE',
  createdBy: 'u1', createdByEmail: 'sid@example.com', createdAt: '2026-10-30T10:00:00Z',
  updatedBy: 'u1', updatedByEmail: 'sid@example.com', updatedAt: '2026-10-30T10:00:00Z', version: 2,
};
const ENDED: AppIconScheduleEntry = {
  ...ACTIVE, id: 'e1', label: 'Diwali 2025', state: 'ENDED',
  startsAt: '2025-10-19T18:30:00Z', endsAt: '2025-10-21T18:30:00Z', updatedByEmail: null,
};

describe('AppIcon', () => {
  beforeEach(() => {
    Object.values(api).forEach((fn) => fn.mockReset());
    api.icons.mockResolvedValue([{ key: 'diwali', label: 'Diwali' }]);
    api.current.mockResolvedValue({ icon: 'default' });
    api.list.mockResolvedValue([]);
  });

  it('is closed to an account without SYSTEM_SETTINGS', () => {
    mockAuth([]);
    renderPage();
    expect(screen.getByText("You don't have access to this section")).toBeInTheDocument();
    expect(api.list).not.toHaveBeenCalled();
  });

  it('says the standard icon is showing when nothing is scheduled', async () => {
    mockAuth(['SYSTEM_SETTINGS']);
    renderPage();
    await waitFor(() =>
      expect(screen.getByTestId('showing-now')).toHaveTextContent('Standard Fynora icon (nothing scheduled right now)'));
    expect(await screen.findByText('Nothing scheduled. The standard icon shows.')).toBeInTheDocument();
  });

  it('shows the festival icon and its end in IST, and lists entries with their state', async () => {
    mockAuth(['SYSTEM_SETTINGS']);
    api.current.mockResolvedValue({ icon: 'diwali', until: '2026-11-09T18:30:00Z' });
    api.list.mockResolvedValue([ACTIVE, ENDED]);
    renderPage();

    await waitFor(() => expect(screen.getByTestId('showing-now')).toHaveTextContent('Diwali, until 10 Nov 2026, 00:00 IST'));
    const activeRow = (await screen.findByText('Diwali 2026')).closest('tr')!;
    expect(within(activeRow).getByText('08 Nov 2026, 00:00 IST')).toBeInTheDocument();
    expect(within(activeRow).getByText('Showing now')).toBeInTheDocument();
    expect(within(activeRow).getByRole('button', { name: 'Edit' })).toBeInTheDocument();

    const endedRow = screen.getByText('Diwali 2025').closest('tr')!;
    expect(within(endedRow).getByText('Ended')).toBeInTheDocument();
    expect(within(endedRow).queryByRole('button', { name: 'Edit' })).not.toBeInTheDocument();
    expect(within(endedRow).getByRole('button', { name: 'Delete' })).toBeInTheDocument();
    expect(within(endedRow).getByText('a removed admin account')).toBeInTheDocument();
  });

  it('creates an entry, reading the typed times as IST whatever the laptop zone is', async () => {
    mockAuth(['SYSTEM_SETTINGS']);
    api.create.mockResolvedValue(ACTIVE);
    const user = userEvent.setup();
    renderPage();

    await user.click(await screen.findByRole('button', { name: /Add entry/ }));
    expect(screen.getByLabelText('Icon')).toHaveValue('diwali');
    await user.type(screen.getByLabelText(/^Name/), '  Diwali 2026 ');
    await user.type(screen.getByLabelText('Starts (IST)'), '2026-11-08T00:00');
    await user.type(screen.getByLabelText(/^Ends \(IST\)/), '2026-11-10T00:00');
    await user.click(screen.getByRole('button', { name: 'Add to schedule' }));

    await waitFor(() => expect(api.create).toHaveBeenCalledWith({
      icon: 'diwali',
      label: 'Diwali 2026',
      startsAt: '2026-11-07T18:30:00.000Z',
      endsAt: '2026-11-09T18:30:00.000Z',
    }));
    expect(await screen.findByText('Added to the schedule.')).toBeInTheDocument();
    // The save refreshes both the table and the "Showing now" line.
    expect(api.list).toHaveBeenCalledTimes(2);
    expect(api.current).toHaveBeenCalledTimes(2);
  });

  it('refuses an end before the start without calling the server', async () => {
    mockAuth(['SYSTEM_SETTINGS']);
    const user = userEvent.setup();
    renderPage();

    await user.click(await screen.findByRole('button', { name: /Add entry/ }));
    await user.type(screen.getByLabelText(/^Name/), 'Backwards');
    await user.type(screen.getByLabelText('Starts (IST)'), '2026-11-10T00:00');
    await user.type(screen.getByLabelText(/^Ends \(IST\)/), '2026-11-08T00:00');
    await user.click(screen.getByRole('button', { name: 'Add to schedule' }));

    expect(await screen.findByText('The end must be after the start.')).toBeInTheDocument();
    expect(api.create).not.toHaveBeenCalled();
  });

  it("shows the server's own words when it refuses a save, such as an overlap", async () => {
    mockAuth(['SYSTEM_SETTINGS']);
    api.create.mockRejectedValue({ response: { status: 409, data: { message: 'This overlaps "Diwali 2026".' } } });
    const user = userEvent.setup();
    renderPage();

    await user.click(await screen.findByRole('button', { name: /Add entry/ }));
    await user.type(screen.getByLabelText(/^Name/), 'Overlap');
    await user.type(screen.getByLabelText('Starts (IST)'), '2026-11-09T00:00');
    await user.type(screen.getByLabelText(/^Ends \(IST\)/), '2026-11-11T00:00');
    await user.click(screen.getByRole('button', { name: 'Add to schedule' }));

    expect(await screen.findByText('This overlaps "Diwali 2026".')).toBeInTheDocument();
  });

  it('edits an entry with its IST times filled in and sends the version it was opened at', async () => {
    mockAuth(['SYSTEM_SETTINGS']);
    api.list.mockResolvedValue([ACTIVE]);
    api.update.mockResolvedValue(ACTIVE);
    const user = userEvent.setup();
    renderPage();

    const row = (await screen.findByText('Diwali 2026')).closest('tr')!;
    await user.click(within(row).getByRole('button', { name: 'Edit' }));
    expect(screen.getByLabelText('Starts (IST)')).toHaveValue('2026-11-08T00:00');
    expect(screen.getByLabelText(/^Ends \(IST\)/)).toHaveValue('2026-11-10T00:00');
    const name = screen.getByLabelText(/^Name/);
    await user.clear(name);
    await user.type(name, 'Diwali 2026 week');
    await user.click(screen.getByRole('button', { name: 'Save changes' }));

    await waitFor(() => expect(api.update).toHaveBeenCalledWith('a1', {
      icon: 'diwali',
      label: 'Diwali 2026 week',
      startsAt: '2026-11-07T18:30:00.000Z',
      endsAt: '2026-11-09T18:30:00.000Z',
      expectedVersion: 2,
    }));
  });

  it('warns before deleting the entry that is showing now, then deletes it', async () => {
    mockAuth(['SYSTEM_SETTINGS']);
    api.list.mockResolvedValue([ACTIVE]);
    api.remove.mockResolvedValue(undefined);
    const user = userEvent.setup();
    renderPage();

    const row = (await screen.findByText('Diwali 2026')).closest('tr')!;
    await user.click(within(row).getByRole('button', { name: 'Delete' }));
    expect(screen.getByText(/is showing now\. Deleting it switches phones back to the standard icon/)).toBeInTheDocument();
    await user.click(within(screen.getByRole('dialog')).getByRole('button', { name: 'Delete' }));

    await waitFor(() => expect(api.remove).toHaveBeenCalledWith('a1'));
    expect(await screen.findByText('Deleted “Diwali 2026”.')).toBeInTheDocument();
  });

  it('shows a load failure instead of an empty schedule', async () => {
    mockAuth(['SYSTEM_SETTINGS']);
    api.list.mockRejectedValue(new Error('network'));
    renderPage();

    expect(await screen.findByText('The schedule could not be loaded.')).toBeInTheDocument();
    expect(screen.queryByText('Nothing scheduled. The standard icon shows.')).not.toBeInTheDocument();
  });
});
