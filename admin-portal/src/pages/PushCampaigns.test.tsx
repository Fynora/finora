import { describe, it, expect, vi, beforeEach } from 'vitest';
import { render, screen, waitFor, within } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { MemoryRouter } from 'react-router-dom';
import { QueryClient, QueryClientProvider } from '@tanstack/react-query';
import PushCampaigns from './PushCampaigns';
import { useAdminAuth } from '../context/AdminAuthContext';
import { mockAdminAuthState } from '../test/mockAdminAuth';
import { adminPushCampaignApi } from '../api/endpoints';
import type { PushCampaign, PushCampaignDetail, PushCampaignRun } from '../types';

// Same two mocks as Notifications.test.tsx: AdminLayout renders ThemeToggle and needs auth state.
vi.mock('../context/ThemeContext', () => ({
  useTheme: () => ({ theme: 'system', resolvedTheme: 'light', setTheme: vi.fn() }),
}));
vi.mock('../context/AdminAuthContext', () => ({
  useAdminAuth: vi.fn(),
}));
vi.mock('../api/endpoints', () => ({
  adminPushCampaignApi: {
    list: vi.fn(),
    get: vi.fn(),
    audienceCount: vi.fn(),
    create: vi.fn(),
    update: vi.fn(),
    clone: vi.fn(),
    sendTest: vi.fn(),
    sendNow: vi.fn(),
    start: vi.fn(),
    pause: vi.fn(),
    resume: vi.fn(),
    stop: vi.fn(),
    cancelSending: vi.fn(),
  },
}));

const api = vi.mocked(adminPushCampaignApi);

function campaign(overrides: Partial<PushCampaign> = {}): PushCampaign {
  return {
    id: 'c1',
    name: 'Upload your first statement',
    title: 'Your money, in one place',
    message: 'Upload a statement and see where it all goes.',
    audienceType: 'NO_STATEMENT_UPLOADED',
    scheduleKind: 'DAILY_AT',
    runAt: null,
    sendTimeIst: '09:00:00',
    endsOn: null,
    status: 'DRAFT',
    nextRunAt: null,
    lastTestedAt: null,
    lastTestedBy: null,
    createdBy: 'admin-1',
    createdAt: '2026-10-05T10:00:00Z',
    updatedAt: '2026-10-05T10:00:00Z',
    version: 3,
    ...overrides,
  };
}

function run(overrides: Partial<PushCampaignRun> = {}): PushCampaignRun {
  return {
    id: 'r1', campaignId: 'c1', campaignVersion: 3, runDateIst: '2026-10-05',
    scheduledFor: null, triggeredBy: 'ADMIN_NOW', triggeredByUser: 'admin-1',
    startedAt: '2026-10-05T10:00:00Z', finishedAt: '2026-10-05T10:01:00Z', status: 'DONE',
    audienceSize: 120, queuedCount: 110, skippedCapCount: 6, skippedAlreadyQueuedCount: 4,
    titleSnapshot: 'Your money, in one place', messageSnapshot: 'm', audienceSnapshot: 'NO_STATEMENT_UPLOADED',
    note: null, sent: 100, failed: 2, pending: 0, cancelled: 0, skipped: 8,
    ...overrides,
  };
}

function detail(c: PushCampaign, runs: PushCampaignRun[] = []): PushCampaignDetail {
  return { campaign: c, runs };
}

