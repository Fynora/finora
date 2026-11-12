import { describe, it, expect, vi, beforeEach } from 'vitest';
import { render, screen, within } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { MemoryRouter, Routes, Route } from 'react-router-dom';
import { QueryClient, QueryClientProvider } from '@tanstack/react-query';
import SupportTicketDetail from './SupportTicketDetail';
import { useAdminAuth } from '../context/AdminAuthContext';
import { mockAdminAuthState } from '../test/mockAdminAuth';
import { adminSupportTicketApi } from '../api/endpoints';
import type { SupportTicketDetail as SupportTicketDetailDto, SupportTicketNote, SupportTicketRow } from '../types';

vi.mock('../context/ThemeContext', () => ({
  useTheme: () => ({ theme: 'system', resolvedTheme: 'light', setTheme: vi.fn() }),
}));
vi.mock('../context/AdminAuthContext', () => ({
  useAdminAuth: vi.fn(),
}));
const notify = vi.hoisted(() => ({ success: vi.fn(), error: vi.fn() }));
vi.mock('../context/NotificationContext', () => ({ useNotify: () => notify }));
vi.mock('../api/endpoints', () => ({
  adminSupportTicketApi: {
    get: vi.fn(), updateStatus: vi.fn(), notes: vi.fn(), addNote: vi.fn(),
    claim: vi.fn(), unclaim: vi.fn(), downloadAttachment: vi.fn(), markDuplicate: vi.fn(),
  },
}));

const ticketId = '11111111-1111-1111-1111-111111111111';

const ticket: SupportTicketDetailDto = {
  id: ticketId,
  ticketNumber: 'SUP-000042',
  userId: '22222222-2222-2222-2222-222222222222',
  customerName: 'Asha Rao',
  customerEmail: 'asha@example.com',
  category: 'TECHNICAL_ISSUE',
  subject: 'Import stuck at 60%',
  description: 'Progress bar froze at 60% and never finished.',
  status: 'OPEN',
  source: 'WEB',
  appVersion: null,
  claimedByAdminId: null,
  claimedByAdminName: null,
  claimedByYou: false,
  resolvedAt: null,
  closedAt: null,
  createdAt: '2026-09-01T08:00:00Z',
  updatedAt: '2026-09-01T08:00:00Z',
  attachments: [],
  duplicateOfTicketId: null,
  duplicateOfTicketNumber: null,
};

/** claim()/unclaim()/updateStatus() return the row shape (SupportTicketRow), not the full detail
 *  the page itself displays -- distinct fixtures so a copy-pasted `ticket` spread here can't hide
 *  a real shape mismatch behind a type assertion. */
const claimedRow: SupportTicketRow = {
  id: ticketId,
  ticketNumber: 'SUP-000042',
  userId: '22222222-2222-2222-2222-222222222222',
  customerName: 'Asha Rao',
  customerEmail: 'asha@example.com',
  category: 'TECHNICAL_ISSUE',
  subject: 'Import stuck at 60%',
  status: 'OPEN',
  claimedByAdminId: 'me',
  claimedByAdminName: 'Me Admin',
  claimedByYou: true,
  createdAt: '2026-09-01T08:00:00Z',
  updatedAt: '2026-09-01T08:00:00Z',
  duplicateOfTicketId: null,
  duplicateOfTicketNumber: null,
};

const note: SupportTicketNote = {
  id: 'note-1',
  adminId: '33333333-3333-3333-3333-333333333333',
  adminName: 'Ops Admin',
  note: 'Reproduced on Android 1.3.7',
  createdAt: '2026-09-01T09:00:00Z',
};

