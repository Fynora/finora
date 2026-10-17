import type { PaymentMethodAmount } from '../../api/endpoints';

export interface MethodRow {
  /** The server's code, e.g. UPI. */
  method: string;
  /** What the card calls it. */
  label: string;
  amount: number;
  /** Percent of the card's own total (named and unnamed together), 0-100. */
  share: number;
}

export interface MethodBreakdown {
  /** The methods the wording named, largest first. */
  named: MethodRow[];
  /** Spending whose wording names no method, or null when there is none. */
  unidentified: MethodRow | null;
  /** The card's own total: what its shares are shares of. */
  total: number;
  /** Percent of the total that has a named method, 0-100. */
  namedShare: number;
}

/** The share of spending that must have a named method before the card is drawn at all. Below it the
 *  card would be mostly "not identified" and say little; the statement wording simply does not carry
 *  the method for this user's banks. This number is a product choice, not a measurement. */
export const MIN_NAMED_SHARE = 50;

const LABELS: Record<string, string> = {
  UPI: 'UPI',
  CARD: 'Card',
  BANK_TRANSFER: 'Bank transfer',
  CASH: 'Cash',
  CHEQUE: 'Cheque',
  AUTO_DEBIT: 'Auto-debit',
  WALLET: 'Wallet',
  UNIDENTIFIED: 'Not identified',
};

/** "60%", or "<1%" for a share under one percent: a method with real money in it must not read "0%". */
export function formatShare(share: number): string {
  return share < 1 ? '<1%' : `${Math.round(share)}%`;
}

/** A code this client does not know (a newer server): shown as readable words, never the raw code. */
function labelFor(method: string): string {
  const known = LABELS[method];
  if (known) return known;
  const words = method.toLowerCase().split(/[_\s]+/).filter(Boolean).join(' ');
  return words ? words.charAt(0).toUpperCase() + words.slice(1) : 'Other';
}

/**
 * The report's payment methods as rows with their share of the card's own total. A method with no
 * positive amount is dropped: it has no share to show. The shares are of the sum of the rows shown
 * here (like the category donut), so they add up to 100 whatever the report's Expense figure is.
 * Null when there is nothing to show, or when too little of the spending has a named method
 * ({@link MIN_NAMED_SHARE}): a card that is mostly "not identified" is left out rather than drawn.
 * Mirrors frontend/src/pages/reportSections/methodBreakdown.ts.
 */
export function methodBreakdown(methods: PaymentMethodAmount[] | undefined): MethodBreakdown | null {
  const positive = (methods ?? []).filter((m) => Number.isFinite(m.amount) && m.amount > 0);
  const total = positive.reduce((sum, m) => sum + m.amount, 0);
  if (total <= 0) return null;
  const row = (m: PaymentMethodAmount): MethodRow =>
    ({ method: m.method, label: labelFor(m.method), amount: m.amount, share: (m.amount / total) * 100 });
  const named = positive.filter((m) => m.method !== 'UNIDENTIFIED').map(row).sort((a, b) => b.amount - a.amount);
  const unnamedAmount = positive.filter((m) => m.method === 'UNIDENTIFIED').reduce((sum, m) => sum + m.amount, 0);
  const namedShare = ((total - unnamedAmount) / total) * 100;
  if (named.length === 0 || namedShare < MIN_NAMED_SHARE) return null;
  return {
    named,
    unidentified: unnamedAmount > 0 ? row({ method: 'UNIDENTIFIED', amount: unnamedAmount }) : null,
    total,
    namedShare,
  };
}
