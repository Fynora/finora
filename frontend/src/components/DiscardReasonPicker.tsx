import { DISCARD_REASONS, type DiscardReason } from '../lib/discardReasons';

/**
 * "Why? (optional)" -- one tap on a reason inside a discard dialog. Tapping the chosen one again
 * clears it, and nothing has to be chosen: the dialog's Discard works either way.
 */
export function DiscardReasonPicker({
  value,
  onChange,
}: {
  value: DiscardReason | null;
  onChange: (reason: DiscardReason | null) => void;
}) {
  return (
    <div role="group" aria-labelledby="discard-reason-label" className="space-y-2">
      <p id="discard-reason-label" className="text-xs font-medium text-ink">
        Why? <span className="text-muted font-normal">(optional)</span>
      </p>
      <div className="flex flex-wrap gap-1.5">
        {DISCARD_REASONS.map((reason) => {
          const selected = value === reason.value;
          return (
            <button
              key={reason.value}
              type="button"
              aria-pressed={selected}
              onClick={() => onChange(selected ? null : reason.value)}
              className={`text-xs rounded-full border px-2.5 py-1 transition-colors ${
                selected
                  ? 'bg-primary text-on-primary border-primary'
                  : 'bg-card text-ink border-border hover:bg-surface'
              }`}
            >
              {reason.label}
            </button>
          );
        })}
      </div>
    </div>
  );
}
