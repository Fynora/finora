import { describe, it, expect, vi, beforeEach } from 'vitest';
import { render, screen, waitFor, within } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { QueryClient, QueryClientProvider } from '@tanstack/react-query';
import { SuspendedAccountScreen } from './SuspendedAccountScreen';
import { suspensionApi, type SuspensionStatus } from '../api/endpoints';

vi.mock('../api/endpoints', () => ({
  suspensionApi: { status: vi.fn(), raiseRequest: vi.fn(), exportData: vi.fn() },
  accountLifecycleApi: { exportData: vi.fn() },
}));

const BASE: SuspensionStatus = {
  suspendedAt: '2026-11-01T10:00:00Z',
  reason: 'FRAUD',
  reasonText: 'We found activity on your account that looks like fraud.',
  message: 'Chargebacks on synthetic cards.',
  storePlatform: null,
  signInMethod: 'PASSWORD',
  requests: [],
};

function renderScreen(onClose = vi.fn()) {
  render(
    <QueryClientProvider client={new QueryClient({ defaultOptions: { queries: { retry: false } } })}>
      <SuspendedAccountScreen token="suspended-token" onClose={onClose} />
    </QueryClientProvider>
  );
  return { onClose };
}

describe('SuspendedAccountScreen', () => {
  beforeEach(() => {
    vi.mocked(suspensionApi.status).mockReset();
    vi.mocked(suspensionApi.raiseRequest).mockReset();
    vi.mocked(suspensionApi.exportData).mockReset();
  });

  it('shows why the account was suspended, since when, and the message from Fynora', async () => {
    vi.mocked(suspensionApi.status).mockResolvedValue(BASE);
    renderScreen();

    expect(await screen.findByText(BASE.reasonText)).toBeInTheDocument();
    expect(screen.getByText('Message from Fynora: Chargebacks on synthetic cards.')).toBeInTheDocument();
    expect(screen.getByText(/Since 1 November 2026/)).toBeInTheDocument();
    expect(suspensionApi.status).toHaveBeenCalledWith('suspended-token');
  });

  it('names the store when Plus is paid through one', async () => {
    vi.mocked(suspensionApi.status).mockResolvedValue({ ...BASE, storePlatform: 'IOS' });
    renderScreen();

    expect(await screen.findByText(/paid through the App Store/)).toBeInTheDocument();
  });

  it('sends an appeal with its text, and then shows it as with Fynora', async () => {
    vi.mocked(suspensionApi.status).mockResolvedValue(BASE);
    vi.mocked(suspensionApi.raiseRequest).mockResolvedValue({
      ...BASE,
      requests: [{ type: 'APPEAL', ticketNumber: 'SUP-000001', status: 'OPEN', createdAt: '2026-11-02T10:00:00Z' }],
    });
    renderScreen();

    const send = await screen.findByRole('button', { name: 'Send appeal' });
    expect(send).toBeDisabled();
    await userEvent.type(screen.getByLabelText(/why you think this is a mistake/i), '  Those were my own cards.  ');
    await userEvent.click(send);

    await waitFor(() => expect(suspensionApi.raiseRequest).toHaveBeenCalledWith('suspended-token', 'APPEAL', 'Those were my own cards.'));
    expect(await screen.findByText(/Your appeal \(SUP-000001\) is with us/)).toBeInTheDocument();
    expect(screen.queryByRole('button', { name: 'Send appeal' })).not.toBeInTheDocument();
  });

  it('asks before sending a deletion request', async () => {
    vi.mocked(suspensionApi.status).mockResolvedValue(BASE);
    vi.mocked(suspensionApi.raiseRequest).mockResolvedValue({
      ...BASE,
      requests: [{ type: 'DELETION', ticketNumber: 'SUP-000002', status: 'OPEN', createdAt: '2026-11-02T10:00:00Z' }],
    });
    renderScreen();

    await userEvent.click(await screen.findByRole('button', { name: 'Ask us to delete my account' }));
    expect(suspensionApi.raiseRequest).not.toHaveBeenCalled();
    await userEvent.click(screen.getByRole('button', { name: 'Send deletion request' }));

    await waitFor(() => expect(suspensionApi.raiseRequest).toHaveBeenCalledWith('suspended-token', 'DELETION', undefined));
    expect(await screen.findByText(/Your deletion request \(SUP-000002\) is with us/)).toBeInTheDocument();
  });

  it('shows the backend refusal when a request cannot be sent', async () => {
    vi.mocked(suspensionApi.status).mockResolvedValue(BASE);
    vi.mocked(suspensionApi.raiseRequest).mockRejectedValue({
      response: { data: { message: "Your appeal is already with us. We'll email you when we've reviewed it." } },
    });
    renderScreen();

    await userEvent.type(await screen.findByLabelText(/why you think this is a mistake/i), 'Again.');
    await userEvent.click(screen.getByRole('button', { name: 'Send appeal' }));

    expect(await screen.findByRole('alert')).toHaveTextContent('Your appeal is already with us.');
  });

  // The token lasts 15 minutes: an appeal written slowly must not be lost when it has expired.
  it('keeps the appeal text and explains, when the page has timed out', async () => {
    vi.mocked(suspensionApi.status).mockResolvedValue(BASE);
    vi.mocked(suspensionApi.raiseRequest).mockRejectedValue({ response: { status: 401, data: {} } });
    renderScreen();

    const box = await screen.findByLabelText(/why you think this is a mistake/i);
    await userEvent.type(box, 'A long appeal.');
    await userEvent.click(screen.getByRole('button', { name: 'Send appeal' }));

    expect(await screen.findByRole('alert')).toHaveTextContent('This page timed out.');
    expect(box).toHaveValue('A long appeal.');
  });

  it('downloads the data with the suspended token, not the app session', async () => {
    vi.mocked(suspensionApi.status).mockResolvedValue(BASE);
    vi.mocked(suspensionApi.exportData).mockResolvedValue(undefined);
    renderScreen();

    await userEvent.click(await screen.findByRole('button', { name: 'Download my data' }));
    const dialog = await screen.findByTestId('export-data-modal');
    await userEvent.type(within(dialog).getByLabelText(/password/i), 'correct-password');
    await userEvent.click(within(dialog).getByRole('button', { name: /export|download/i }));

    await waitFor(() => expect(suspensionApi.exportData).toHaveBeenCalledWith('suspended-token', 'correct-password', null, null));
  });

  it('explains a timed-out page when the download is refused for an expired token', async () => {
    vi.mocked(suspensionApi.status).mockResolvedValue(BASE);
    vi.mocked(suspensionApi.exportData).mockRejectedValue({ response: { status: 401, data: { message: 'Unauthorized' } } });
    renderScreen();

    await userEvent.click(await screen.findByRole('button', { name: 'Download my data' }));
    const dialog = await screen.findByTestId('export-data-modal');
    await userEvent.type(within(dialog).getByLabelText(/password/i), 'correct-password');
    await userEvent.click(within(dialog).getByRole('button', { name: /export|download/i }));

    expect(await within(dialog).findByText(/This page timed out/)).toBeInTheDocument();
  });

  it('goes back to sign-in', async () => {
    vi.mocked(suspensionApi.status).mockResolvedValue(BASE);
    const { onClose } = renderScreen();

    await userEvent.click(await screen.findByRole('button', { name: 'Back to sign in' }));
    expect(onClose).toHaveBeenCalled();
  });
});