function renderPage() {
  const queryClient = new QueryClient({ defaultOptions: { queries: { retry: false } } });
  return render(
    <QueryClientProvider client={queryClient}>
      <MemoryRouter initialEntries={[`/support-tickets/${ticketId}`]}>
        <Routes>
          <Route path="/support-tickets/:id" element={<SupportTicketDetail />} />
        </Routes>
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

describe('SupportTicketDetail', () => {
  beforeEach(() => {
    vi.clearAllMocks();
    mockAuth(['SUPPORT_MANAGE']);
    vi.mocked(adminSupportTicketApi.get).mockResolvedValue(ticket);
    vi.mocked(adminSupportTicketApi.notes).mockResolvedValue([]);
  });

  it('is gated on SUPPORT_MANAGE', () => {
    mockAuth(['TRUST_REVIEW_MANAGE']);
    renderPage();

    expect(screen.getByText(/don't have access to this section/i)).toBeInTheDocument();
    expect(adminSupportTicketApi.get).not.toHaveBeenCalled();
  });

  it('renders the ticket subject, description and category', async () => {
    renderPage();

    expect(await screen.findByText('Import stuck at 60%')).toBeInTheDocument();
    expect(screen.getByText('Progress bar froze at 60% and never finished.')).toBeInTheDocument();
    expect(screen.getByText(/Technical issue/)).toBeInTheDocument();
  });

  it('only offers the legal next statuses for an OPEN ticket, never a reopen-style move', async () => {
    renderPage();
    await screen.findByText('Import stuck at 60%');

    expect(screen.getByRole('button', { name: 'Move to IN PROGRESS' })).toBeInTheDocument();
    expect(screen.getByRole('button', { name: 'Move to RESOLVED' })).toBeInTheDocument();
    expect(screen.getByRole('button', { name: 'Move to CLOSED' })).toBeInTheDocument();
  });

  // The backend allows RESOLVED -> CLOSED (SupportTicket.Status); this page offered nothing for a
  // resolved ticket until 2026-11-12, so one could never be closed from here.
  it('offers only Close for a resolved ticket', async () => {
    vi.mocked(adminSupportTicketApi.get).mockResolvedValue({ ...ticket, status: 'RESOLVED' });
    renderPage();
    await screen.findByText('Import stuck at 60%');

    const moves = screen.getAllByRole('button', { name: /Move to/ });
    expect(moves.map((b) => b.textContent)).toEqual(['Move to CLOSED']);
  });

  it('shows no status-change buttons at all for a closed ticket', async () => {
    vi.mocked(adminSupportTicketApi.get).mockResolvedValue({ ...ticket, status: 'CLOSED' });
    renderPage();

    expect(await screen.findByText(/can't change status again/i)).toBeInTheDocument();
    expect(screen.queryByRole('button', { name: /Move to/ })).not.toBeInTheDocument();
  });

  it('claims an unclaimed ticket directly, without a confirmation step', async () => {
    const user = userEvent.setup();
    vi.mocked(adminSupportTicketApi.claim).mockResolvedValue(claimedRow);
    renderPage();
    await screen.findByText('Import stuck at 60%');

    await user.click(screen.getByRole('button', { name: 'Claim' }));

    expect(adminSupportTicketApi.claim).toHaveBeenCalledWith(ticketId);
  });

  it('asks for confirmation before taking over an already-claimed ticket, naming who holds it', async () => {
    const user = userEvent.setup();
    const claimedTicket = { ...ticket, claimedByAdminId: '99999999-9999-9999-9999-999999999999', claimedByAdminName: 'Colleague Admin' };
    vi.mocked(adminSupportTicketApi.get).mockResolvedValue(claimedTicket);
    renderPage();
    await screen.findByText('Import stuck at 60%');

    await user.click(screen.getByRole('button', { name: 'Take over' }));

    expect(screen.getByText('Take over this ticket?')).toBeInTheDocument();
    expect(screen.getByText(/claimed by Colleague Admin/)).toBeInTheDocument();
    expect(adminSupportTicketApi.claim).not.toHaveBeenCalled();

    // Two "Take over" buttons exist once the dialog is open (the trigger behind it, and the
    // confirm inside it) -- scoped to the dialog's own role so this clicks the confirm, not
    // whichever "Take over" the DOM happens to list first.
    await user.click(within(screen.getByRole('dialog')).getByRole('button', { name: 'Take over' }));

    expect(adminSupportTicketApi.claim).toHaveBeenCalledWith(ticketId);
  });

  it('adds an internal note and shows it in the list', async () => {
    const user = userEvent.setup();
    vi.mocked(adminSupportTicketApi.notes).mockResolvedValueOnce([]).mockResolvedValueOnce([note]);
    vi.mocked(adminSupportTicketApi.addNote).mockResolvedValue(note);
    renderPage();
    await screen.findByText('Import stuck at 60%');
    expect(screen.getByText('No internal notes yet.')).toBeInTheDocument();

    await user.type(screen.getByPlaceholderText(/add a note/i), 'Reproduced on Android 1.3.7');
    await user.click(screen.getByRole('button', { name: 'Add note' }));

    expect(adminSupportTicketApi.addNote).toHaveBeenCalledWith(ticketId, 'Reproduced on Android 1.3.7');
    expect(await screen.findByText('Reproduced on Android 1.3.7')).toBeInTheDocument();
  });

  // The server refuses a longer note (support break-test, 2026-11-10); the box stops typing there.
  it('limits an internal note to 10,000 characters', async () => {
    renderPage();
    await screen.findByText('Import stuck at 60%');

    expect(screen.getByPlaceholderText(/add a note/i)).toHaveAttribute('maxLength', '10000');
  });

  it('downloads an attachment by filename, not by re-deriving it from the id', async () => {
    const user = userEvent.setup();
    vi.mocked(adminSupportTicketApi.get).mockResolvedValue({
      ...ticket,
      attachments: [{ id: 'att-1', filename: 'screenshot.png', contentType: 'image/png', sizeBytes: 2048 }],
    });
    renderPage();

    const attachmentButton = await screen.findByRole('button', { name: /screenshot\.png/ });
    await user.click(attachmentButton);

    expect(adminSupportTicketApi.downloadAttachment).toHaveBeenCalledWith(ticketId, 'att-1', 'screenshot.png');
  });

  describe('marking a duplicate', () => {
    const referenceId = '44444444-4444-4444-4444-444444444444';

    it('offers it for an open ticket, with the button off until a number is typed', async () => {
      renderPage();
      await screen.findByText('Import stuck at 60%');

      expect(screen.getByText(/same issue, enter that ticket/i)).toBeInTheDocument();
      expect(screen.getByRole('button', { name: 'Mark as duplicate' })).toBeDisabled();
    });

    it('asks first, naming both tickets and that the customer is emailed, and only then sends it', async () => {
      const user = userEvent.setup();
      vi.mocked(adminSupportTicketApi.markDuplicate).mockResolvedValue({
        ...claimedRow, status: 'CLOSED', claimedByAdminId: null,
        duplicateOfTicketId: referenceId, duplicateOfTicketNumber: 'SUP-000041',
      });
      renderPage();
      await screen.findByText('Import stuck at 60%');

      await user.type(screen.getByLabelText(/number of the ticket this duplicates/i), 'sup-000041');
      await user.click(screen.getByRole('button', { name: 'Mark as duplicate' }));

      const dialog = within(screen.getByRole('dialog'));
      expect(dialog.getByText(/SUP-000042 will be closed as a duplicate of SUP-000041 and the customer will be emailed/)).toBeInTheDocument();
      // Nothing is sent, and nobody is emailed, until the admin confirms.
      expect(adminSupportTicketApi.markDuplicate).not.toHaveBeenCalled();

      await user.click(dialog.getByRole('button', { name: 'Close as duplicate' }));

      expect(adminSupportTicketApi.markDuplicate).toHaveBeenCalledWith(ticketId, 'sup-000041');
    });

    it('cancelling the confirmation sends nothing', async () => {
      const user = userEvent.setup();
      renderPage();
      await screen.findByText('Import stuck at 60%');
      await user.type(screen.getByLabelText(/number of the ticket this duplicates/i), 'SUP-000041');
      await user.click(screen.getByRole('button', { name: 'Mark as duplicate' }));

      await user.click(within(screen.getByRole('dialog')).getByRole('button', { name: /cancel/i }));

      expect(screen.queryByRole('dialog')).not.toBeInTheDocument();
      expect(adminSupportTicketApi.markDuplicate).not.toHaveBeenCalled();
    });

    it("shows the server's own reason when it refuses, and closes the confirmation", async () => {
      const user = userEvent.setup();
      vi.mocked(adminSupportTicketApi.markDuplicate).mockRejectedValue({
        response: { data: { message: 'SUP-000041 belongs to a different customer.' } },
      });
      renderPage();
      await screen.findByText('Import stuck at 60%');
      await user.type(screen.getByLabelText(/number of the ticket this duplicates/i), 'SUP-000041');
      await user.click(screen.getByRole('button', { name: 'Mark as duplicate' }));

      await user.click(within(screen.getByRole('dialog')).getByRole('button', { name: 'Close as duplicate' }));

      expect(await screen.findByText('SUP-000041 belongs to a different customer.')).toBeInTheDocument();
      expect(screen.queryByRole('dialog')).not.toBeInTheDocument();
    });

    it('is not offered for a closed ticket, and a duplicate says what it duplicates, with a link', async () => {
      vi.mocked(adminSupportTicketApi.get).mockResolvedValue({
        ...ticket, status: 'CLOSED', duplicateOfTicketId: referenceId, duplicateOfTicketNumber: 'SUP-000041',
      });
      renderPage();
      await screen.findByText('Import stuck at 60%');

      expect(screen.queryByRole('button', { name: 'Mark as duplicate' })).not.toBeInTheDocument();
      expect(screen.getByText(/Closed as a duplicate of/)).toBeInTheDocument();
      expect(screen.getByRole('link', { name: 'SUP-000041' })).toHaveAttribute('href', `/support-tickets/${referenceId}`);
    });
  });

  // ---- 2026-11-12: names, platform, and telling the customer ----

  it('names the customer with a link to their profile, and the app they wrote from', async () => {
    mockAuth(['SUPPORT_MANAGE', 'USER_VIEW']);
    vi.mocked(adminSupportTicketApi.get).mockResolvedValue({ ...ticket, source: 'MOBILE_ANDROID', appVersion: '1.2.1' });
    renderPage();

    const customer = await screen.findByTestId('ticket-customer');
    expect(customer).toHaveTextContent('From Asha Rao · asha@example.com');
    expect(within(customer).getByRole('link', { name: 'Asha Rao' })).toHaveAttribute('href', '/users/22222222-2222-2222-2222-222222222222');
    expect(screen.getByText(/Sent from the Android app 1.2.1/)).toBeInTheDocument();
  });

  it('says the customer is hidden, with no profile link, without USER_VIEW', async () => {
    vi.mocked(adminSupportTicketApi.get).mockResolvedValue({ ...ticket, customerName: null, customerEmail: null });
    renderPage();

    expect(await screen.findByTestId('ticket-customer')).toHaveTextContent('Customer hidden: needs the USER_VIEW permission');
    expect(screen.queryByRole('link', { name: 'Asha Rao' })).not.toBeInTheDocument();
  });

  it('says "You" for a ticket you hold, and offers no take-over of your own ticket', async () => {
    vi.mocked(adminSupportTicketApi.get).mockResolvedValue({
      ...ticket, claimedByAdminId: 'me', claimedByAdminName: 'Me Admin', claimedByYou: true,
    });
    renderPage();

    expect(await screen.findByText(/Claimed by:/)).toHaveTextContent('Claimed by: You');
    expect(screen.queryByRole('button', { name: 'Take over' })).not.toBeInTheDocument();
    expect(screen.getByRole('button', { name: 'Unclaim' })).toBeInTheDocument();
  });

  it('names the admin who wrote each note', async () => {
    vi.mocked(adminSupportTicketApi.notes).mockResolvedValue([note]);
    renderPage();

    expect(await screen.findByText('Ops Admin')).toBeInTheDocument();
    expect(screen.queryByText('33333333-3333-3333-3333-333333333333')).not.toBeInTheDocument();
  });

  it('resolving asks first, emails the customer by default, and sends the note', async () => {
    const user = userEvent.setup();
    vi.mocked(adminSupportTicketApi.updateStatus).mockResolvedValue({
      ticket: { id: ticketId, status: 'RESOLVED' }, customerEmailed: true, notEmailedReason: null,
    });
    renderPage();
    await screen.findByText('Import stuck at 60%');

    await user.click(screen.getByRole('button', { name: 'Move to RESOLVED' }));
    expect(adminSupportTicketApi.updateStatus).not.toHaveBeenCalled();
    const dialog = screen.getByRole('dialog');
    expect(within(dialog).getByRole('checkbox', { name: /Email the customer/ })).toBeChecked();
    await user.type(within(dialog).getByLabelText(/Note to include/), 'We re-read your statement.');
    await user.click(within(dialog).getByRole('button', { name: 'Resolve' }));

    expect(adminSupportTicketApi.updateStatus).toHaveBeenCalledWith(ticketId, 'RESOLVED',
      { notifyCustomer: true, message: 'We re-read your statement.' });
    expect(notify.success).toHaveBeenCalledWith('Status changed. The customer will be emailed.');
  });

  it('says plainly when the customer could not be emailed, and why', async () => {
    const user = userEvent.setup();
    vi.mocked(adminSupportTicketApi.updateStatus).mockResolvedValue({
      ticket: { id: ticketId, status: 'CLOSED' }, customerEmailed: false,
      notEmailedReason: "The customer's email address is not verified, so no email was sent.",
    });
    renderPage();
    await screen.findByText('Import stuck at 60%');

    await user.click(screen.getByRole('button', { name: 'Move to CLOSED' }));
    await user.click(within(screen.getByRole('dialog')).getByRole('button', { name: 'Close' }));

    expect(await screen.findByText(/Status changed, but the customer was not emailed. The customer's email address is not verified/))
      .toBeInTheDocument();
  });

  it('can resolve without emailing the customer', async () => {
    const user = userEvent.setup();
    vi.mocked(adminSupportTicketApi.updateStatus).mockResolvedValue({
      ticket: { id: ticketId, status: 'RESOLVED' }, customerEmailed: false, notEmailedReason: null,
    });
    renderPage();
    await screen.findByText('Import stuck at 60%');

    await user.click(screen.getByRole('button', { name: 'Move to RESOLVED' }));
    const dialog = screen.getByRole('dialog');
    await user.click(within(dialog).getByRole('checkbox', { name: /Email the customer/ }));
    expect(within(dialog).queryByLabelText(/Note to include/)).not.toBeInTheDocument();
    await user.click(within(dialog).getByRole('button', { name: 'Resolve' }));

    expect(adminSupportTicketApi.updateStatus).toHaveBeenCalledWith(ticketId, 'RESOLVED', { notifyCustomer: false, message: undefined });
    expect(notify.success).not.toHaveBeenCalled();
  });

  it('closing an already-resolved ticket does not email the customer again unless asked', async () => {
    const user = userEvent.setup();
    vi.mocked(adminSupportTicketApi.get).mockResolvedValue({ ...ticket, status: 'RESOLVED' });
    renderPage();
    await screen.findByText('Import stuck at 60%');

    await user.click(screen.getByRole('button', { name: 'Move to CLOSED' }));

    expect(within(screen.getByRole('dialog')).getByRole('checkbox', { name: /Email the customer/ })).not.toBeChecked();
  });

  it('moving to In Progress needs no dialog and emails nobody', async () => {
    const user = userEvent.setup();
    vi.mocked(adminSupportTicketApi.updateStatus).mockResolvedValue({
      ticket: { id: ticketId, status: 'IN_PROGRESS' }, customerEmailed: false, notEmailedReason: null,
    });
    renderPage();
    await screen.findByText('Import stuck at 60%');

    await user.click(screen.getByRole('button', { name: 'Move to IN PROGRESS' }));

    expect(screen.queryByRole('dialog')).not.toBeInTheDocument();
    expect(adminSupportTicketApi.updateStatus).toHaveBeenCalledWith(ticketId, 'IN_PROGRESS', { notifyCustomer: false, message: undefined });
  });

  it('will not send a note over 2,000 characters', async () => {
    const user = userEvent.setup();
    renderPage();
    await screen.findByText('Import stuck at 60%');

    await user.click(screen.getByRole('button', { name: 'Move to RESOLVED' }));
    const dialog = screen.getByRole('dialog');
    const box = within(dialog).getByLabelText(/Note to include/);
    await user.click(box);
    await user.paste('x'.repeat(2001));

    expect(within(dialog).getByRole('button', { name: 'Resolve' })).toBeDisabled();
    expect(within(dialog).getByText(/2001 \/ 2000/)).toBeInTheDocument();
  });
});
