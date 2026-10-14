import { render, screen, waitFor } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { afterEach, describe, expect, it, vi } from 'vitest';
import BlankedCopyPanel from './BlankedCopyPanel';
import * as download from '../lib/download';

const copy = (over = {}) => ({
  fileName: 'blanked-HLD-1.trace', trace: '# finora-pdf-trace v3\n', check: 'MATCHES' as const,
  notCheckedReason: null, differences: [], ...over,
});

describe('BlankedCopyPanel', () => {
  afterEach(() => {
    vi.restoreAllMocks();
  });

  it('downloads the copy and says it matches', async () => {
    const spy = vi.spyOn(download, 'downloadBlob').mockImplementation(() => {});
    render(<BlankedCopyPanel fetchCopy={() => Promise.resolve(copy())} />);
    await userEvent.click(screen.getByRole('button', { name: 'Download blanked copy' }));
    await waitFor(() => expect(spy).toHaveBeenCalledWith(expect.any(Blob), 'blanked-HLD-1.trace'));
    expect(screen.getByText('Matches the real statement')).toBeInTheDocument();
  });

  it('lists differing rows and fields, with masked words only', async () => {
    vi.spyOn(download, 'downloadBlob').mockImplementation(() => {});
    render(<BlankedCopyPanel fetchCopy={() => Promise.resolve(copy({
      check: 'DIFFERS',
      differences: [{ section: 0, row: 12, field: 'DESCRIPTION', maskedTokens: ['Xxxxxxxxx'] },
                    { section: 0, row: null, field: 'ROW_COUNT', maskedTokens: [] }],
    }))} />);
    await userEvent.click(screen.getByRole('button', { name: 'Download blanked copy' }));
    expect(await screen.findByText('Does not match the real statement')).toBeInTheDocument();
    expect(screen.getByText('Row 13: description (hidden words: Xxxxxxxxx)')).toBeInTheDocument();
    expect(screen.getByText('Number of rows')).toBeInTheDocument();
  });

  it('names the account for a difference past the first one, so equal row numbers do not collide', async () => {
    vi.spyOn(download, 'downloadBlob').mockImplementation(() => {});
    render(<BlankedCopyPanel fetchCopy={() => Promise.resolve(copy({
      check: 'DIFFERS',
      differences: [{ section: 0, row: 3, field: 'AMOUNT', maskedTokens: [] },
                    { section: 1, row: 3, field: 'AMOUNT', maskedTokens: [] },
                    { section: 1, row: null, field: 'CLOSING_BALANCE', maskedTokens: [] }],
    }))} />);
    await userEvent.click(screen.getByRole('button', { name: 'Download blanked copy' }));
    expect(await screen.findByText('Row 4: amount')).toBeInTheDocument();
    expect(screen.getByText('Account 2, Row 4: amount')).toBeInTheDocument();
    expect(screen.getByText('Account 2, Closing balance')).toBeInTheDocument();
  });

  it('clears an earlier verdict when a later attempt is refused', async () => {
    vi.spyOn(download, 'downloadBlob').mockImplementation(() => {});
    const fetchCopy = vi.fn()
      .mockResolvedValueOnce(copy())
      .mockRejectedValueOnce({ response: { data: { message: 'Blanked copy not available for CSV yet.' } } });
    render(<BlankedCopyPanel fetchCopy={fetchCopy} />);
    await userEvent.click(screen.getByRole('button', { name: 'Download blanked copy' }));
    expect(await screen.findByText('Matches the real statement')).toBeInTheDocument();

    await userEvent.click(screen.getByRole('button', { name: 'Download blanked copy' }));
    expect(await screen.findByText('Blanked copy not available for CSV yet.')).toBeInTheDocument();
    expect(screen.queryByText('Matches the real statement')).not.toBeInTheDocument();
  });

  /** The copy was made and its making audited; only the browser's save failed. Saying the copy
   *  could not be made would be untrue. */
  it('says the copy was made but not saved when the browser cannot save it', async () => {
    vi.spyOn(download, 'downloadBlob').mockImplementation(() => { throw new Error('blocked'); });
    render(<BlankedCopyPanel fetchCopy={() => Promise.resolve(copy())} />);
    await userEvent.click(screen.getByRole('button', { name: 'Download blanked copy' }));
    expect(await screen.findByRole('alert')).toHaveTextContent(
      'The blanked copy was made, but your browser could not save it. Try again.');
    expect(screen.queryByText('The blanked copy could not be made.')).not.toBeInTheDocument();
    // The check did run on the copy, so its verdict still stands.
    expect(screen.getByText('Matches the real statement')).toBeInTheDocument();
  });

  it('falls back to a plain message when the server gives none', async () => {
    render(<BlankedCopyPanel fetchCopy={() => Promise.reject(new Error('Network Error'))} />);
    await userEvent.click(screen.getByRole('button', { name: 'Download blanked copy' }));
    expect(await screen.findByRole('alert')).toHaveTextContent('The blanked copy could not be made.');
  });

  it('says not checked with the reason', async () => {
    vi.spyOn(download, 'downloadBlob').mockImplementation(() => {});
    render(<BlankedCopyPanel fetchCopy={() => Promise.resolve(copy({
      check: 'NOT_CHECKED', notCheckedReason: 'The reading could not be repeated (IllegalStateException).',
    }))} />);
    await userEvent.click(screen.getByRole('button', { name: 'Download blanked copy' }));
    expect(await screen.findByText(/Not checked: The reading could not be repeated/)).toBeInTheDocument();
  });

  it('shows the server refusal message', async () => {
    const spy = vi.spyOn(download, 'downloadBlob').mockImplementation(() => {});
    render(<BlankedCopyPanel fetchCopy={() => Promise.reject({ response: { data: {
      message: 'The blanked copy was refused: it still contained phone number.' } } })} />);
    await userEvent.click(screen.getByRole('button', { name: 'Download blanked copy' }));
    expect(await screen.findByText('The blanked copy was refused: it still contained phone number.')).toBeInTheDocument();
    expect(spy).not.toHaveBeenCalled();
  });
});
