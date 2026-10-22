import { createPortal } from 'react-dom';
import type { AdvancedReport } from '../../api/endpoints';
import { ADVANCED_PRINT_CSS, advancedPrintBody } from './advancedPrint';

/**
 * The printable report, mounted only while the print dialog is open. The page's tabs and charts are
 * not printed: the sheet holds every section of the period, the same page mobile turns into a PDF.
 * It is a direct child of <body> (a portal) because index.css's print rules hide every other child
 * of <body>, which is the whole app shell, and hide this sheet on screen.
 *
 * The markup comes from advancedPrint.ts as a string, so it is injected rather than rendered. Every
 * value in it is escaped there (names are user-controlled; advancedPrint.test.ts pins it), and the
 * site's Content-Security-Policy allows no inline script or event handler as a second line.
 */
export function PrintSheet({ report }: { report: AdvancedReport }) {
  return createPortal(
    <div
      className="fy-ar-print-root"
      dangerouslySetInnerHTML={{ __html: `<style>${ADVANCED_PRINT_CSS}</style>${advancedPrintBody(report)}` }}
    />,
    document.body,
  );
}
