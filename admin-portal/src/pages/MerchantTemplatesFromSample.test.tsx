import { describe, it, expect, vi, beforeEach } from 'vitest';
import { render, screen, waitFor, within } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { MemoryRouter } from 'react-router-dom';
import { QueryClient, QueryClientProvider } from '@tanstack/react-query';
import MerchantTemplates from './MerchantTemplates';
import { useAdminAuth } from '../context/AdminAuthContext';
import { mockAdminAuthState } from '../test/mockAdminAuth';
import { adminMerchantTemplatesApi } from '../api/endpoints';
import type { SampleAnalysis } from '../types';

// Same mocks and reasons as MerchantTemplates.test.tsx.
vi.mock('../context/ThemeContext', () => ({
  useTheme: () => ({ theme: 'system', resolvedTheme: 'light', setTheme: vi.fn() }),
}));
vi.mock('../context/AdminAuthContext', () => ({
  useAdminAuth: vi.fn(),
}));
vi.mock('../context/NotificationContext', () => ({
  useNotify: () => ({ success: vi.fn(), error: vi.fn() }),
}));
vi.mock('../api/endpoints', () => ({
  adminMerchantTemplatesApi: {
    list: vi.fn(), create: vi.fn(), update: vi.fn(), activate: vi.fn(), deactivate: vi.fn(), test: vi.fn(),
    analyzeSample: vi.fn(),
  },
}));

