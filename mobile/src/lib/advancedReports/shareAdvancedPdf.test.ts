import * as Print from 'expo-print';
import { shareFileAndCleanUp } from '../shareFile';
import { shareAdvancedPdf } from './shareAdvancedPdf';
import { fixtureReport } from './testFixtures';

/** A stand-in for expo-file-system's File: a path, whether it exists, rename and delete. */
const mockFs = { existing: new Set<string>(), renameFails: false };
jest.mock('expo-file-system', () => {
  class MockFile {
    uri: string;
    constructor(dirOrUri: string | { uri: string }, name?: string) {
      this.uri = name === undefined ? String(dirOrUri) : `${typeof dirOrUri === 'string' ? dirOrUri : dirOrUri.uri}${name}`;
    }
    get exists() { return mockFs.existing.has(this.uri); }
    get parentDirectory() { return { uri: this.uri.slice(0, this.uri.lastIndexOf('/') + 1) }; }
    delete() { mockFs.existing.delete(this.uri); }
    rename(name: string) {
      if (mockFs.renameFails) throw new Error('rename refused');
      const next = `${this.uri.slice(0, this.uri.lastIndexOf('/') + 1)}${name}`;
      if (mockFs.existing.has(next)) throw new Error('destination exists');
      mockFs.existing.delete(this.uri);
      mockFs.existing.add(next);
      this.uri = next;
    }
  }
  return { File: MockFile };
});
jest.mock('expo-print', () => ({ printToFileAsync: jest.fn() }));
jest.mock('../shareFile', () => ({ shareFileAndCleanUp: jest.fn() }));

const printToFileAsync = Print.printToFileAsync as jest.Mock;
const share = shareFileAndCleanUp as jest.Mock;
const PRINTED = 'file:///cache/Print/abc.pdf';
const NAMED = 'file:///cache/Print/fynora-advanced-report-2026-04-to-2026-09.pdf';
const OPTIONS = { mimeType: 'application/pdf', UTI: 'com.adobe.pdf', dialogTitle: 'fynora-advanced-report-2026-04-to-2026-09.pdf' };

describe('shareAdvancedPdf', () => {
  beforeEach(() => {
    mockFs.existing = new Set([PRINTED]);
    mockFs.renameFails = false;
    printToFileAsync.mockReset().mockResolvedValue({ uri: PRINTED });
    share.mockReset().mockResolvedValue(undefined);
  });

  it('prints the whole report and shares it under the report\'s own file name', async () => {
    await shareAdvancedPdf(fixtureReport());

    // A4 at expo-print's 72 PPI, not its US Letter default.
    expect(printToFileAsync.mock.calls[0][0]).toMatchObject({ width: 595, height: 842 });
    // iOS takes its page margins from here (Android from the page's @page rule): 16/18/14 mm at 72 PPI.
    expect(printToFileAsync.mock.calls[0][0]).toMatchObject({ margins: { top: 45, bottom: 51, left: 40, right: 40 } });
    const html = printToFileAsync.mock.calls[0][0].html as string;
    expect(html.startsWith('<!DOCTYPE html>')).toBe(true);
    expect(html).toContain('Apr 2026 – Sep 2026');
    // "Save to Files" keeps the file's own name, so the file carries the readable one, not expo-print's.
    expect(share).toHaveBeenCalledWith(expect.objectContaining({ uri: NAMED }), OPTIONS);
  });

  it('clears a file of the same name left behind by an earlier export, then names this one', async () => {
    mockFs.existing.add(NAMED);
    await shareAdvancedPdf(fixtureReport());
    expect(share).toHaveBeenCalledWith(expect.objectContaining({ uri: NAMED }), OPTIONS);
  });

  it('still shares the PDF, under expo-print\'s name, when it cannot be renamed', async () => {
    mockFs.renameFails = true;
    await shareAdvancedPdf(fixtureReport());
    expect(share).toHaveBeenCalledWith(expect.objectContaining({ uri: PRINTED }), OPTIONS);
  });

  it('shares nothing when the PDF could not be made', async () => {
    printToFileAsync.mockRejectedValue(new Error('print failed'));
    await expect(shareAdvancedPdf(fixtureReport())).rejects.toThrow('print failed');
    expect(share).not.toHaveBeenCalled();
  });
});
