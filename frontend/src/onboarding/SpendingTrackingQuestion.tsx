import { useState } from 'react';
import { Button } from '../design-system';

/** The answers, in the order shown. Keys are the backend's SpendingTrackingMethod names. */
export const SPENDING_TRACKING_OPTIONS: { key: string; label: string }[] = [
  { key: 'NOT_TRACKED', label: "I don't really track it" },
  { key: 'IN_MY_HEAD', label: 'Roughly, in my head' },
  { key: 'PAPER', label: 'In a notebook or on paper' },
  { key: 'SPREADSHEET', label: 'In a spreadsheet (Excel, Google Sheets)' },
  { key: 'EXPENSE_APP', label: 'With an expense or budgeting app' },
  { key: 'BANK_APP', label: "With my bank's app or statements" },
  { key: 'OTHER', label: 'Something else' },
];

interface Props {
  onSubmit: (method: string) => Promise<void>;
  /** The way out without answering -- like VerifyPhone's, so nobody is trapped on a shared
   *  device. It never lets anyone past the question. */
  onSignOut: () => void;
}

/**
 * The required question "How do you keep track of your spending today?". One answer, and nothing
 * moves on until one is chosen and saved: there is no skip. Shown by SpendingQuestionGate.
 */
export function SpendingTrackingQuestion({ onSubmit, onSignOut }: Props) {
  const [selected, setSelected] = useState<string | null>(null);
  const [saving, setSaving] = useState(false);
  const [error, setError] = useState<string | null>(null);

  async function submit() {
    if (!selected || saving) return;
    setSaving(true);
    setError(null);
    try {
      await onSubmit(selected);
    } catch {
      setError('Something went wrong. Check your connection and try again.');
      setSaving(false);
    }
  }

  return (
    <div className="flex flex-col items-center justify-center min-h-screen px-6 py-10 text-center bg-bg">
      <h1 className="text-2xl font-bold text-ink mb-2">How do you keep track of your spending today?</h1>
      <p className="text-muted mb-6 max-w-md">
        Pick the one closest to what you do now. It helps us build Fynora around how people really manage money.
      </p>
      <div role="radiogroup" aria-label="How you keep track of your spending today" className="flex flex-col gap-3 w-full max-w-md mb-8">
        {SPENDING_TRACKING_OPTIONS.map((opt) => {
          const active = selected === opt.key;
          return (
            <button
              key={opt.key}
              type="button"
              role="radio"
              aria-checked={active}
              onClick={() => setSelected(opt.key)}
              className={`px-4 py-3 rounded-lg border text-sm text-left transition-colors ${
                active ? 'border-primary bg-primary/10 text-ink' : 'border-border text-muted'
              }`}
            >
              {opt.label}
            </button>
          );
        })}
      </div>
      {error && <p role="alert" className="text-danger text-sm mb-4">{error}</p>}
      <Button variant="primary" onClick={() => void submit()} disabled={!selected || saving}>
        {saving ? 'Saving…' : 'Continue'}
      </Button>
      <button type="button" onClick={onSignOut} className="mt-6 text-xs text-muted hover:text-ink font-medium underline">
        Sign out
      </button>
    </div>
  );
}
