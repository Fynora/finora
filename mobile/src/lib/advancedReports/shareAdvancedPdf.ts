import { File } from 'expo-file-system';
import * as Print from 'expo-print';
import type { AdvancedReport } from '../../api/endpoints';
import { shareFileAndCleanUp } from '../shareFile';
import { advancedPdfName, advancedPrintDocument } from './advancedPrint';

/** Renders the period's printable page to a PDF in the cache and hands it to the system share sheet
 *  (save to Files, mail, print). The file is deleted afterwards whether or not the share went through,
 *  the same route Reports' sharePdf takes. */
export async function shareAdvancedPdf(report: AdvancedReport): Promise<void> {
  const { uri } = await Print.printToFileAsync({ html: advancedPrintDocument(report) });
  const name = advancedPdfName(report);
  const file = new File(uri);
  giveName(file, name);
  await shareFileAndCleanUp(file, { mimeType: 'application/pdf', UTI: 'com.adobe.pdf', dialogTitle: name });
}

/**
 * expo-print names its file at random, and "Save to Files" / a mail attachment keeps the file's own
 * name: the share sheet's title is not used for it. So the file is renamed in place (rename updates
 * `file.uri`), after clearing one of the same name an earlier export left behind (rename refuses an
 * existing name on both platforms). Best effort: if it still cannot be renamed, the PDF is shared under
 * expo-print's name rather than not shared at all.
 */
function giveName(file: File, name: string): void {
  try {
    const leftover = new File(file.parentDirectory, name);
    if (leftover.exists) leftover.delete();
    file.rename(name);
  } catch {
    // Keep expo-print's name; the export itself still works.
  }
}
