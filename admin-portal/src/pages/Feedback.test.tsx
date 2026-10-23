import { describe, it, expect, vi, beforeEach } from 'vitest';
import { render, screen, within } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { MemoryRouter } from 'react-router-dom';
import { QueryClient, QueryClientProvider } from '@tanstack/react-query';
import Feedback from './Feedback';
import { useAdminAuth } from '../context/AdminAuthContext';
import { mockAdminAuthState } from '../test/mockAdminAuth';
import { adminFeedbackApi } from '../api/endpoints';
import type { FeedbackBreakdown, FeedbackRow } from '../types';

vi.mock('../context/ThemeContext', () => ({
  useTheme: () => ({ theme: 'system', resolvedTheme: 'light', setTheme: vi.fn() }),
}));
vi.mock('../context/AdminAuthContext', () => ({
  useAdminAuth: vi.fn(),
}));
vi.mock('../api/endpoints', () => ({
  adminFeedbackApi: { list: vi.fn(), breakdown: vi.fn() },
}));

const row: FeedbackRow = {
  id: 'fb-1',
  userId: '22222222-2222-2222-2222-222222222222',
  userName: 'Test Sender',
  userEmail: 'sender@example.com',
  type: 'BUG',
  context: 'IMPORT_FLOW',
  source: 'WEB',
  message: 'Import silently drops rows when the file has a BOM.',
  appVersion: '1.0.0',
  createdAt: '2026-09-01T08:00:00Z',
};

const breakdown: FeedbackBreakdown = {
  total: 10,
  byType: [{ label: 'BUG', total: 7 }, { label: 'FEATURE_REQUEST', total: 3 }],
  byContext: [{ label: 'IMPORT_FLOW', total: 8 }, { label: 'HELP', total: 2 }],
  bySource: [{ label: 'WEB', total: 7 }, { label: 'MOBILE_ANDROID', total: 3 }],
};