function renderPage() {
  const queryClient = new QueryClient({ defaultOptions: { queries: { retry: false } } });
  return render(
    <QueryClientProvider client={queryClient}>
      <MemoryRouter>
        <MerchantTemplates />
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

function analysis(overrides: Partial<SampleAnalysis> = {}): SampleAnalysis {
  return {
    authenticatedDomain: 'instamart.example',
    senderVerdict: 'TRUSTED',
    domainIsTrusted: true,
    handWrittenParserExists: false,
    senderName: 'Instamart',
    receivedOn: '2026-09-02',
    arrivalDatePattern: '{received}',
    html: '<p>Your order is delivered. Total &#8377;1491.00</p>',
    text: 'Your order is delivered. Total ₹1491.00',
    amounts: [
      { pattern: 'Total ₹{amount}', value: '1491.00', context: 'is delivered. Total ₹1491.00', labelled: true, likelyTotal: true },
      { pattern: 'Fee ₹{amount}', value: '12.00', context: 'Handling Fee ₹12.00', labelled: true, likelyTotal: false },
    ],
    dates: [],
    receiptMarkerSuggestions: ['Your order is delivered.', 'Thank you for ordering'],
    problems: ['This email prints no date with a year. Use the day the email arrived (2026-09-02) as the receipt date instead.'],
    ...overrides,
  };
}

function emlFile(content = 'From: x\r\n\r\nbody') {
  return new File([content], 'receipt.eml', { type: 'message/rfc822' });
}

async function openNewTemplateForm() {
  vi.mocked(adminMerchantTemplatesApi.list).mockResolvedValue(
    { content: [], page: 0, size: 20, totalElements: 0, totalPages: 1 });
  renderPage();
  await userEvent.click(await screen.findByRole('button', { name: /New template/ }));
}

async function upload(file: File = emlFile()) {
  await userEvent.upload(screen.getByLabelText('Sample email file'), file);
}

const field = (label: string) => screen.getByLabelText(label) as HTMLInputElement;

describe('MerchantTemplates -- start from a sample email', () => {
  beforeEach(() => {
    vi.mocked(useAdminAuth).mockReset();
    for (const fn of Object.values(adminMerchantTemplatesApi)) vi.mocked(fn).mockReset();
    mockAuth(['MERCHANT_MANAGE', 'SYSTEM_SETTINGS']);
  });

  it('sends the file to the server and fills the form from what comes back', async () => {
    vi.mocked(adminMerchantTemplatesApi.analyzeSample).mockResolvedValue(analysis());
    await openNewTemplateForm();

    await upload(emlFile('From: Shop\r\n\r\nthe whole email'));

    await waitFor(() => expect(adminMerchantTemplatesApi.analyzeSample).toHaveBeenCalledWith('From: Shop\r\n\r\nthe whole email'));
    await waitFor(() => expect(field('Merchant domain')).toHaveValue('instamart.example'));
    expect(field('Merchant name')).toHaveValue('Instamart');
    expect(field('Amount pattern')).toHaveValue('Total ₹{amount}');
    // No date in the email, so the day it arrived is chosen.
    expect(field('Date pattern')).toHaveValue('{received}');
    // The receipt marker is never chosen for the admin.
    expect(field('Receipt marker')).toHaveValue('');
  });

  it('shows each amount with the text around it, and choosing another one changes the pattern', async () => {
    vi.mocked(adminMerchantTemplatesApi.analyzeSample).mockResolvedValue(analysis());
    await openNewTemplateForm();
    await upload();

    expect(await screen.findByText(/Handling Fee/)).toBeInTheDocument();
    expect(screen.getByText('looks like the total')).toBeInTheDocument();

    await userEvent.click(screen.getByRole('radio', { name: /12\.00/ }));

    expect(field('Amount pattern')).toHaveValue('Fee ₹{amount}');
  });

  it('offers the arrival day as a date choice and says why when the email prints no date', async () => {
    vi.mocked(adminMerchantTemplatesApi.analyzeSample).mockResolvedValue(analysis());
    await openNewTemplateForm();
    await upload();

    expect(await screen.findByText(/prints no date with a year/)).toBeInTheDocument();
    expect(screen.getByRole('radio', { name: /The day the email arrived \(2026-09-02\)/ })).toBeChecked();
  });

  it('lets an email that prints a date choose it, over the arrival day', async () => {
    vi.mocked(adminMerchantTemplatesApi.analyzeSample).mockResolvedValue(analysis({
      dates: [{ pattern: 'date {date}', value: 'Aug 01, 2026', context: 'payment date Aug 01, 2026', labelled: true, likelyTotal: false }],
      problems: [],
    }));
    await openNewTemplateForm();
    await upload();

    await waitFor(() => expect(field('Date pattern')).toHaveValue('date {date}'));
    await userEvent.click(screen.getByRole('radio', { name: /The day the email arrived/ }));
    expect(field('Date pattern')).toHaveValue('{received}');
  });

  it('sets the receipt marker only when a suggested phrase is chosen', async () => {
    vi.mocked(adminMerchantTemplatesApi.analyzeSample).mockResolvedValue(analysis());
    await openNewTemplateForm();
    await upload();

    await userEvent.click(await screen.findByRole('button', { name: 'Thank you for ordering' }));

    expect(field('Receipt marker')).toHaveValue('Thank you for ordering');
    expect(screen.getByRole('button', { name: 'Thank you for ordering' })).toHaveAttribute('aria-pressed', 'true');
  });

  it('runs the test against the email it read, with its arrival day, not against anything typed', async () => {
    vi.mocked(adminMerchantTemplatesApi.analyzeSample).mockResolvedValue(analysis());
    vi.mocked(adminMerchantTemplatesApi.test).mockResolvedValue({
      status: 'PARSED', reason: null, amount: 1491, transactionDate: '2026-09-02', confidence: 0.9, violations: [],
    });
    await openNewTemplateForm();
    await upload();
    await userEvent.click(await screen.findByRole('button', { name: 'Your order is delivered.' }));

    await userEvent.click(screen.getByRole('button', { name: 'Test template' }));

    await waitFor(() => expect(adminMerchantTemplatesApi.test).toHaveBeenCalledWith(expect.objectContaining({
      merchantDomain: 'instamart.example',
      receiptMarker: 'Your order is delivered.',
      amountPattern: 'Total ₹{amount}',
      datePattern: '{received}',
      sampleHtml: '<p>Your order is delivered. Total &#8377;1491.00</p>',
      receivedOn: '2026-09-02',
    })));
    expect(await screen.findByText(/Parsed -- amount 1491, date 2026-09-02/)).toBeInTheDocument();
  });

  describe('the sender', () => {
    it('says a trusted domain is trusted', async () => {
      vi.mocked(adminMerchantTemplatesApi.analyzeSample).mockResolvedValue(analysis());
      await openNewTemplateForm();
      await upload();

      expect(await screen.findByText(/-- trusted/)).toBeInTheDocument();
      expect(screen.queryByText(/will not run until the domain is trusted/)).not.toBeInTheDocument();
    });

    it('warns about an untrusted domain and links to Trusted Senders when the admin can use it', async () => {
      vi.mocked(adminMerchantTemplatesApi.analyzeSample).mockResolvedValue(
        analysis({ domainIsTrusted: false, senderVerdict: 'DOMAIN_NOT_TRUSTED' }));
      await openNewTemplateForm();
      await upload();

      expect(await screen.findByText(/not trusted yet/)).toBeInTheDocument();
      expect(screen.getByRole('link', { name: /Trusted Senders page/ })).toHaveAttribute('href', '/trusted-senders');
    });

    it('does not link an admin who cannot open the Trusted Senders page to it', async () => {
      mockAuth(['MERCHANT_MANAGE']);
      vi.mocked(adminMerchantTemplatesApi.analyzeSample).mockResolvedValue(
        analysis({ domainIsTrusted: false, senderVerdict: 'DOMAIN_NOT_TRUSTED' }));
      await openNewTemplateForm();
      await upload();

      expect(await screen.findByText(/Ask an admin with Settings access/)).toBeInTheDocument();
      expect(screen.queryByRole('link', { name: /Trusted Senders/ })).not.toBeInTheDocument();
    });

    it('says up front that a domain with a hand-written parser cannot have a template', async () => {
      vi.mocked(adminMerchantTemplatesApi.analyzeSample).mockResolvedValue(
        analysis({ authenticatedDomain: 'amazon.in', handWrittenParserExists: true }));
      await openNewTemplateForm();
      await upload();

      expect(await screen.findByText(/amazon\.in already has a hand-written parser/)).toBeInTheDocument();
    });

    it('does not show that notice for an ordinary domain', async () => {
      vi.mocked(adminMerchantTemplatesApi.analyzeSample).mockResolvedValue(analysis());
      await openNewTemplateForm();
      await upload();

      await screen.findByText(/-- trusted/);
      expect(screen.queryByText(/already has a hand-written parser/)).not.toBeInTheDocument();
    });

    it('says so when Gmail authenticated the message as no domain, and leaves the domain to be typed', async () => {
      vi.mocked(adminMerchantTemplatesApi.analyzeSample).mockResolvedValue(analysis({
        authenticatedDomain: null, domainIsTrusted: false, senderVerdict: 'NO_AUTHENTICATION_HEADER',
        problems: ['Gmail did not authenticate this message as coming from any domain, so the sender domain could not be read.'],
      }));
      await openNewTemplateForm();
      await userEvent.type(field('Merchant domain'), 'typed.example');
      await upload();

      expect(await screen.findByText(/did not authenticate this email as coming from any domain/)).toBeInTheDocument();
      expect(field('Merchant domain')).toHaveValue('typed.example');
    });
  });

  describe('when the file cannot be used', () => {
    it("shows the server's reason and leaves the form as it was", async () => {
      vi.mocked(adminMerchantTemplatesApi.analyzeSample).mockRejectedValue({
        response: { data: { message: 'No readable message body was found in this file.' } },
      });
      await openNewTemplateForm();
      await userEvent.type(field('Merchant name'), 'Typed name');

      await upload();

      expect(await screen.findByRole('alert')).toHaveTextContent('No readable message body was found in this file.');
      expect(field('Merchant name')).toHaveValue('Typed name');
      expect(field('Amount pattern')).toHaveValue('');
    });

    it('refuses a file over the size limit without sending it', async () => {
      await openNewTemplateForm();

      await upload(emlFile('x'.repeat(5_000_001)));

      expect(await screen.findByRole('alert')).toHaveTextContent(/too large/);
      expect(adminMerchantTemplatesApi.analyzeSample).not.toHaveBeenCalled();
    });

    it('says when the email has no amount, and offers none', async () => {
      vi.mocked(adminMerchantTemplatesApi.analyzeSample).mockResolvedValue(analysis({
        amounts: [], problems: ['No amount was found in the readable text.'],
      }));
      await openNewTemplateForm();
      await upload();

      expect(await screen.findByText('No amount found in this email.')).toBeInTheDocument();
      expect(field('Amount pattern')).toHaveValue('');
    });
  });

  it('chooses no amount for the admin when none of them clearly reads as the total', async () => {
    vi.mocked(adminMerchantTemplatesApi.analyzeSample).mockResolvedValue(analysis({
      amounts: [
        { pattern: 'mins! ₹{amount}', value: '70', context: 'in 32 mins! ₹70', labelled: true, likelyTotal: false },
        { pattern: 'Payment ₹{amount}', value: '522', context: 'Split Payment ₹522', labelled: true, likelyTotal: false },
      ],
    }));
    await openNewTemplateForm();

    await upload();

    expect(await screen.findByText(/None of these clearly reads as the total/)).toBeInTheDocument();
    expect(field('Amount pattern')).toHaveValue('');
    expect(screen.getAllByRole('radio', { name: /70|522/ }).filter((r) => (r as HTMLInputElement).checked)).toHaveLength(0);

    await userEvent.click(screen.getByRole('radio', { name: /522/ }));
    expect(field('Amount pattern')).toHaveValue('Payment ₹{amount}');
    expect(screen.queryByText(/None of these clearly reads as the total/)).not.toBeInTheDocument();
  });

  describe('checking other emails against the template', () => {
    async function fillFromFirstEmail() {
      vi.mocked(adminMerchantTemplatesApi.analyzeSample).mockResolvedValueOnce(analysis());
      await openNewTemplateForm();
      await upload();
      await userEvent.click(await screen.findByRole('button', { name: 'Your order is delivered.' }));
    }

    it('is not available until the marker and both patterns are filled in', async () => {
      await openNewTemplateForm();

      expect(screen.getByLabelText('Another email to check')).toBeDisabled();
    });

    it('shows "Not read" for a shipped email the template does not read', async () => {
      await fillFromFirstEmail();
      vi.mocked(adminMerchantTemplatesApi.analyzeSample).mockResolvedValueOnce(
        analysis({ html: '<p>Your order has shipped</p>', receivedOn: '2026-09-03' }));
      vi.mocked(adminMerchantTemplatesApi.test).mockResolvedValueOnce({
        status: 'NOT_A_RECEIPT', reason: 'receipt marker "Your order is delivered." not found',
        amount: null, transactionDate: null, confidence: null, violations: [],
      });

      await userEvent.upload(screen.getByLabelText('Another email to check'),
        new File(['shipped'], 'shipped.eml', { type: 'message/rfc822' }));

      expect(await screen.findByText(/shipped\.eml: Not read/)).toBeInTheDocument();
      // It was run through the SAME template as the form holds now, on that email's own html and day.
      expect(adminMerchantTemplatesApi.test).toHaveBeenCalledWith(expect.objectContaining({
        receiptMarker: 'Your order is delivered.', amountPattern: 'Total ₹{amount}',
        sampleHtml: '<p>Your order has shipped</p>', receivedOn: '2026-09-03',
      }));
    });

    it('flags an email the template WOULD read, because it would be counted as a purchase', async () => {
      await fillFromFirstEmail();
      vi.mocked(adminMerchantTemplatesApi.analyzeSample).mockResolvedValueOnce(analysis());
      vi.mocked(adminMerchantTemplatesApi.test).mockResolvedValueOnce({
        status: 'PARSED', reason: null, amount: 496, transactionDate: '2026-09-03', confidence: 0.9, violations: [],
      });

      await userEvent.upload(screen.getByLabelText('Another email to check'),
        new File(['refund'], 'refund.eml', { type: 'message/rfc822' }));

      expect(await screen.findByText(/refund\.eml: would be read as 496 on 2026-09-03/)).toBeInTheDocument();
    });

    it('reports a file it could not read without disturbing the form', async () => {
      await fillFromFirstEmail();
      vi.mocked(adminMerchantTemplatesApi.analyzeSample).mockRejectedValueOnce({
        response: { data: { message: 'No readable message body was found in this file.' } },
      });

      await userEvent.upload(screen.getByLabelText('Another email to check'),
        new File(['junk'], 'junk.eml', { type: 'message/rfc822' }));

      expect(await screen.findByText(/junk\.eml: No readable message body/)).toBeInTheDocument();
      expect(field('Amount pattern')).toHaveValue('Total ₹{amount}');
      expect(field('Receipt marker')).toHaveValue('Your order is delivered.');
    });
  });

  it('is offered when creating a template, not when editing one', async () => {
    vi.mocked(adminMerchantTemplatesApi.list).mockResolvedValue({
      content: [{
        id: 't1', merchantDomain: 'uber.com', merchantName: 'Uber', receiptMarker: 'Trip Fare',
        nonReceiptMarker: null, amountPattern: 'Total ₹{amount}', datePattern: 'Trip Date: {date}', enabled: true,
        createdByUserId: null, createdAt: '2026-08-01T00:00:00Z', updatedAt: '2026-08-01T00:00:00Z', domainIsTrusted: true,
      }],
      page: 0, size: 20, totalElements: 1, totalPages: 1,
    });
    renderPage();

    await userEvent.click(await screen.findByTitle('Edit / test'));

    const form = await screen.findByRole('button', { name: 'Save changes' });
    expect(within(form.closest('form') as HTMLElement).queryByText('Start from a sample email')).not.toBeInTheDocument();
  });
});
