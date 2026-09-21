import { describe, it, expect, vi, beforeEach } from 'vitest';
import { render, screen, waitFor, within } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { MemoryRouter } from 'react-router-dom';
import { QueryClient, QueryClientProvider } from '@tanstack/react-query';
import TrustedSenders from './TrustedSenders';
import { useAdminAuth } from '../context/AdminAuthContext';
import { mockAdminAuthState } from '../test/mockAdminAuth';
import { adminTrustedSendersApi } from '../api/endpoints';
import type { TrustedSenderDto } from '../types';

// Same reasons as MerchantTemplates.test.tsx: AdminLayout renders ThemeToggle and Sidebar, and a
// real ThemeProvider / NotificationProvider / auth context is not mounted in these tests.
vi.mock('../context/ThemeContext', () => ({
  useTheme: () => ({ theme: 'system', resolvedTheme: 'light', setTheme: vi.fn() }),
}));
vi.mock('../context/AdminAuthContext', () => ({
  useAdminAuth: vi.fn(),
}));
const notifySuccess = vi.fn();
const notifyError = vi.fn();
vi.mock('../context/NotificationContext', () => ({
  useNotify: () => ({ success: notifySuccess, error: notifyError }),
}));
vi.mock('../api/endpoints', () => ({
  adminTrustedSendersApi: {
    list: vi.fn(), add: vi.fn(), relabel: vi.fn(), disable: vi.fn(), enable: vi.fn(),
  },
}));

function renderPage() {
  const queryClient = new QueryClient({ defaultOptions: { queries: { retry: false } } });
  return render(
    <QueryClientProvider client={queryClient}>
      <MemoryRouter>
        <TrustedSenders />
      </MemoryRouter>
    </QueryClientProvider>
  );
}

function mockAuth(permissions: string[]) {
  vi.mocked(useAdminAuth).mockReturnValue(mockAdminAuthState({
    hasPermission: (p: string) => permissions.includes(p),
    permissions,
    fullName: 'Support Admin',
    logout: vi.fn(),
  }));
}

function sender(overrides: Partial<TrustedSenderDto> = {}): TrustedSenderDto {
  return {
    id: 'ts-1', domain: 'amazon.in', merchantName: 'Amazon', status: 'ACTIVE',
    createdAt: '2026-08-01T00:00:00Z', updatedAt: '2026-08-01T00:00:00Z', ...overrides,
  };
}

const ACTIVE = sender();
const DISABLED = sender({ id: 'ts-2', domain: 'zeptonow.com', merchantName: 'Zepto', status: 'DISABLED' });

