import { describe, it, expect, vi, beforeEach } from 'vitest';
import { render, screen, waitFor, within } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { MemoryRouter } from 'react-router-dom';
import { QueryClient, QueryClientProvider } from '@tanstack/react-query';
import SupportTickets from './SupportTickets';
import { useAdminAuth } from '../context/AdminAuthContext';
import { mockAdminAuthState } from '../test/mockAdminAuth';
import { adminSupportTicketApi } from '../api/endpoints';
import type { SupportTicketRow } from '../types';

vi.mock('../context/ThemeContext', () => ({
  useTheme: () => ({ theme: 'system', resolvedTheme: 'light', setTheme: vi.fn() }),
}));
vi.mock('../context/AdminAuthContext', () => ({
  useAdminAuth: vi.fn(),
}));
vi.mock('../api/endpoints', () => ({
  adminSupportTicketApi: { list: vi.fn() },
}));

const row: SupportTicketRow = {
  id: '11111111-1111-1111-1111-111111111111',
  ticketNumber: 'SUP-000042',
  userId: '22222222-2222-2222-2222-222222222222',
  customerName: 'Asha Rao',
  customerEmail: 'asha@example.com',
  category: 'STATEMENT_IMPORT',
  subject: 'Import stuck at 60%',
  status: 'OPEN',
  claimedByAdminId: null,
  claimedByAdminName: null,
  claimedByYou: false,
  createdAt: '2026-09-01T08:00:00Z',
  updatedAt: '2026-09-01T08:00:00Z',
  duplicateOfTicketId: null,
  duplicateOfTicketNumber: null,
};

function renderPage() {
  const queryClient = new QueryClient({ defaultOptions: { queries: { retry: false } } });
  return render(
    <QueryClientProvider client={queryClient}>
      <MemoryRouter>
        <SupportTickets />
      </MemoryRouter>
    </QueryClientProvider>
  );
}

function mockAuth(permissions: string[]) {
  vi.mocked(useAdminAuth).mockReturnValue(mockAdminAuthState({
    hasPermission: (p: string) => permissions.includes(p),
    permissions,
  }));
}

