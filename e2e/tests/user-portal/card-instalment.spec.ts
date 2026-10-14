import { test, expect } from '../../fixtures/test';
import type { Row } from '../../fixtures/statements';

/**
 * A card instalment row repeats part of an earlier purchase, and spending totals count both when
 * that purchase was imported too. The row is marked so a total that looks too high has an
 * explanation the user can find. This walks the whole seam: a real import, the flag the server
 * derives from the row's own words, and the ledger reading it.
 *
 * All narrations and amounts are invented.
 */
const ROWS: Row[] = [
  { date: '2026-06-03', description: 'SAMPLE STORE PURCHASE 4471', amount: 1200.0, type: 'DEBIT' },
  { date: '2026-06-05', description: 'FP EMI 02/06(EXCL TAX 49.40)', amount: 4000.0, type: 'DEBIT' },
  { date: '2026-06-05', description: 'INTEREST ON EMI', amount: 300.0, type: 'DEBIT' },
];

const NOTE = /a monthly instalment on an earlier card purchase/i;

test.describe('Card instalment rows', () => {
  test('an imported instalment is marked in the ledger, and the mark says what it means', async ({ api, userPage }) => {
    await api.importStatement(ROWS, { accountName: 'E2E Card' });

    await userPage.goto('/app/transactions');

    // One mark, on the instalment row only: its interest is new cost, and a purchase is a purchase.
    const marks = userPage.getByRole('button', { name: 'EMI instalment' });
    await expect(marks).toHaveCount(1);
    const instalmentRow = userPage.getByRole('row').filter({ has: marks });
    await expect(instalmentRow).toContainText('FP EMI 02/06');
    await expect(marks).toHaveAttribute('title', NOTE);

    await marks.click();

    const panel = userPage.getByRole('dialog');
    await expect(panel.getByText(NOTE)).toBeVisible();
  });
});
