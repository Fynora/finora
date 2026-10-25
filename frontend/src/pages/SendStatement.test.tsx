import { describe, it, expect, vi, beforeEach } from 'vitest';
import { render, screen, waitFor } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { MemoryRouter } from 'react-router-dom';
import SendStatement from './SendStatement';
import { sendStatementApi } from '../api/endpoints';

vi.mock('../api/endpoints', () => ({
  sendStatementApi: { send: vi.fn() },
}));

const send = vi.mocked(sendStatementApi.send);

function renderPage() {
  return render(
    <MemoryRouter initialEntries={['/send']}>
      <SendStatement />
    </MemoryRouter>,
  );
}

function axiosError(status: number, errorCode: string, message: string) {
  return Object.assign(new Error(message), { response: { status, data: { errorCode, message } } });
}

async function fill(user: ReturnType<typeof userEvent.setup>, code: string, file: File) {
  await user.type(screen.getByLabelText('Code from your phone'), code);
  await user.upload(screen.getByLabelText(/statement file/i), file);
  await user.click(screen.getByRole('button', { name: 'Send' }));
}

const csv = () => new File(['Date,Description,Amount\n'], 'statement.csv', { type: 'text/csv' });

describe('SendStatement (Gate 2b spec §7)', () => {
  beforeEach(() => {
    send.mockReset();
  });

  it('sendsCodeAndFile', async () => {
    send.mockResolvedValue({ received: true });
    const user = userEvent.setup();
    renderPage();
    const file = csv();

    await fill(user, 'ab3-k7m', file);

    expect(send).toHaveBeenCalledWith('AB3K7M', file);
  });

  it('showsSentOnSuccess', async () => {
    send.mockResolvedValue({ received: true });
    const user = userEvent.setup();
    renderPage();

    await fill(user, 'AB3K7M', csv());

    expect(await screen.findByText('Sent. Open Fynora on your phone to review it.')).toBeInTheDocument();
  });

  it('showsPasswordMessageForLockedPdf', async () => {
    send.mockRejectedValue(axiosError(422, 'IMPORT_008', 'This PDF is password-protected.'));
    const user = userEvent.setup();
    renderPage();

    await fill(user, 'AB3K7M', new File(['%PDF-1.7'], 'statement.pdf', { type: 'application/pdf' }));

    expect(await screen.findByText(
      'This file has a password. Send it to your phone and share it into Fynora instead.')).toBeInTheDocument();
  });

  it('showsServerMessageForBadCode', async () => {
    const message = "That code didn't work. Check it on your phone, or ask for a new one.";
    send.mockRejectedValue(axiosError(400, 'IMPORT_024', message));
    const user = userEvent.setup();
    renderPage();

    await fill(user, 'AB3K7M', csv());

    expect(await screen.findByText(message)).toBeInTheDocument();
    // The code and file stay, so a mistyped character can be fixed without starting over.
    expect(screen.getByLabelText('Code from your phone')).toHaveValue('AB3K7M');
  });

  it('showsNoAccountData', async () => {
    send.mockResolvedValue({ received: true });
    const user = userEvent.setup();
    const { container } = renderPage();

    await fill(user, 'AB3K7M', csv());
    await screen.findByText('Sent. Open Fynora on your phone to review it.');

    // Nothing on the page can show an account: it never asks the server for one and has no
    // signed-in session. Pinned on the rendered text: no email, no "signed in as", no sign-out.
    expect(container.textContent).not.toMatch(/@|signed in|sign out|log out/i);
  });

  it('waits for a six-character code and a file before it can send', async () => {
    const user = userEvent.setup();
    renderPage();
    const button = screen.getByRole('button', { name: 'Send' });
    expect(button).toBeDisabled();

    await user.type(screen.getByLabelText('Code from your phone'), 'AB3K7');
    await user.upload(screen.getByLabelText(/statement file/i), csv());
    expect(button).toBeDisabled();

    await user.type(screen.getByLabelText('Code from your phone'), 'M');
    expect(button).toBeEnabled();
  });

  it('says so when the server cannot be reached', async () => {
    send.mockRejectedValue(new Error('Network Error'));
    const user = userEvent.setup();
    renderPage();

    await fill(user, 'AB3K7M', csv());

    await waitFor(() => expect(screen.getByRole('alert')).toHaveTextContent(/try again/i));
  });
});
