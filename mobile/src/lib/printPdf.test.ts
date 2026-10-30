import * as Print from 'expo-print';
import { PRINT_TIMEOUT_MS, printToPdfFile } from './printPdf';

jest.mock('expo-print', () => ({ printToFileAsync: jest.fn() }));

const printToFileAsync = Print.printToFileAsync as jest.Mock;
const OPTIONS = { html: '<p>report</p>' };

beforeEach(() => {
  jest.useFakeTimers();
  printToFileAsync.mockReset();
});

afterEach(() => {
  jest.useRealTimers();
});

describe('printToPdfFile', () => {
  it('returns the printed file\'s uri and leaves no timer behind', async () => {
    printToFileAsync.mockResolvedValue({ uri: 'file:///cache/Print/abc.pdf', numberOfPages: 1 });

    await expect(printToPdfFile(OPTIONS)).resolves.toBe('file:///cache/Print/abc.pdf');
    expect(printToFileAsync).toHaveBeenCalledWith(OPTIONS);
    expect(jest.getTimerCount()).toBe(0);
  });

  it('passes a failed render on as it is', async () => {
    printToFileAsync.mockRejectedValue(new Error('PdfWriteException'));

    await expect(printToPdfFile(OPTIONS)).rejects.toThrow('PdfWriteException');
    expect(jest.getTimerCount()).toBe(0);
  });

  it('gives up on a render that never finishes once the time limit passes', async () => {
    printToFileAsync.mockReturnValue(new Promise(() => {}));
    let outcome: unknown = 'pending';
    const promise = printToPdfFile(OPTIONS).catch((e: unknown) => { outcome = e; });

    jest.advanceTimersByTime(PRINT_TIMEOUT_MS - 1);
    await Promise.resolve();
    expect(outcome).toBe('pending');

    jest.advanceTimersByTime(1);
    await promise;
    expect(outcome).toEqual(new Error('Making the PDF took too long.'));
  });
});
