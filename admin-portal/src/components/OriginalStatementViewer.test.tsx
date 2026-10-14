import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import { fireEvent, render, screen, within } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import OriginalStatementViewer from './OriginalStatementViewer';
import { adminOriginalViewApi } from '../api/endpoints';

const REASON = 'Rows on page 2 are missing';

/** jsdom has no object URLs; each blob gets its own, so the test can count what is freed. */
let created: string[] = [];
const revoke = vi.fn();

beforeEach(() => {
  created = [];
  revoke.mockReset();
  URL.createObjectURL = vi.fn(() => {
    const url = `blob:page-${created.length + 1}`;
    created.push(url);
    return url;
  });
  URL.revokeObjectURL = revoke;
});

afterEach(() => {
  vi.restoreAllMocks();
});

function httpError(status: number, message?: string) {
  return Object.assign(new Error(message ?? `HTTP ${status}`), {
    response: { status, data: message ? { message } : {} },
  });
}

async function openWithReason() {
  await userEvent.type(screen.getByLabelText('Reason'), REASON);
  await userEvent.click(screen.getByRole('button', { name: 'Open statement' }));
}

describe('OriginalStatementViewer', () => {
  it('asks for a reason first and says who will be told', () => {
    render(<OriginalStatementViewer queue="IMPORT" itemId="job-1" onClose={vi.fn()} />);
    expect(screen.getByText(/Super Admins will be emailed/)).toBeInTheDocument();
    expect(screen.getByText(/The customer will be told the date/)).toBeInTheDocument();
    expect(screen.getByRole('button', { name: 'Open statement' })).toBeDisabled();
  });

  it('keeps the button off until the reason is 15 characters', async () => {
    render(<OriginalStatementViewer queue="IMPORT" itemId="job-1" onClose={vi.fn()} />);
    await userEvent.type(screen.getByLabelText('Reason'), 'too short');
    expect(screen.getByRole('button', { name: 'Open statement' })).toBeDisabled();
    await userEvent.type(screen.getByLabelText('Reason'), ' rows on page 2');
    expect(screen.getByRole('button', { name: 'Open statement' })).toBeEnabled();
  });

  it('counts spaces at the ends as nothing, as the server does', async () => {
    render(<OriginalStatementViewer queue="IMPORT" itemId="job-1" onClose={vi.fn()} />);
    await userEvent.type(screen.getByLabelText('Reason'), '   short words    ');
    expect(screen.getByRole('button', { name: 'Open statement' })).toBeDisabled();
    expect(screen.getByText('11 / 500')).toBeInTheDocument();
  });

  it('shows each page as an image and offers no download', async () => {
    vi.spyOn(adminOriginalViewApi, 'openImport').mockResolvedValue(
      { viewId: 'v1', format: 'PDF', pageCount: 2, expiresAt: '2026-10-15T05:30:00Z' });
    const pageBlob = vi.spyOn(adminOriginalViewApi, 'pageBlob').mockResolvedValue(new Blob(['x'], { type: 'image/png' }));
    render(<OriginalStatementViewer queue="IMPORT" itemId="job-1" onClose={vi.fn()} />);

    await openWithReason();

    expect(adminOriginalViewApi.openImport).toHaveBeenCalledWith('job-1', REASON);
    const pages = await screen.findAllByRole('img', { name: /Page \d of 2/ });
    expect(pages).toHaveLength(2);
    expect(pages[0]).toHaveAttribute('src', 'blob:page-1');
    expect(pages[0]).toHaveAttribute('draggable', 'false');
    expect(pageBlob).toHaveBeenNthCalledWith(1, 'v1', 1);
    expect(pageBlob).toHaveBeenNthCalledWith(2, 'v1', 2);
    expect(screen.getByText(/Nothing here can be downloaded/)).toBeInTheDocument();
    expect(screen.queryByRole('link', { name: /download/i })).toBeNull();
    expect(screen.queryByRole('button', { name: /download/i })).toBeNull();
  });

  it('opens a trust hold through the trust queue', async () => {
    vi.spyOn(adminOriginalViewApi, 'openTrust').mockResolvedValue(
      { viewId: 'v2', format: 'PDF', pageCount: 1, expiresAt: '2026-10-15T05:30:00Z' });
    vi.spyOn(adminOriginalViewApi, 'pageBlob').mockResolvedValue(new Blob(['x'], { type: 'image/png' }));
    const openImport = vi.spyOn(adminOriginalViewApi, 'openImport');
    render(<OriginalStatementViewer queue="TRUST" itemId="HLD-2026-000001" onClose={vi.fn()} />);

    await openWithReason();

    expect(await screen.findByRole('img', { name: 'Page 1 of 1' })).toBeInTheDocument();
    expect(adminOriginalViewApi.openTrust).toHaveBeenCalledWith('HLD-2026-000001', REASON);
    expect(openImport).not.toHaveBeenCalled();
  });

  it('shows a CSV as lines', async () => {
    vi.spyOn(adminOriginalViewApi, 'openImport').mockResolvedValue(
      { viewId: 'v3', format: 'CSV', pageCount: 0, expiresAt: '2026-10-15T05:30:00Z' });
    vi.spyOn(adminOriginalViewApi, 'csv').mockResolvedValue(['a,b', 'c,d']);
    const pageBlob = vi.spyOn(adminOriginalViewApi, 'pageBlob');
    render(<OriginalStatementViewer queue="IMPORT" itemId="job-1" onClose={vi.fn()} />);

    await openWithReason();

    const pre = await screen.findByTestId('original-statement-csv');
    expect(pre.tagName).toBe('PRE');
    expect(within(pre).getByText(/a,b/)).toBeInTheDocument();
    expect(pre.textContent).toBe('a,b\nc,d');
    expect(adminOriginalViewApi.csv).toHaveBeenCalledWith('v3');
    expect(pageBlob).not.toHaveBeenCalled();
    expect(screen.queryByRole('img')).toBeNull();
  });

  it("shows the server's reason when the statement cannot be opened, and stays on the reason step", async () => {
    vi.spyOn(adminOriginalViewApi, 'openImport').mockRejectedValue(
      httpError(409, 'The stored statement file behind held import job-1 could not be read.'));
    render(<OriginalStatementViewer queue="IMPORT" itemId="job-1" onClose={vi.fn()} />);

    await openWithReason();

    expect(await screen.findByText('The stored statement file behind held import job-1 could not be read.'))
      .toBeInTheDocument();
    expect(screen.getByLabelText('Reason')).toHaveValue(REASON);
  });

  it('says the server is busy on a 503', async () => {
    vi.spyOn(adminOriginalViewApi, 'openImport').mockRejectedValue(httpError(503, 'busy reading statements'));
    render(<OriginalStatementViewer queue="IMPORT" itemId="job-1" onClose={vi.fn()} />);

    await openWithReason();

    expect(await screen.findByText('The server is busy. Try again in a minute.')).toBeInTheDocument();
  });

  it('says the view has ended on a 404 and frees the image URLs on close', async () => {
    vi.spyOn(adminOriginalViewApi, 'openImport').mockResolvedValue(
      { viewId: 'v4', format: 'PDF', pageCount: 3, expiresAt: '2026-10-15T05:30:00Z' });
    vi.spyOn(adminOriginalViewApi, 'pageBlob')
      .mockResolvedValueOnce(new Blob(['1'], { type: 'image/png' }))
      .mockResolvedValueOnce(new Blob(['2'], { type: 'image/png' }))
      .mockRejectedValueOnce(httpError(404, 'This view has ended. Open the statement again.'));
    const onClose = vi.fn();
    const { unmount } = render(<OriginalStatementViewer queue="IMPORT" itemId="job-1" onClose={onClose} />);

    await openWithReason();

    expect(await screen.findByText('This view has ended. Open the statement again.')).toBeInTheDocument();
    expect(screen.getAllByRole('img', { name: /Page \d of 3/ })).toHaveLength(2);
    fireEvent.click(screen.getByRole('button', { name: 'Close' }));
    expect(onClose).toHaveBeenCalledTimes(1);
    unmount();
    expect(created).toHaveLength(2);
    expect(revoke).toHaveBeenCalledTimes(2);
    expect(revoke).toHaveBeenCalledWith('blob:page-1');
    expect(revoke).toHaveBeenCalledWith('blob:page-2');
  });

  it('blocks the context menu on the pages', async () => {
    vi.spyOn(adminOriginalViewApi, 'openImport').mockResolvedValue(
      { viewId: 'v5', format: 'PDF', pageCount: 1, expiresAt: '2026-10-15T05:30:00Z' });
    vi.spyOn(adminOriginalViewApi, 'pageBlob').mockResolvedValue(new Blob(['x'], { type: 'image/png' }));
    render(<OriginalStatementViewer queue="IMPORT" itemId="job-1" onClose={vi.fn()} />);

    await openWithReason();

    const page = await screen.findByRole('img', { name: 'Page 1 of 1' });
    const allowed = fireEvent.contextMenu(page);
    expect(allowed).toBe(false);
  });
});
