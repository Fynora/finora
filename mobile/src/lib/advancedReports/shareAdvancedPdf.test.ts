import * as Print from 'expo-print';
import { shareFileAndCleanUp } from '../shareFile';
import { shareAdvancedPdf } from './shareAdvancedPdf';
import { fixtureReport } from './testFixtures';

jest.mock('expo-print', () => ({ printToFileAsync: jest.fn() }));
jest.mock('expo-file-system', () => ({ File: jest.fn().mockImplementation((uri: string) => ({ uri })) }));
jest.mock('../shareFile', () => ({ shareFileAndCleanUp: jest.fn() }));

const printToFileAsync = Print.printToFileAsync as jest.Mock;
const share = shareFileAndCleanUp as jest.Mock;

describe('shareAdvancedPdf', () => {
  beforeEach(() => {
    printToFileAsync.mockReset().mockResolvedValue({ uri: 'file:///cache/Print/abc.pdf' });
    share.mockReset().mockResolvedValue(undefined);
  });

  it('prints the whole report and hands the PDF to the share sheet', async () => {
    await shareAdvancedPdf(fixtureReport());

    const html = printToFileAsync.mock.calls[0][0].html as string;
    expect(html.startsWith('<!DOCTYPE html>')).toBe(true);
    expect(html).toContain('Apr 2026 – Sep 2026');
    expect(share).toHaveBeenCalledWith(
      { uri: 'file:///cache/Print/abc.pdf' },
      { mimeType: 'application/pdf', UTI: 'com.adobe.pdf', dialogTitle: 'fynora-advanced-report-2026-04-to-2026-09.pdf' },
    );
  });

  it('shares nothing when the PDF could not be made', async () => {
    printToFileAsync.mockRejectedValue(new Error('print failed'));
    await expect(shareAdvancedPdf(fixtureReport())).rejects.toThrow('print failed');
    expect(share).not.toHaveBeenCalled();
  });
});
