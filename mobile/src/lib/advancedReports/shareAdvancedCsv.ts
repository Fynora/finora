import { File, Paths } from 'expo-file-system';
import type { AdvancedReport } from '../../api/endpoints';
import { csvCell } from '../reportExport';
import { shareFileAndCleanUp } from '../shareFile';
import { advancedCsvRows, advancedExportName } from './advancedExport';

/** The period as CSV text, every cell escaped by the same csvCell Reports uses (quotes doubled, and
 *  a cell that starts like a formula is guarded: category and merchant names are user-controlled). */
export function advancedCsv(report: AdvancedReport): string {
  return advancedCsvRows(report).map((row) => row.map((cell) => csvCell(String(cell))).join(',')).join('\n');
}

/** Writes the CSV to the cache and hands it to the system share sheet, which is where "save to Files"
 *  and "mail it to myself" live; a file in the app's own sandbox would be invisible to the user. */
export async function shareAdvancedCsv(report: AdvancedReport): Promise<void> {
  const name = advancedExportName(report);
  const file = new File(Paths.cache, name);
  // A previous export of the same period leaves the file behind, and create() won't overwrite.
  if (file.exists) file.delete();
  file.create();
  file.write(advancedCsv(report));
  await shareFileAndCleanUp(file, {
    mimeType: 'text/csv', UTI: 'public.comma-separated-values-text', dialogTitle: name,
  });
}