describe('SupportTickets', () => {
  beforeEach(() => {
    vi.clearAllMocks();
    vi.mocked(adminSupportTicketApi.list).mockResolvedValue({
      content: [row], page: 0, size: 25, totalElements: 1, totalPages: 1,
    });
  });

  it('is gated on SUPPORT_MANAGE, not on any permission an admin happens to hold', () => {
    mockAuth(['TRUST_REVIEW_MANAGE']);
    renderPage();

    expect(screen.getByText(/don't have access to this section/i)).toBeInTheDocument();
    expect(adminSupportTicketApi.list).not.toHaveBeenCalled();
  });

  it('says the queue could not be loaded when the request fails', async () => {
    mockAuth(['SUPPORT_MANAGE']);
    vi.mocked(adminSupportTicketApi.list).mockRejectedValue(new Error('Network Error'));
    renderPage();

    expect(await screen.findByRole('alert')).toHaveTextContent("Couldn't load this list. Please try again.");
    expect(screen.queryByText('No support tickets match this filter.')).not.toBeInTheDocument();
  });

  it('lists a ticket with its number, subject, category, status and claimed-by column', async () => {
    mockAuth(['SUPPORT_MANAGE']);
    renderPage();

    expect(await screen.findByText('SUP-000042')).toBeInTheDocument();
    // Scoped to the table, not the page as a whole: "Statement import" also appears as an
    // <option> inside the category filter dropdown, and getByText fails on more than one match.
    const table = within(screen.getByRole('table'));
    expect(table.getByText('Import stuck at 60%')).toBeInTheDocument();
    expect(table.getByText('Statement import')).toBeInTheDocument();
    expect(table.getByText('OPEN')).toBeInTheDocument();
    expect(table.getByText('—')).toBeInTheDocument(); // unclaimed
  });

  it('says which ticket a closed duplicate duplicates, and says nothing for an ordinary ticket', async () => {
    mockAuth(['SUPPORT_MANAGE']);
    vi.mocked(adminSupportTicketApi.list).mockResolvedValue({
      content: [
        row,
        { ...row, id: '33333333-3333-3333-3333-333333333333', ticketNumber: 'SUP-000043', status: 'CLOSED',
          duplicateOfTicketId: row.id, duplicateOfTicketNumber: 'SUP-000042' },
      ],
      page: 0, size: 25, totalElements: 2, totalPages: 1,
    });
    renderPage();

    await screen.findByText('SUP-000043');
    const table = within(screen.getByRole('table'));
    expect(table.getAllByText(/Duplicate of/)).toHaveLength(1);
    expect(table.getByText('Duplicate of SUP-000042')).toBeInTheDocument();
  });

  it('links the ticket number into the detail route', async () => {
    mockAuth(['SUPPORT_MANAGE']);
    renderPage();

    const link = await screen.findByRole('link', { name: 'SUP-000042' });
    expect(link).toHaveAttribute('href', '/support-tickets/11111111-1111-1111-1111-111111111111');
  });

  it('refetches with the status filter applied', async () => {
    mockAuth(['SUPPORT_MANAGE']);
    const user = userEvent.setup();
    renderPage();
    await screen.findByText('SUP-000042');

    await user.selectOptions(screen.getByLabelText(/filter by status/i), 'RESOLVED');

    expect(adminSupportTicketApi.list).toHaveBeenLastCalledWith(
      expect.objectContaining({ status: 'RESOLVED', page: 0 })
    );
  });

  it('searches only once Apply is pressed, not on every keystroke, and sends the trimmed term', async () => {
    mockAuth(['SUPPORT_MANAGE']);
    const user = userEvent.setup();
    renderPage();
    await screen.findByText('SUP-000042');
    const callsBeforeTyping = vi.mocked(adminSupportTicketApi.list).mock.calls.length;

    await user.type(screen.getByPlaceholderText(/search ticket number or subject/i), '  stuck import  ');
    expect(adminSupportTicketApi.list).toHaveBeenCalledTimes(callsBeforeTyping);

    await user.click(screen.getByRole('button', { name: 'Search' }));

    expect(adminSupportTicketApi.list).toHaveBeenLastCalledWith(
      expect.objectContaining({ q: 'stuck import', page: 0 })
    );
  });

  // ---- 2026-11-12 ----

  it('names the customer, linked to their profile, and the admin holding each ticket', async () => {
    mockAuth(['SUPPORT_MANAGE', 'USER_VIEW']);
    vi.mocked(adminSupportTicketApi.list).mockResolvedValue({
      content: [
        { ...row, claimedByAdminId: 'a1', claimedByAdminName: 'Colleague Admin' },
        { ...row, id: 'row-2', ticketNumber: 'SUP-000043', claimedByAdminId: 'me', claimedByAdminName: 'Me', claimedByYou: true },
      ],
      page: 0, size: 25, totalElements: 2, totalPages: 1,
    });
    renderPage();

    await screen.findByText('SUP-000043');
    const table = within(screen.getByRole('table'));
    expect(table.getAllByRole('link', { name: 'Asha Rao' })[0]).toHaveAttribute('href', '/users/22222222-2222-2222-2222-222222222222');
    expect(table.getAllByText('asha@example.com')).toHaveLength(2);
    expect(table.getByText('Colleague Admin')).toBeInTheDocument();
    expect(table.getByText('You')).toBeInTheDocument();
  });

  it('shows the customer as hidden without USER_VIEW', async () => {
    mockAuth(['SUPPORT_MANAGE']);
    vi.mocked(adminSupportTicketApi.list).mockResolvedValue({
      content: [{ ...row, customerName: null, customerEmail: null }], page: 0, size: 25, totalElements: 1, totalPages: 1,
    });
    renderPage();

    await screen.findByText('SUP-000042');
    expect(within(screen.getByRole('table')).getByText('Hidden')).toBeInTheDocument();
  });

  it('narrows the queue to my tickets, or to unclaimed ones', async () => {
    mockAuth(['SUPPORT_MANAGE']);
    const user = userEvent.setup();
    renderPage();
    await screen.findByText('SUP-000042');

    await user.selectOptions(screen.getByLabelText('Filter by who holds it'), 'MINE');
    await waitFor(() => expect(adminSupportTicketApi.list).toHaveBeenLastCalledWith(expect.objectContaining({ claim: 'MINE', page: 0 })));
    await user.selectOptions(screen.getByLabelText('Filter by who holds it'), 'UNCLAIMED');
    await waitFor(() => expect(adminSupportTicketApi.list).toHaveBeenLastCalledWith(expect.objectContaining({ claim: 'UNCLAIMED' })));
  });

  it('says the queue is oldest first, as the server sorts it', () => {
    mockAuth(['SUPPORT_MANAGE']);
    renderPage();

    expect(screen.getByText(/oldest first/)).toBeInTheDocument();
  });
});
