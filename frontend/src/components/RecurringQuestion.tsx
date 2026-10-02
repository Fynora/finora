import { useState } from 'react';
import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query';
import { categoriesApi, recurringApi, type RecurringQuestionState } from '../api/endpoints';
import { useInvalidateMoneyFigures } from '../lib/invalidateMoneyFigures';
import { CategoryCombobox } from './CategoryCombobox';

/** What a repeating payment usually is, in the order the chips show (spec decision 2). */
export const SHORT_LIST = ['Rent', 'Loan EMI', 'Subscriptions', 'Education', 'Insurance', 'Utilities', 'Investments'] as const;

export interface RecurringQuestionProps {
  merchant: string;
  state: RecurringQuestionState;
  answer: string | null;
  /** The average for NEEDS_ANSWER, the latest payment otherwise. */
  amount: number;
  /** RecurringDto.label ("Monthly", "Weekly", ...). */
  label?: string;
  /** Called after an answer is saved, for a page that holds the recurring list outside React Query. */
  onSaved?: () => void;
}

function rupees(n: number) {
  return '₹' + Math.round(n).toLocaleString('en-IN');
}

/**
 * The recurring-payment question on a recurring row (docs/superpowers/specs/2026-10-02-recurring-
 * payment-answer-design.md §4): ask once what a repeating "Other" payment is, show the answer with
 * a way to change it, and re-ask when the amount moves out of the saved range. A chip appears only
 * for a category the user still has, so a chip never brings back a deleted category.
 */
export function RecurringQuestion({ merchant, state, answer, amount, label = 'Monthly', onSaved }: RecurringQuestionProps) {
  const queryClient = useQueryClient();
  const invalidateMoneyFigures = useInvalidateMoneyFigures();
  const [changing, setChanging] = useState(false);
  const [other, setOther] = useState(false);
  const [otherPick, setOtherPick] = useState('');
  const categoriesQ = useQuery({ queryKey: ['categories'], queryFn: () => categoriesApi.list(), retry: false });
  const save = useMutation({
    mutationFn: (category: string) => recurringApi.categorize(merchant, category),
    onSuccess: () => {
      setChanging(false);
      setOther(false);
      void queryClient.invalidateQueries({ queryKey: ['recurring'] });
      void queryClient.invalidateQueries({ queryKey: ['recurring-changed-amounts'] });
      invalidateMoneyFigures();
      onSaved?.();
    },
  });

  if (state === 'NONE') return null;

  const owned = new Set((categoriesQ.data ?? []).map((c) => c.name.toLowerCase()));
  const chips = SHORT_LIST.filter((name) => owned.has(name.toLowerCase()));
  const asking = state === 'NEEDS_ANSWER' || changing;

  if (!asking && state === 'ANSWERED') {
    return (
      <div className="flex items-center gap-2 text-xs text-muted mt-1">
        <span className="text-ink">{answer}</span>
        <span aria-hidden="true">·</span>
        <button type="button" className="underline hover:text-ink" onClick={() => setChanging(true)}>Change</button>
      </div>
    );
  }

  if (!asking && state === 'AMOUNT_CHANGED') {
    return (
      <div className="flex flex-wrap items-center gap-2 text-xs mt-1">
        <span className="text-ink">{`${rupees(amount)} to ${merchant} — still ${answer}?`}</span>
        <button
          type="button"
          disabled={save.isPending || !answer}
          onClick={() => answer && save.mutate(answer)}
          className="px-2 py-0.5 rounded-full border border-border hover:bg-primary-light disabled:opacity-50"
        >
          Yes
        </button>
        <button type="button" className="underline text-muted hover:text-ink" onClick={() => setChanging(true)}>Change</button>
        {save.isError && <span className="text-danger">Couldn't save — try again.</span>}
      </div>
    );
  }

  return (
    <div className="mt-1 space-y-1.5">
      <p className="text-xs text-ink">{`What is this ${rupees(amount)} ${label.toLowerCase()} payment?`}</p>
      <div className="flex flex-wrap gap-1.5">
        {chips.map((name) => (
          <button
            key={name}
            type="button"
            disabled={save.isPending}
            onClick={() => save.mutate(name)}
            className="px-2.5 py-1 text-xs rounded-full border border-border hover:bg-primary-light disabled:opacity-50"
          >
            {name}
          </button>
        ))}
        <button
          type="button"
          disabled={save.isPending}
          onClick={() => setOther(true)}
          className="px-2.5 py-1 text-xs rounded-full border border-dashed border-border hover:bg-primary-light disabled:opacity-50"
        >
          Something else
        </button>
        {changing && (
          <button
            type="button"
            disabled={save.isPending}
            onClick={() => { setChanging(false); setOther(false); }}
            className="px-2 py-1 text-xs underline text-muted hover:text-ink disabled:opacity-50"
          >
            Cancel
          </button>
        )}
      </div>
      {other && (
        <div className="flex items-center gap-2">
          <div className="flex-1 min-w-0">
            <CategoryCombobox value={otherPick} onChange={setOtherPick} />
          </div>
          <button
            type="button"
            disabled={save.isPending || !otherPick.trim()}
            onClick={() => save.mutate(otherPick.trim())}
            className="px-2.5 py-1 text-xs rounded-lg bg-primary text-white disabled:opacity-50"
          >
            Save
          </button>
        </div>
      )}
      {save.isError && <p className="text-xs text-danger">Couldn't save — try again.</p>}
    </div>
  );
}