describe('TrustedSenders', () => {
  beforeEach(() => {
    vi.mocked(useAdminAuth).mockReset();
    for (const fn of Object.values(adminTrustedSendersApi)) vi.mocked(fn).mockReset();
    notifySuccess.mockReset();
    notifyError.mockReset();
  });

  it('shows an access-denied message when the account lacks SYSTEM_SETTINGS', () => {
    mockAuth(['MERCHANT_MANAGE']);
    vi.mocked(adminTrustedSendersApi.list).mockResolvedValue([]);

    renderPage();

    expect(screen.getByText("You don't have access to this section")).toBeInTheDocument();
    expect(adminTrustedSendersApi.list).not.toHaveBeenCalled();
  });

  it('lists senders with their status and a trusted/disabled count', async () => {
    mockAuth(['SYSTEM_SETTINGS']);
    vi.mocked(adminTrustedSendersApi.list).mockResolvedValue([ACTIVE, DISABLED]);

    renderPage();

    expect(await screen.findByText(/amazon\.in/)).toBeInTheDocument();
    expect(screen.getByText(/zeptonow\.com/)).toBeInTheDocument();
    expect(screen.getByText('Trusted')).toBeInTheDocument();
    expect(screen.getByText('Disabled')).toBeInTheDocument();
    expect(screen.getByText('1 trusted, 1 disabled')).toBeInTheDocument();
  });

  it('filters by domain or merchant name', async () => {
    mockAuth(['SYSTEM_SETTINGS']);
    vi.mocked(adminTrustedSendersApi.list).mockResolvedValue([ACTIVE, DISABLED]);
    renderPage();
    await screen.findByText(/amazon\.in/);

    await userEvent.type(screen.getByLabelText('Search trusted senders'), 'zep');

    expect(screen.queryByText(/amazon\.in/)).not.toBeInTheDocument();
    expect(screen.getByText(/zeptonow\.com/)).toBeInTheDocument();
  });

  it('says so when the list cannot be loaded', async () => {
    mockAuth(['SYSTEM_SETTINGS']);
    vi.mocked(adminTrustedSendersApi.list).mockRejectedValue(new Error('boom'));

    renderPage();

    expect(await screen.findByText('Could not load the trusted senders.')).toBeInTheDocument();
  });

  describe('adding a domain', () => {
    async function openAddForm() {
      mockAuth(['SYSTEM_SETTINGS']);
      vi.mocked(adminTrustedSendersApi.list).mockResolvedValue([ACTIVE]);
      renderPage();
      await screen.findByText(/amazon\.in/);
      await userEvent.click(screen.getByRole('button', { name: /Trust a domain/ }));
    }

    it('asks for confirmation showing the exact normalized domain, and adds only once confirmed', async () => {
      await openAddForm();
      vi.mocked(adminTrustedSendersApi.add).mockResolvedValue(sender({ id: 'ts-3', domain: 'swiggy.in', merchantName: 'Swiggy' }));

      await userEvent.type(screen.getByLabelText('Sender domain'), '  Swiggy.IN. ');
      await userEvent.type(screen.getByLabelText('Merchant name'), ' Swiggy ');
      await userEvent.click(screen.getByRole('button', { name: 'Review and add' }));

      const dialog = screen.getByRole('dialog');
      expect(within(dialog).getByText('Trust swiggy.in?')).toBeInTheDocument();
      expect(adminTrustedSendersApi.add).not.toHaveBeenCalled();

      await userEvent.click(within(dialog).getByRole('button', { name: 'Trust this domain' }));

      await waitFor(() => expect(adminTrustedSendersApi.add).toHaveBeenCalledWith({
        domain: 'swiggy.in', merchantName: 'Swiggy',
      }));
      await waitFor(() => expect(notifySuccess).toHaveBeenCalledWith('swiggy.in is now trusted.'));
    });

    it('does not add anything when the confirmation is cancelled', async () => {
      await openAddForm();

      await userEvent.type(screen.getByLabelText('Sender domain'), 'swiggy.in');
      await userEvent.type(screen.getByLabelText('Merchant name'), 'Swiggy');
      await userEvent.click(screen.getByRole('button', { name: 'Review and add' }));
      await userEvent.click(within(screen.getByRole('dialog')).getByRole('button', { name: 'Cancel' }));

      expect(adminTrustedSendersApi.add).not.toHaveBeenCalled();
      expect(screen.queryByRole('dialog')).not.toBeInTheDocument();
    });

    it.each([
      ['a URL', 'https://swiggy.in'],
      ['an email address', 'orders@merchant.example'],
      ['a wildcard', '*.swiggy.in'],
      ['a value with a space', 'swiggy in'],
    ])('refuses %s before it ever reaches the confirmation', async (_label, value) => {
      await openAddForm();

      await userEvent.type(screen.getByLabelText('Sender domain'), value);
      await userEvent.type(screen.getByLabelText('Merchant name'), 'Swiggy');
      await userEvent.click(screen.getByRole('button', { name: 'Review and add' }));

      expect(screen.getByText(/Enter a bare domain such as swiggy\.in/)).toBeInTheDocument();
      expect(screen.queryByRole('dialog')).not.toBeInTheDocument();
      expect(adminTrustedSendersApi.add).not.toHaveBeenCalled();
    });

    it("shows the server's reason and keeps the form when the domain is already in the registry", async () => {
      await openAddForm();
      vi.mocked(adminTrustedSendersApi.add).mockRejectedValue({
        response: { data: { message: 'That domain is already in the registry (DISABLED). Change its status instead of adding it again.' } },
      });

      await userEvent.type(screen.getByLabelText('Sender domain'), 'zeptonow.com');
      await userEvent.type(screen.getByLabelText('Merchant name'), 'Zepto');
      await userEvent.click(screen.getByRole('button', { name: 'Review and add' }));
      await userEvent.click(within(screen.getByRole('dialog')).getByRole('button', { name: 'Trust this domain' }));

      expect(await screen.findByText(/already in the registry \(DISABLED\)/)).toBeInTheDocument();
      expect(screen.queryByRole('dialog')).not.toBeInTheDocument();
      expect(screen.getByLabelText('Sender domain')).toHaveValue('zeptonow.com');
      expect(notifySuccess).not.toHaveBeenCalled();
    });
  });

  describe('changing trust', () => {
    it('disables a trusted domain only after confirmation', async () => {
      mockAuth(['SYSTEM_SETTINGS']);
      vi.mocked(adminTrustedSendersApi.list).mockResolvedValue([ACTIVE]);
      vi.mocked(adminTrustedSendersApi.disable).mockResolvedValue(sender({ status: 'DISABLED' }));
      renderPage();
      await screen.findByText(/amazon\.in/);

      await userEvent.click(screen.getByRole('button', { name: 'Disable amazon.in' }));
      expect(adminTrustedSendersApi.disable).not.toHaveBeenCalled();
      await userEvent.click(within(screen.getByRole('dialog')).getByRole('button', { name: 'Stop trusting' }));

      await waitFor(() => expect(adminTrustedSendersApi.disable).toHaveBeenCalledWith('ts-1'));
      await waitFor(() => expect(notifySuccess).toHaveBeenCalledWith('amazon.in is no longer trusted.'));
    });

    it('does not disable when the confirmation is cancelled', async () => {
      mockAuth(['SYSTEM_SETTINGS']);
      vi.mocked(adminTrustedSendersApi.list).mockResolvedValue([ACTIVE]);
      renderPage();
      await screen.findByText(/amazon\.in/);

      await userEvent.click(screen.getByRole('button', { name: 'Disable amazon.in' }));
      await userEvent.click(within(screen.getByRole('dialog')).getByRole('button', { name: 'Cancel' }));

      expect(adminTrustedSendersApi.disable).not.toHaveBeenCalled();
    });

    it('re-enables a disabled domain only after confirmation, and offers no disable for it', async () => {
      mockAuth(['SYSTEM_SETTINGS']);
      vi.mocked(adminTrustedSendersApi.list).mockResolvedValue([DISABLED]);
      vi.mocked(adminTrustedSendersApi.enable).mockResolvedValue(sender({ id: 'ts-2', status: 'ACTIVE' }));
      renderPage();
      await screen.findByText(/zeptonow\.com/);

      expect(screen.queryByRole('button', { name: 'Disable zeptonow.com' })).not.toBeInTheDocument();
      await userEvent.click(screen.getByRole('button', { name: 'Enable zeptonow.com' }));
      expect(adminTrustedSendersApi.enable).not.toHaveBeenCalled();
      await userEvent.click(within(screen.getByRole('dialog')).getByRole('button', { name: 'Trust again' }));

      await waitFor(() => expect(adminTrustedSendersApi.enable).toHaveBeenCalledWith('ts-2'));
    });

    it("surfaces the server's error when a status change fails", async () => {
      mockAuth(['SYSTEM_SETTINGS']);
      vi.mocked(adminTrustedSendersApi.list).mockResolvedValue([ACTIVE]);
      vi.mocked(adminTrustedSendersApi.disable).mockRejectedValue({ response: { data: { message: 'No such trusted sender domain.' } } });
      renderPage();
      await screen.findByText(/amazon\.in/);

      await userEvent.click(screen.getByRole('button', { name: 'Disable amazon.in' }));
      await userEvent.click(within(screen.getByRole('dialog')).getByRole('button', { name: 'Stop trusting' }));

      await waitFor(() => expect(notifyError).toHaveBeenCalledWith('No such trusted sender domain.'));
      expect(screen.queryByRole('dialog')).not.toBeInTheDocument();
    });
  });

  it('renames a sender without offering to edit its domain', async () => {
    mockAuth(['SYSTEM_SETTINGS']);
    vi.mocked(adminTrustedSendersApi.list).mockResolvedValue([ACTIVE]);
    vi.mocked(adminTrustedSendersApi.relabel).mockResolvedValue(sender({ merchantName: 'Amazon India' }));
    renderPage();
    await screen.findByText(/amazon\.in/);

    await userEvent.click(screen.getByRole('button', { name: 'Rename amazon.in' }));
    expect(screen.queryByLabelText('Sender domain')).not.toBeInTheDocument();
    const name = screen.getByLabelText('Merchant name');
    await userEvent.clear(name);
    await userEvent.type(name, 'Amazon India');
    await userEvent.click(screen.getByRole('button', { name: 'Save name' }));

    await waitFor(() => expect(adminTrustedSendersApi.relabel).toHaveBeenCalledWith('ts-1', 'Amazon India'));
  });
});
