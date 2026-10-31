import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import { render, screen, waitFor } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { MemoryRouter } from 'react-router-dom';
import StatementConverter from './StatementConverter';
import { converterApi } from '../api/endpoints';
import { downloadBlob } from '../lib/download';
import { CONVERTER_FAQ, CONVERTER_TITLE, TESTED_BANKS } from './converter/converterContent';
import type { ConvertedStatement } from './converter/statementSheets';

vi.mock('../api/endpoints', () => ({
  converterApi: { availability: vi.fn(), convert: vi.fn() },
}));

vi.mock('../lib/download', async (importOriginal) => ({
  ...(await importOriginal<typeof import('../lib/download')>()),
  downloadBlob: vi.fn(),
}));

// Turnstile is Cloudflare's script; here it is a widget that has already passed.
const turnstileReset = vi.fn();
vi.mock('./converter/useTurnstile', () => ({
  useTurnstile: () => ({ container: { current: null }, token: 'turnstile-token', state: 'ready', reset: turnstileReset }),
}));

const availability = vi.mocked(converterApi.availability);
const convert = vi.mocked(converterApi.convert);

// Synthetic values only.
const RESULT: ConvertedStatement = {
  pageCount: 2,
  sections: [{
    bankName: 'Example Bank', accountType: 'SAVINGS', accountNumberMasked: 'XX1234',
    periodStart: '2026-09-01', periodEnd: '2026-09-30', openingBalance: 1000, closingBalance: 1500,
    checks: ['BALANCE_CHAIN'],
    rows: [
      { date: '2026-09-03', description: 'UPI/GENERIC PAYEE', debit: 250, credit: null, balance: 750, reference: null },
      { date: '2026-09-10', description: 'NEFT/GENERIC SENDER', debit: null, credit: 750, balance: 1500, reference: null },
    ],
  }],
};

const pdf = () => new File(['%PDF-1.4 synthetic'], 'statement.pdf', { type: 'application/pdf' });

function axiosError(status: number, errorCode: string, message: string) {
  return Object.assign(new Error(message), { response: { status, data: { errorCode, message } } });
}

function renderPage() {
  return render(
    <MemoryRouter initialEntries={['/bank-statement-to-excel']}>
      <StatementConverter />
    </MemoryRouter>,
  );
}

async function chooseAndConvert(user: ReturnType<typeof userEvent.setup>, file: File) {
  await user.click(screen.getByRole('checkbox'));
  await user.upload(screen.getByLabelText(/statement pdf/i), file);
  await user.click(screen.getByRole('button', { name: /convert to excel or csv/i }));
}

