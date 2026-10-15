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

  it('names a difference in whether account details were found at all', async () => {
    vi.spyOn(download, 'downloadBlob').mockImplementation(() => {});
    render(<BlankedCopyPanel fetchCopy={() => Promise.resolve(copy({
      check: 'DIFFERS',
      differences: [{ section: 0, row: null, field: 'DETECTED_ACCOUNT', maskedTokens: [] }],
    }))} />);
    await userEvent.click(screen.getByRole('button', { name: 'Download blanked copy' }));
    expect(await screen.findByText('Account details')).toBeInTheDocument();
    expect(screen.queryByText('Statement start date')).not.toBeInTheDocument();
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
      check: 'NOT_CHECKED', notCheckedReason: 'The copy could not be read back (IllegalStateException).',
    }))} />);
    await userEvent.click(screen.getByRole('button', { name: 'Download blanked copy' }));
    expect(await screen.findByText('Not checked: The copy could not be read back (IllegalStateException).')).toBeInTheDocument();
  });

  it('shows the server refusal message', async () => {
    const spy = vi.spyOn(download, 'downloadBlob').mockImplementation(() => {});
    render(<BlankedCopyPanel fetchCopy={() => Promise.reject({ response: { data: {
      message: 'The blanked copy was refused: it still contained phone number.' } } })} />);
    await userEvent.click(screen.getByRole('button', { name: 'Download blanked copy' }));
    expect(await screen.findByText('The blanked copy was refused: it still contained phone number.')).toBeInTheDocument();
    expect(spy).not.toHaveBeenCalled();
  });

  it('lists unusual rows in plain words, numbered as rows are elsewhere', async () => {
    vi.spyOn(download, 'downloadBlob').mockImplementation(() => {});
    render(<BlankedCopyPanel fetchCopy={() => Promise.resolve(copy({
      unusualRows: [{ section: 0, row: 11, kind: 'MANY_LINES', lines: 6, pages: 1 },
                    { section: 0, row: 19, kind: 'TWO_PAGES', lines: 3, pages: 2 },
                    { section: 0, row: 30, kind: 'FAR_LINE', lines: 3, pages: 1 },
                    { section: 1, row: 3, kind: 'MANY_LINES', lines: 5, pages: 1 }],
    }))} />);
    await userEvent.click(screen.getByRole('button', { name: 'Download blanked copy' }));
    expect(await screen.findByText('Matches the real statement')).toBeInTheDocument();
    const list = screen.getByRole('list', { name: 'Unusual rows' });
    expect(list).toHaveTextContent('Row 12 took 6 printed lines.');
    expect(screen.getByText('Row 12 took 6 printed lines.')).toBeInTheDocument();
    expect(screen.getByText('Row 20 took lines from 2 pages.')).toBeInTheDocument();
    expect(screen.getByText('Row 31 took a line far from the rest.')).toBeInTheDocument();
    expect(screen.getByText('Account 2, Row 4 took 5 printed lines.')).toBeInTheDocument();
  });

  it('says nothing about unusual rows when there are none', async () => {
    vi.spyOn(download, 'downloadBlob').mockImplementation(() => {});
    render(<BlankedCopyPanel fetchCopy={() => Promise.resolve(copy({ unusualRows: [] }))} />);
    await userEvent.click(screen.getByRole('button', { name: 'Download blanked copy' }));
    expect(await screen.findByText('Matches the real statement')).toBeInTheDocument();
    expect(screen.queryByText('Unusual rows')).not.toBeInTheDocument();
    expect(screen.queryByRole('list', { name: 'Unusual rows' })).not.toBeInTheDocument();
  });

  it('names a row-lines difference in plain words', async () => {
    vi.spyOn(download, 'downloadBlob').mockImplementation(() => {});
    render(<BlankedCopyPanel fetchCopy={() => Promise.resolve(copy({
      check: 'DIFFERS',
      differences: [{ section: 0, row: 11, field: 'ROW_LINES', maskedTokens: [] }],
    }))} />);
    await userEvent.click(screen.getByRole('button', { name: 'Download blanked copy' }));
    expect(await screen.findByText('Row 12: the copy used different printed lines')).toBeInTheDocument();
  });
});
