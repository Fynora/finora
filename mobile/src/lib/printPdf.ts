import * as Print from 'expo-print';

/**
 * How long turning a report's HTML into a PDF may take before the export gives up. A full Advanced
 * Reports period takes a few seconds on a slow phone; this is far past that, and only stops a render
 * that will never finish from holding the export button on "Preparing…" for good.
 */
export const PRINT_TIMEOUT_MS = 30_000;

/**
 * expo-print's printToFileAsync, with a time limit. On Android it renders the page in a WebView that
 * is never put on screen and settles only when that WebView reports the page loaded and written; if
 * that never happens the promise never settles, and nothing else would release the export.
 *
 * A render that finishes after the limit still leaves its file in the cache's Print/ folder;
 * fileCacheSweep clears that folder.
 */
export async function printToPdfFile(options: Print.FilePrintOptions): Promise<string> {
  let timer: ReturnType<typeof setTimeout> | undefined;
  const timeout = new Promise<never>((_, reject) => {
    timer = setTimeout(() => reject(new Error('Making the PDF took too long.')), PRINT_TIMEOUT_MS);
  });
  try {
    const { uri } = await Promise.race([Print.printToFileAsync(options), timeout]);
    return uri;
  } finally {
    clearTimeout(timer);
  }
}