function renderPage() {
  const queryClient = new QueryClient({ defaultOptions: { queries: { retry: false } } });
  return render(
    <QueryClientProvider client={queryClient}>
      <MemoryRouter>
        <Feedback />
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

describe('Feedback', () => {
  beforeEach(() => {
    vi.clearAllMocks();
    mockAuth(['SUPPORT_MANAGE']);
    vi.mocked(adminFeedbackApi.list).mockResolvedValue({
      content: [row], page: 0, size: 25, totalElements: 1, totalPages: 1,
    });
    vi.mocked(adminFeedbackApi.breakdown).mockResolvedValue(breakdown);
  });

  it('is gated on SUPPORT_MANAGE', () => {
    mockAuth(['TRUST_REVIEW_MANAGE']);
    renderPage();

    expect(screen.getByText(/don't have access to this section/i)).toBeInTheDocument();
    expect(adminFeedbackApi.list).not.toHaveBeenCalled();
  });

  it('renders the breakdown panels by type, context and source with the grand total', async () => {
    renderPage();

    expect(await screen.findByText('By Type (10 total)')).toBeInTheDocument();
    expect(screen.getByText('BUG')).toBeInTheDocument();
    // Labels display with underscores replaced by spaces, same convention as the status/category
    // labels elsewhere in the admin portal (e.g. HeldStatements.tsx's own status cell).
    expect(screen.getByText('IMPORT FLOW')).toBeInTheDocument();
    expect(screen.getByText('MOBILE ANDROID')).toBeInTheDocument();
  });

  it('lists a feedback row with its type, context, source and message', async () => {
    renderPage();
    // findByRole('table') resolves the moment the (empty, "Loading…") table mounts, not once its
    // query has resolved -- waiting on the row's own text first is what actually waits for data.
    await screen.findByText(/Import silently drops rows/);

    const table = within(screen.getByRole('table'));
    expect(table.getByText('Bug')).toBeInTheDocument();
    expect(table.getByText('Import flow')).toBeInTheDocument();
    expect(table.getByText('WEB')).toBeInTheDocument();
    expect(table.getByText(/Import silently drops rows/)).toBeInTheDocument();
  });

  it('shows who sent each feedback in a From column', async () => {
    renderPage();
    await screen.findByText(/Import silently drops rows/);

    const table = within(screen.getByRole('table'));
    expect(table.getByRole('columnheader', { name: 'From' })).toBeInTheDocument();
    expect(table.getByText('Test Sender')).toBeInTheDocument();
    expect(table.getByText('sender@example.com')).toBeInTheDocument();
  });

  it('says "Unknown" rather than leaving From blank when there is no account to show', async () => {
    vi.mocked(adminFeedbackApi.list).mockResolvedValue({
      content: [{ ...row, userId: null, userName: null, userEmail: null }],
      page: 0, size: 25, totalElements: 1, totalPages: 1,
    });
    renderPage();
    await screen.findByText(/Import silently drops rows/);

    expect(within(screen.getByRole('table')).getByText('Unknown')).toBeInTheDocument();
  });

  it('opens the full message, with its line breaks, in a drawer when the row is clicked', async () => {
    const user = userEvent.setup();
    const long = `${'Why is my card bill counted twice? '.repeat(4)}\n\nSecond paragraph stays separate.`;
    vi.mocked(adminFeedbackApi.list).mockResolvedValue({
      content: [{ ...row, message: long }], page: 0, size: 25, totalElements: 1, totalPages: 1,
    });
    renderPage();

    // The table cell is cut at 80 characters; the full text only exists once the drawer is open.
    const open = await screen.findByRole('button', { name: /Why is my card bill counted twice/ });
    expect(open).toHaveAttribute('aria-haspopup', 'dialog');
    expect(screen.queryByRole('dialog')).not.toBeInTheDocument();
    expect(screen.queryByText(/Second paragraph stays separate/)).not.toBeInTheDocument();

    await user.click(open);

    const dialog = within(await screen.findByRole('dialog'));
    const body = dialog.getByText(/Second paragraph stays separate/);
    expect(body.textContent).toBe(long);
    expect(body).toHaveClass('whitespace-pre-wrap');
    expect(dialog.getByText('Test Sender · sender@example.com')).toBeInTheDocument();
    expect(dialog.getByText('1.0.0')).toBeInTheDocument();
    expect(dialog.getByText(row.userId as string)).toBeInTheDocument();

    await user.click(dialog.getByRole('button', { name: 'Close' }));
    expect(screen.queryByRole('dialog')).not.toBeInTheDocument();
  });

  describe('pagination', () => {
    // Three pages of 25: the server decides what is on each page, so the mock answers by the page
    // number the component actually asks for rather than returning one fixed response.
    function mockThreePages() {
      vi.mocked(adminFeedbackApi.list).mockImplementation(async (params) => {
        const page = params.page ?? 0;
        return {
          content: [{ ...row, id: `fb-page-${page}`, message: `Message on page ${page + 1}` }],
          page, size: 25, totalElements: 60, totalPages: 3,
        };
      });
    }

    it('starts on page 1 of 3 with Previous disabled, and asks the server for 25 per page', async () => {
      mockThreePages();
      renderPage();

      expect(await screen.findByText('Message on page 1')).toBeInTheDocument();
      expect(screen.getByText('Showing 1–25 of 60')).toBeInTheDocument();
      expect(screen.getByText('Page 1 of 3')).toBeInTheDocument();
      expect(screen.getByRole('button', { name: 'Previous page' })).toBeDisabled();
      expect(adminFeedbackApi.list).toHaveBeenLastCalledWith(expect.objectContaining({ page: 0, size: 25 }));
    });

    it('walks forward to the last page, where Next is disabled, and back again', async () => {
      const user = userEvent.setup();
      mockThreePages();
      renderPage();
      await screen.findByText('Message on page 1');

      await user.click(screen.getByRole('button', { name: 'Next page' }));
      expect(await screen.findByText('Message on page 2')).toBeInTheDocument();
      expect(screen.getByText('Showing 26–50 of 60')).toBeInTheDocument();
      expect(adminFeedbackApi.list).toHaveBeenLastCalledWith(expect.objectContaining({ page: 1 }));

      await user.click(screen.getByRole('button', { name: 'Next page' }));
      expect(await screen.findByText('Message on page 3')).toBeInTheDocument();
      // The last page is partial: 51-60, not 51-75.
      expect(screen.getByText('Showing 51–60 of 60')).toBeInTheDocument();
      expect(screen.getByRole('button', { name: 'Next page' })).toBeDisabled();

      await user.click(screen.getByRole('button', { name: 'Previous page' }));
      expect(await screen.findByText('Message on page 2')).toBeInTheDocument();
    });

    it('returns to page 1 when a filter changes, instead of staying on a page the filter may not have', async () => {
      const user = userEvent.setup();
      mockThreePages();
      renderPage();
      await screen.findByText('Message on page 1');
      await user.click(screen.getByRole('button', { name: 'Next page' }));
      await screen.findByText('Message on page 2');

      await user.selectOptions(screen.getByLabelText(/filter by type/i), 'BUG');

      expect(adminFeedbackApi.list).toHaveBeenLastCalledWith(expect.objectContaining({ page: 0, type: 'BUG' }));
      expect(await screen.findByText('Message on page 1')).toBeInTheDocument();
    });
  });

  it('breakdown is fetched once and never refetched when the list filter changes', async () => {
    const user = userEvent.setup();
    renderPage();
    await screen.findByText('By Type (10 total)');
    expect(adminFeedbackApi.breakdown).toHaveBeenCalledTimes(1);

    await user.selectOptions(screen.getByLabelText(/filter by type/i), 'BUG');

    expect(adminFeedbackApi.list).toHaveBeenLastCalledWith(expect.objectContaining({ type: 'BUG' }));
    expect(adminFeedbackApi.breakdown).toHaveBeenCalledTimes(1);
  });

  it('shows "No feedback yet" for an empty breakdown dimension rather than an empty bar list', async () => {
    vi.mocked(adminFeedbackApi.breakdown).mockResolvedValue({ total: 0, byType: [], byContext: [], bySource: [] });
    renderPage();

    expect((await screen.findAllByText('No feedback yet.')).length).toBe(3);
  });
});
