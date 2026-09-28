import type { RefreshFieldChange, RefreshRunDetail } from '../api/endpoints';
import { fmtDate } from './format';

/** "-₹1,234.50" / "₹1,234.50" -- the sign before the symbol, never "₹-". */
export function inr(value: string | number | null | undefined): string {
  if (value === null || value === undefined || value === '') return '—';
  const n = typeof value === 'number' ? value : Number(value);
  if (Number.isNaN(n)) return String(value);
  const abs = Math.abs(n).toLocaleString('en-IN', { minimumFractionDigits: 2, maximumFractionDigits: 2 });
  return (n < 0 ? '-₹' : '₹') + abs;
}

export function period(start: string | null, end: string | null): string | null {
  return start && end ? `${fmtDate(start)} – ${fmtDate(end)}` : null;
}

const FIELD_LABELS: Record<string, string> = {
  DATE: 'Date',
  DESCRIPTION: 'Description',
  AMOUNT: 'Amount',
  TYPE: 'Debit or credit',
  BALANCE_AFTER: 'Balance after',
  REFERENCE_NUMBER: 'Reference number',
  OPENING_BALANCE: 'Opening balance',
  CLOSING_BALANCE: 'Closing balance',
  STATEMENT_PERIOD_START: 'Statement start',
  STATEMENT_PERIOD_END: 'Statement end',
  TOTAL_AMOUNT_DUE: 'Total amount due',
  PAYMENT_DUE_DATE: 'Payment due date',
};

export function fieldLabel(field: string): string {
  return FIELD_LABELS[field] ?? field.replace(/_/g, ' ').toLowerCase().replace(/^./, (ch) => ch.toUpperCase());
}

const MONEY_FIELDS = new Set(['AMOUNT', 'BALANCE_AFTER', 'OPENING_BALANCE', 'CLOSING_BALANCE', 'TOTAL_AMOUNT_DUE']);
const DATE_FIELDS = new Set(['DATE', 'STATEMENT_PERIOD_START', 'STATEMENT_PERIOD_END', 'PAYMENT_DUE_DATE']);

/** A before/after value as the user reads it: money as rupees, dates as dates, types in words. */
export function fieldValue(field: string, value: string | null): string {
  if (value === null || value === '') return '—';
  if (MONEY_FIELDS.has(field)) return inr(value);
  if (DATE_FIELDS.has(field)) return fmtDate(value) ?? value;
  if (field === 'TYPE') {
    if (value === 'CREDIT' || value === 'INCOME') return 'Credit';
    if (value === 'DEBIT' || value === 'EXPENSE') return 'Debit';
  }
  return value;
}

export function describeChange(ch: RefreshFieldChange): string {
  return `${fieldLabel(ch.field)}: ${fieldValue(ch.field, ch.before)} → ${fieldValue(ch.field, ch.after)}`;
}

/** One line for a result's outcome. */
export function outcomeLabel(r: RefreshRunDetail): string {
  switch (r.status) {
    case 'APPLIED': return 'Updated';
    case 'NO_CHANGES': return 'Already up to date';
    case 'NEEDS_REVIEW':
      return "Not updated — the new reading would remove too many rows, so we left it as it was and we're looking into it";
    case 'NEEDS_PASSWORD': return 'Needs the statement password';
    case 'FAILED':
      return r.reason === 'NO_LONGER_AVAILABLE'
        ? 'Not updated — this statement was deleted or replaced'
        : "Not updated — we couldn't read this statement again; nothing was changed";
    default: return r.status;
  }
}

export function counts(r: RefreshRunDetail): string {
  const parts = [
    r.rowsChanged ? `${r.rowsChanged} corrected` : null,
    r.rowsAdded ? `${r.rowsAdded} added` : null,
    r.rowsRemoved ? `${r.rowsRemoved} removed` : null,
    r.factsChanged ? `${r.factsChanged} statement ${r.factsChanged === 1 ? 'detail' : 'details'}` : null,
  ].filter(Boolean);
  return parts.length ? ` — ${parts.join(', ')}` : '';
}