function renderPage() {
  const queryClient = new QueryClient({ defaultOptions: { queries: { retry: false } } });
  return render(
    <QueryClientProvider client={queryClient}>
      <MemoryRouter>
        <PushCampaigns />
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

async function openDetail(c: PushCampaign, runs: PushCampaignRun[] = []) {
  api.list.mockResolvedValue([c]);
  api.get.mockResolvedValue(detail(c, runs));
  const user = userEvent.setup();
  renderPage();
  await user.click(await screen.findByRole('button', { name: new RegExp(c.name) }));
  await screen.findByText('What people see');
  return user;
}

describe('PushCampaigns', () => {
  beforeEach(() => {
    vi.clearAllMocks();
    mockAuth(['PUSH_CAMPAIGN_MANAGE']);
    api.list.mockResolvedValue([]);
    api.audienceCount.mockResolvedValue({ audienceType: 'ALL_WITH_DEVICE', count: 50, rolloutLimit: 100 });
  });

  it('is gated on PUSH_CAMPAIGN_MANAGE, not on the read-only notification permission', () => {
    mockAuth(['NOTIFICATION_MANAGE']);
    renderPage();
    expect(screen.getByText("You don't have access to this section")).toBeInTheDocument();
    expect(screen.getByText(/PUSH_CAMPAIGN_MANAGE/)).toBeInTheDocument();
    expect(api.list).not.toHaveBeenCalled();
  });

  it('lists campaigns with a plain status, audience and schedule', async () => {
    api.list.mockResolvedValue([
      campaign(),
      campaign({ id: 'c2', name: 'Weekend nudge', status: 'ACTIVE', scheduleKind: 'ONCE_AT', runAt: '2026-10-10T04:30:00Z', sendTimeIst: null, nextRunAt: '2026-10-10T04:30:00Z' }),
    ]);
    renderPage();
    expect(await screen.findByText('Upload your first statement')).toBeInTheDocument();
    expect(screen.getByText('Draft')).toBeInTheDocument();
    expect(screen.getByText('Scheduled')).toBeInTheDocument();
    expect(screen.getByText('Every day at 09:00 IST, until stopped')).toBeInTheDocument();
    // 04:30 UTC is 10:00 IST: shown in IST whatever the browser's zone.
    expect(screen.getAllByText(/10 Oct 2026, 10:00 IST/).length).toBeGreaterThan(0);
  });

  it('shows an empty state with no campaigns', async () => {
    renderPage();
    expect(await screen.findByText(/No campaigns yet/)).toBeInTheDocument();
  });

  it('says the load failed, not that nothing exists, when the list cannot be fetched', async () => {
    api.list.mockRejectedValue({ response: { data: { message: 'Something broke.' } } });
    renderPage();
    expect(await screen.findByText('Something broke.')).toBeInTheDocument();
    expect(screen.getByText('Campaigns could not be loaded.')).toBeInTheDocument();
    expect(screen.queryByText(/No campaigns yet/)).toBeNull();
  });

  describe('editor', () => {
    async function openEditor() {
      const user = userEvent.setup();
      renderPage();
      await user.click(await screen.findByRole('button', { name: /New campaign/ }));
      return user;
    }

    it('refuses a daily time outside 07:00-21:59 IST without calling the API', async () => {
      const user = await openEditor();
      await user.type(screen.getByLabelText(/Campaign name/), 'Late');
      await user.type(screen.getByLabelText(/Notification title/), 'Hello');
      await user.type(screen.getByLabelText(/^Message/), 'World');
      await user.click(screen.getByLabelText('Every day at a time'));
      await user.type(screen.getByLabelText(/Time of day/), '22:00');
      await user.click(screen.getByRole('button', { name: 'Save as draft' }));

      // The error banner, not the always-visible hint under the schedule ("go out between ...").
      expect(await screen.findByText(/Scheduled sends must be between 07:00 and 21:59 IST/)).toBeInTheDocument();
      expect(api.create).not.toHaveBeenCalled();
    });

    it('saves a daily campaign with the time as IST and no one-off date', async () => {
      api.create.mockResolvedValue(campaign({ id: 'new' }));
      api.get.mockResolvedValue(detail(campaign({ id: 'new' })));
      const user = await openEditor();
      await user.type(screen.getByLabelText(/Campaign name/), '  Nudge  ');
      await user.type(screen.getByLabelText(/Notification title/), 'Hello');
      await user.type(screen.getByLabelText(/^Message/), 'World');
      await user.click(screen.getByLabelText('Every day at a time'));
      await user.type(screen.getByLabelText(/Time of day/), '09:30');
      await user.click(screen.getByRole('button', { name: 'Save as draft' }));

      await waitFor(() => expect(api.create).toHaveBeenCalledTimes(1));
      expect(api.create).toHaveBeenCalledWith({
        name: 'Nudge', title: 'Hello', message: 'World', audienceType: 'ALL_WITH_DEVICE',
        scheduleKind: 'DAILY_AT', runAt: null, sendTimeIst: '09:30', endsOn: null, expectedVersion: null,
      });
      // Lands on the new campaign's page.
      expect(await screen.findByText('What people see')).toBeInTheDocument();
    });

    it('converts a one-time IST date and time to the right instant', async () => {
      api.create.mockResolvedValue(campaign({ id: 'new' }));
      api.get.mockResolvedValue(detail(campaign({ id: 'new' })));
      const user = await openEditor();
      await user.type(screen.getByLabelText(/Campaign name/), 'Once');
      await user.type(screen.getByLabelText(/Notification title/), 'Hello');
      await user.type(screen.getByLabelText(/^Message/), 'World');
      await user.click(screen.getByLabelText('Once, at a date and time'));
      await user.type(screen.getByLabelText(/Date and time/), '2099-01-02T19:30');
      await user.click(screen.getByRole('button', { name: 'Save as draft' }));

      await waitFor(() => expect(api.create).toHaveBeenCalledTimes(1));
      expect(api.create.mock.calls[0][0]).toMatchObject({
        scheduleKind: 'ONCE_AT', runAt: '2099-01-02T14:00:00.000Z', sendTimeIst: null, endsOn: null,
      });
    });

    it('warns when the audience is over the rollout limit', async () => {
      api.audienceCount.mockResolvedValue({ audienceType: 'ALL_WITH_DEVICE', count: 450, rolloutLimit: 100 });
      await openEditor();
      expect(await screen.findByText(/over the current rollout limit of 100/)).toBeInTheDocument();
    });

    it('shows the server message when the save is refused as stale, and sends the loaded version', async () => {
      const c = campaign({ version: 7 });
      api.update.mockRejectedValue({ response: { data: { message: 'This campaign was changed by someone else since you opened it.' } } });
      const user = await openDetail(c);
      await user.click(screen.getByRole('button', { name: 'Edit' }));
      await user.click(screen.getByRole('button', { name: 'Save changes' }));

      expect(await screen.findByText(/changed by someone else/)).toBeInTheDocument();
      expect(api.update).toHaveBeenCalledWith('c1', expect.objectContaining({ expectedVersion: 7, sendTimeIst: '09:00' }));
    });
  });

  describe('campaign page', () => {
    it('offers Start, Edit and Send now for a scheduled draft, but not Pause or Resume', async () => {
      await openDetail(campaign());
      for (const name of ['Edit', 'Start', 'Send now', 'Clone', 'End campaign']) {
        expect(screen.getByRole('button', { name })).toBeInTheDocument();
      }
      expect(screen.queryByRole('button', { name: 'Pause' })).toBeNull();
      expect(screen.queryByRole('button', { name: 'Resume' })).toBeNull();
    });

    it('offers no Start for a send-now-only draft', async () => {
      await openDetail(campaign({ scheduleKind: 'NOW_ONLY', sendTimeIst: null }));
      expect(screen.queryByRole('button', { name: 'Start' })).toBeNull();
      expect(screen.getByRole('button', { name: 'Send now' })).toBeInTheDocument();
    });

    it('lets a running campaign be paused but not edited', async () => {
      await openDetail(campaign({ status: 'ACTIVE', nextRunAt: '2026-10-06T03:30:00Z' }));
      expect(screen.getByRole('button', { name: 'Pause' })).toBeInTheDocument();
      expect(screen.queryByRole('button', { name: 'Edit' })).toBeNull();
      expect(screen.getByText(/Next send: 06 Oct 2026, 09:00 IST/)).toBeInTheDocument();
    });

    it('lets a paused campaign be edited and resumed', async () => {
      await openDetail(campaign({ status: 'PAUSED' }));
      expect(screen.getByRole('button', { name: 'Edit' })).toBeInTheDocument();
      expect(screen.getByRole('button', { name: 'Resume' })).toBeInTheDocument();
    });

    it('offers only Clone and the emergency brake once finished', async () => {
      await openDetail(campaign({ status: 'COMPLETED' }));
      expect(screen.getByRole('button', { name: 'Clone' })).toBeInTheDocument();
      expect(screen.getByRole('button', { name: 'Stop sending now' })).toBeInTheDocument();
      for (const name of ['Edit', 'Start', 'Resume', 'Pause', 'Send now', 'End campaign']) {
        expect(screen.queryByRole('button', { name })).toBeNull();
      }
    });

    it('confirms Send now with the audience size and sends only after the click', async () => {
      api.audienceCount.mockResolvedValue({ audienceType: 'NO_STATEMENT_UPLOADED', count: 250, rolloutLimit: 1000 });
      api.sendNow.mockResolvedValue(run({ status: 'RUNNING', audienceSize: 250 }));
      const user = await openDetail(campaign());
      await user.click(screen.getByRole('button', { name: 'Send now' }));

      const dialog = await screen.findByRole('dialog');
      expect(await within(dialog).findByText(/about 250 people right now/)).toBeInTheDocument();
      expect(within(dialog).getByText(/roughly 3 minute/)).toBeInTheDocument();
      expect(api.sendNow).not.toHaveBeenCalled();

      await user.click(within(dialog).getByRole('button', { name: 'Send now' }));
      await waitFor(() => expect(api.sendNow).toHaveBeenCalledWith('c1'));
      expect(await screen.findByText(/Sending started/)).toBeInTheDocument();
    });

    it('needs SEND typed above 1,000 people, and exactly 1,000 does not', async () => {
      api.audienceCount.mockResolvedValue({ audienceType: 'NO_STATEMENT_UPLOADED', count: 1001, rolloutLimit: 100000 });
      api.sendNow.mockResolvedValue(run({ status: 'RUNNING' }));
      const user = await openDetail(campaign());
      await user.click(screen.getByRole('button', { name: 'Send now' }));

      const dialog = await screen.findByRole('dialog');
      const confirmButton = await within(dialog).findByRole('button', { name: 'Send now' });
      expect(confirmButton).toBeDisabled();
      await user.type(within(dialog).getByLabelText(/Type SEND/), 'send');
      expect(confirmButton).toBeDisabled();
      await user.clear(within(dialog).getByLabelText(/Type SEND/));
      await user.type(within(dialog).getByLabelText(/Type SEND/), 'SEND');
      expect(confirmButton).toBeEnabled();
      await user.click(confirmButton);
      await waitFor(() => expect(api.sendNow).toHaveBeenCalledTimes(1));
    });

    it('does not ask for SEND at exactly 1,000', async () => {
      api.audienceCount.mockResolvedValue({ audienceType: 'NO_STATEMENT_UPLOADED', count: 1000, rolloutLimit: 100000 });
      const user = await openDetail(campaign());
      await user.click(screen.getByRole('button', { name: 'Send now' }));
      const dialog = await screen.findByRole('dialog');
      await within(dialog).findByText(/about 1,000 people/);
      expect(within(dialog).queryByLabelText(/Type SEND/)).toBeNull();
      expect(within(dialog).getByRole('button', { name: 'Send now' })).toBeEnabled();
    });

    it('treats an audience it could not count as large and asks for SEND', async () => {
      api.audienceCount.mockRejectedValue(new Error('boom'));
      const user = await openDetail(campaign());
      await user.click(screen.getByRole('button', { name: 'Send now' }));
      const dialog = await screen.findByRole('dialog');
      expect(await within(dialog).findByLabelText(/Type SEND/)).toBeInTheDocument();
      expect(within(dialog).getByRole('button', { name: 'Send now' })).toBeDisabled();
    });

    it('blocks Send now over the rollout limit instead of letting the server refuse it', async () => {
      api.audienceCount.mockResolvedValue({ audienceType: 'NO_STATEMENT_UPLOADED', count: 450, rolloutLimit: 100 });
      const user = await openDetail(campaign());
      await user.click(screen.getByRole('button', { name: 'Send now' }));
      const dialog = await screen.findByRole('dialog');
      expect(await within(dialog).findByText(/over the rollout limit of 100/)).toBeInTheDocument();
      expect(within(dialog).getByRole('button', { name: 'Send now' })).toBeDisabled();
    });

    it('shows the server refusal in words when an action is rejected', async () => {
      api.start.mockRejectedValue({ response: { data: { message: 'The scheduled time has already passed. Edit it to a future time.' } } });
      const user = await openDetail(campaign());
      await user.click(screen.getByRole('button', { name: 'Start' }));
      await user.click(within(await screen.findByRole('dialog')).getByRole('button', { name: 'Start' }));
      expect(await screen.findByRole('alert')).toHaveTextContent('The scheduled time has already passed');
    });

    it('reports what the emergency brake did', async () => {
      api.cancelSending.mockResolvedValue({ cancelledPushes: 42, releasedSlots: 42 });
      const user = await openDetail(campaign({ status: 'COMPLETED' }));
      await user.click(screen.getByRole('button', { name: 'Stop sending now' }));
      const dialog = await screen.findByRole('dialog');
      expect(dialog).toHaveTextContent('at most one batch');
      await user.click(within(dialog).getByRole('button', { name: 'Stop sending' }));
      expect(await screen.findByText(/Withdrew 42 queued push/)).toBeInTheDocument();
    });

    it('sends a test by email, or by user id when it looks like one', async () => {
      api.sendTest.mockResolvedValue({ queued: true, detail: 'Test queued.' });
      const user = await openDetail(campaign());
      const box = screen.getByLabelText('Test recipient email or user id');

      await user.type(box, 'person@example.com');
      await user.click(screen.getByRole('button', { name: 'Send test' }));
      await waitFor(() => expect(api.sendTest).toHaveBeenCalledWith('c1', { email: 'person@example.com' }));
      expect(await screen.findByText('Test queued.')).toBeInTheDocument();

      await user.clear(box);
      await user.type(box, 'aaaaaaaa-bbbb-4ccc-8ddd-eeeeeeeeeeee');
      await user.click(screen.getByRole('button', { name: 'Send test' }));
      await waitFor(() => expect(api.sendTest).toHaveBeenLastCalledWith('c1', { userId: 'aaaaaaaa-bbbb-4ccc-8ddd-eeeeeeeeeeee' }));
    });

    it('shows why a test was not sent, in the server\'s words', async () => {
      api.sendTest.mockResolvedValue({ queued: false, detail: 'Nothing was sent: this account has no registered device.' });
      const user = await openDetail(campaign());
      await user.type(screen.getByLabelText('Test recipient email or user id'), 'test.user@example.com');
      await user.click(screen.getByRole('button', { name: 'Send test' }));
      expect(await screen.findByText(/no registered device/)).toBeInTheDocument();
    });

    it('does not send a test with nothing typed', async () => {
      await openDetail(campaign());
      expect(screen.getByRole('button', { name: 'Send test' })).toBeDisabled();
    });

    it('shows each run with live delivery counts, and a missed run with no counts', async () => {
      await openDetail(campaign({ status: 'ACTIVE' }), [
        run(),
        run({
          id: 'r2', status: 'MISSED', triggeredBy: 'SCHEDULE', note: 'Outside the allowed window.',
          sent: null, failed: null, pending: null, cancelled: null, skipped: null, audienceSize: 0, queuedCount: 0,
          skippedCapCount: 0, skippedAlreadyQueuedCount: 0,
        }),
      ]);
      expect(screen.getByText('100 delivered')).toBeInTheDocument();
      expect(screen.getByText('2 failed')).toBeInTheDocument();
      expect(screen.getByText('8 no working device')).toBeInTheDocument();
      expect(screen.getByText('6 already had a push today')).toBeInTheDocument();
      expect(screen.getByText('4 already queued')).toBeInTheDocument();
      expect(screen.getByText('Missed')).toBeInTheDocument();
      expect(screen.getByText('Outside the allowed window.')).toBeInTheDocument();
    });

    it('keeps IST upper-case in the Start confirmation', async () => {
      const user = await openDetail(campaign());
      await user.click(screen.getByRole('button', { name: 'Start' }));
      expect(await screen.findByRole('dialog')).toHaveTextContent('Schedule: Every day at 09:00 IST, until stopped.');
    });

    it('ends a campaign only through a confirmation that says it is permanent', async () => {
      api.stop.mockResolvedValue(campaign({ status: 'STOPPED' }));
      const user = await openDetail(campaign({ status: 'ACTIVE', nextRunAt: '2026-10-06T03:30:00Z' }));
      await user.click(screen.getByRole('button', { name: 'End campaign' }));
      const dialog = await screen.findByRole('dialog');
      expect(dialog).toHaveTextContent('cannot be resumed');
      expect(api.stop).not.toHaveBeenCalled();
      await user.click(within(dialog).getByRole('button', { name: 'End campaign' }));
      await waitFor(() => expect(api.stop).toHaveBeenCalledWith('c1'));
    });

    it('leaves nothing from the old campaign on screen after a clone', async () => {
      api.sendTest.mockResolvedValue({ queued: true, detail: 'Test queued.' });
      api.clone.mockResolvedValue(campaign({ id: 'c9', name: 'Copy of Upload your first statement' }));
      const user = await openDetail(campaign({ status: 'COMPLETED' }));
      await user.type(screen.getByLabelText('Test recipient email or user id'), 'person@example.com');
      await user.click(screen.getByRole('button', { name: 'Send test' }));
      expect(await screen.findByText('Test queued.')).toBeInTheDocument();

      api.get.mockResolvedValue(detail(campaign({ id: 'c9', name: 'Copy of Upload your first statement' })));
      await user.click(screen.getByRole('button', { name: 'Clone' }));
      await screen.findByText('Copy of Upload your first statement');

      expect(screen.queryByText('Test queued.')).toBeNull();
      expect(screen.getByLabelText('Test recipient email or user id')).toHaveValue('');
    });

    it('clones into a new draft and opens it', async () => {
      api.clone.mockResolvedValue(campaign({ id: 'c9', name: 'Copy of Upload your first statement' }));
      const user = await openDetail(campaign({ status: 'COMPLETED' }));
      api.get.mockResolvedValue(detail(campaign({ id: 'c9', name: 'Copy of Upload your first statement' })));
      await user.click(screen.getByRole('button', { name: 'Clone' }));
      expect(await screen.findByText('Copy of Upload your first statement')).toBeInTheDocument();
      expect(api.get).toHaveBeenLastCalledWith('c9');
    });
  });
});
