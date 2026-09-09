import type { Transaction } from '../types';

/**
 * Mobile equivalent of the web's reconciliationBadge (frontend/src/pages/Ledger.tsx). OK is the
 * status of the overwhelming majority of ordinary transactions and gets no badge -- everything
 * else gets a short label plus a one-line hint naming what the match means. There's no hover on a
 * phone to carry that hint as a tooltip, so the caller's own accessibilityLabel carries it instead
 * -- a sighted user reads the pill, a screen-reader user hears the same explanation the pill would
 * otherwise only show on hover.
 *
 * Extracted out of LedgerScreen.tsx (Phase 4) so TransactionExplanationModal can reuse the same
 * label/tone mapping for its own reconciliation section without importing LedgerScreen.tsx itself,
 * which would create a cycle: LedgerScreen renders the modal, the modal would import LedgerScreen.
 */
export function reconciliationBadge(
  status: Transaction['reconciliationStatus']
): { label: string; hint: string; tone: 'danger' | 'primary' | 'success' | 'warning' | 'muted' } | null {
  switch (status) {
    case 'OK':
      return null;
    case 'DUPLICATE':
      return { label: 'Duplicate', hint: 'Matched as a repeat of another transaction', tone: 'danger' };
    case 'TRANSFER':
      return { label: 'Transfer', hint: 'Matched as money moving between your own accounts', tone: 'primary' };
    case 'REFUND':
      return { label: 'Refund', hint: 'Matched as a refund of an earlier purchase', tone: 'success' };
    case 'REVERSAL':
      return { label: 'Reversed', hint: 'Matched as a reversal of an earlier purchase', tone: 'warning' };
    case 'INVESTMENT_TRANSFER':
      return { label: 'Investment', hint: 'Excluded from spend as an investment transfer', tone: 'primary' };
    case 'SUPERSEDED':
      return { label: 'Superseded', hint: 'From a statement re-upload that replaced this period', tone: 'muted' };
  }
}
