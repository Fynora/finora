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
  await shareFileAndCleanUp(new File(uri), {
    mimeType: 'application/pdf', UTI: 'com.adobe.pdf', dialogTitle: advancedPdfName(report),
  });
}
