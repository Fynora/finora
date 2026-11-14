import { useState } from 'react';
import { Link } from 'react-router-dom';
import { ChevronDown } from 'lucide-react';
import type { QuickSortQuestion } from '../types';
import { formatDate } from '../utils/date';
import { patternLine, seeAllHref } from '../lib/payeePattern';
import { trackNavigation } from '../lib/trackNavigation';

function rupees(n: number) {
  return '₹' + Math.round(Math.abs(n)).toLocaleString('en-IN');
}

/**
 * The pattern line under a Quick sort payee, and the payments it opens (spec
 * docs/superpowers/specs/2026-11-14-smarter-person-question-design.md). Renders nothing when the question
 * carries no pattern, so callers keep their own fallback line. Key it on the question so a new question
 * starts closed.
 */
export function QuickSortPattern({ question }: { question: QuickSortQuestion }) {
  const [open, setOpen] = useState(false);
  const line = patternLine(question);
  if (!line) return null;
  const recent = question.recent ?? [];
  const more = (question.allPayments ?? 0) > recent.length;
  return (
    <div className="text-xs text-muted">
      <button type="button" aria-expanded={open} onClick={() => setOpen((v) => !v)}
        className="inline-flex items-center gap-1 text-left underline decoration-dotted underline-offset-2">
        {line}
        <ChevronDown size={12} aria-hidden="true" className={open ? 'rotate-180' : undefined} />
      </button>
      {/* What one answer files: the waiting payments, which the line above no longer counts. */}
      <p>{question.payments} waiting · {rupees(question.total)}</p>
      {open && (
        <div className="mt-1 space-y-1">
          <ul className="space-y-0.5">
            {recent.map((s) => (
              <li key={s.id}>{formatDate(s.date) || s.date} · {rupees(s.amount)} · {s.description}</li>
            ))}
          </ul>
          {more && (
            <Link to={seeAllHref(question.payee)} onClick={() => trackNavigation('transactions', 'contextual')}
              className="inline-block font-medium text-primary underline">
              See all {question.allPayments} in Transactions
            </Link>
          )}
        </div>
      )}
    </div>
  );
}