describe('StatementConverter', () => {
  beforeEach(() => {
    vi.stubEnv('VITE_TURNSTILE_SITE_KEY', 'site-key');
    availability.mockReset().mockResolvedValue({ available: true, maxBytes: 5 * 1024 * 1024, maxPages: 20 });
    convert.mockReset();
    turnstileReset.mockReset();
    vi.mocked(downloadBlob).mockReset();
  });

  afterEach(() => {
    vi.unstubAllEnvs();
  });

  it('answers first: the heading, the intro, the tested banks, and every FAQ question', () => {
    renderPage();
    expect(screen.getByRole('heading', { level: 1, name: CONVERTER_TITLE })).toBeInTheDocument();
    for (const bank of TESTED_BANKS) expect(screen.getByText(bank)).toBeInTheDocument();
    for (const [question] of CONVERTER_FAQ) expect(screen.getByText(question)).toBeInTheDocument();
    expect(screen.getByText(/not affiliated with/i)).toBeInTheDocument();
  });

  it('reads nothing until the consent box is ticked', async () => {
    const user = userEvent.setup();
    renderPage();
    await waitFor(() => expect(availability).toHaveBeenCalled());

    expect(screen.getByRole('checkbox')).not.toBeChecked();
    expect(screen.getByLabelText(/statement pdf/i)).toBeDisabled();
    await user.click(screen.getByRole('checkbox'));
    expect(screen.getByLabelText(/statement pdf/i)).toBeEnabled();
  });

  it('converts: sends the file as base64 with consent and the Turnstile token, then offers both downloads', async () => {
    convert.mockResolvedValue(RESULT);
    const user = userEvent.setup();
    renderPage();
    await waitFor(() => expect(availability).toHaveBeenCalled());

    await chooseAndConvert(user, pdf());

    await screen.findByText('Your file is ready');
    expect(convert).toHaveBeenCalledWith({
      file: btoa('%PDF-1.4 synthetic'),
      turnstileToken: 'turnstile-token',
      consent: true,
    });
    expect(screen.getByText('UPI/GENERIC PAYEE')).toBeInTheDocument();
    expect(screen.getByText(/Descriptions are copied as they were read/)).toBeInTheDocument();
    expect(turnstileReset).toHaveBeenCalled();

    await user.click(screen.getByRole('button', { name: /download csv/i }));
    await user.click(screen.getByRole('button', { name: /download excel/i }));
    const names = vi.mocked(downloadBlob).mock.calls.map((call) => call[1]);
    expect(names).toEqual([
      'statement-2026-09-01-to-2026-09-30.csv',
      'statement-2026-09-01-to-2026-09-30.xlsx',
    ]);
  });

  it('asks for the password of a locked statement, then sends it with the same file', async () => {
    convert
      .mockRejectedValueOnce(axiosError(422, 'IMPORT_008', 'locked'))
      .mockResolvedValueOnce(RESULT);
    const user = userEvent.setup();
    renderPage();
    await waitFor(() => expect(availability).toHaveBeenCalled());

    await chooseAndConvert(user, pdf());
    await screen.findByText(/This statement is locked/);
    await user.type(screen.getByLabelText(/pdf password/i), 'synthetic-pw');
    await user.click(screen.getByRole('button', { name: /convert to excel or csv/i }));

    await screen.findByText('Your file is ready');
    expect(convert).toHaveBeenLastCalledWith(expect.objectContaining({ password: 'synthetic-pw' }));
  });

  it('shows the server’s reason when it refuses, never a file', async () => {
    convert.mockRejectedValue(axiosError(422, 'CONVERT_008', 'We couldn’t confirm this statement’s amounts against its own totals.'));
    const user = userEvent.setup();
    renderPage();
    await waitFor(() => expect(availability).toHaveBeenCalled());

    await chooseAndConvert(user, pdf());

    expect(await screen.findByRole('alert')).toHaveTextContent('against its own totals');
    expect(screen.queryByText('Your file is ready')).not.toBeInTheDocument();
  });

  it('refuses a file over 5 MB or not a PDF before sending anything', async () => {
    const user = userEvent.setup({ applyAccept: false });
    renderPage();
    await waitFor(() => expect(availability).toHaveBeenCalled());
    await user.click(screen.getByRole('checkbox'));

    const big = pdf();
    Object.defineProperty(big, 'size', { value: 5 * 1024 * 1024 + 1 });
    await user.upload(screen.getByLabelText(/statement pdf/i), big);
    expect(screen.getByRole('alert')).toHaveTextContent('over 5 MB');

    await user.upload(screen.getByLabelText(/statement pdf/i), new File(['a,b'], 'statement.csv', { type: 'text/csv' }));
    expect(screen.getByRole('alert')).toHaveTextContent('PDF files only');
    expect(screen.getByRole('button', { name: /convert to excel or csv/i })).toBeDisabled();
    expect(convert).not.toHaveBeenCalled();
  });

  it('says the converter is unavailable when the server has it switched off', async () => {
    availability.mockResolvedValue({ available: false, maxBytes: 0, maxPages: 0 });
    renderPage();

    expect(await screen.findByText(/isn’t available right now/)).toBeInTheDocument();
    expect(screen.getByRole('checkbox')).toBeDisabled();
  });

  it('says the converter is unavailable with no Turnstile site key, without asking the server', async () => {
    vi.stubEnv('VITE_TURNSTILE_SITE_KEY', '');
    renderPage();

    expect(await screen.findByText(/isn’t available right now/)).toBeInTheDocument();
    expect(availability).not.toHaveBeenCalled();
  });
});
