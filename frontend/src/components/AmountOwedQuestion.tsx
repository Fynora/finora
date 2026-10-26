import { readAmountOwed } from '../lib/amountOwed';

/**
 * "How much do you owe on this card today?" on the import review screen, shown when a card
 * statement gives no closing balance and nothing has stated the card's figure (see
 * lib/amountOwed.ts). Optional: skipping imports the statement and the card shows
 * "Amount owed not known" until the user adds it.
 */
export function AmountOwedQuestion({
  idPrefix,
  value,
  onChange,
  skipped,
  onSkip,
  onUndoSkip,
}: {
  idPrefix: string;
  value: string;
  onChange: (value: string) => void;
  skipped: boolean;
  onSkip: () => void;
  onUndoSkip: () => void;
}) {
  const inputId = `${idPrefix}-amount-owed`;
  const invalid = readAmountOwed(value).state === 'invalid';

  if (skipped) {
    return (
      <div data-testid={`${idPrefix}-amount-owed-skipped`} className="bg-bg border border-border rounded-xl p-4 text-sm text-muted">
        Skipped. Until you add it, the card will show "Amount owed not known" and stays out of your totals.{' '}
        <button type="button" onClick={onUndoSkip} className="text-primary font-medium hover:underline">
          Answer now
        </button>
      </div>
    );
  }

  return (
    <div data-testid={`${idPrefix}-amount-owed`} className="bg-bg border border-border rounded-xl p-4 space-y-2">
      <p className="text-sm font-semibold text-ink">How much do you owe on this card today?</p>
      <p className="text-xs text-muted">
        This statement doesn't show your balance. Your card app or latest bill has it.
      </p>
      <div className="flex items-end gap-3 flex-wrap">
        <div className="flex-1 min-w-[10rem]">
          <label htmlFor={inputId} className="block text-xs uppercase text-muted mb-1">Amount owed today (₹)</label>
          <input
            id={inputId}
            inputMode="decimal"
            value={value}
            onChange={(e) => onChange(e.target.value)}
            aria-invalid={invalid}
            aria-describedby={invalid ? `${inputId}-error` : undefined}
            className="bg-card text-ink border border-border rounded-lg px-3 py-2 text-sm w-full"
          />
        </div>
        <button type="button" onClick={onSkip} className="text-sm text-muted hover:text-ink py-2">
          Skip for now
        </button>
      </div>
      {invalid && (
        <p id={`${inputId}-error`} role="alert" className="text-xs text-danger">
          Enter the amount in rupees, like 12345.67
        </p>
      )}
    </div>
  );
}
