import { useId, useState } from 'react';
import { Link } from 'react-router-dom';
import { ChevronDown } from 'lucide-react';

/**
 * "What happens to my statement?" -- Gate 2b, spec §5.2 (approved copy, verbatim). Closed by default,
 * beside the file picker. Every line is a claim about production, checked against the code before it
 * shipped:
 *   1. read by Fynora's own rules, not AI services: the import path (Privacy, "Uploaded Statements").
 *   2. the original is kept, downloadable and re-readable: R2 statement storage (STATEMENT_STORAGE_PROVIDER
 *      is r2 in production) and Statement History's download and re-import.
 *   3. nothing is saved before confirm: rows are staged in an import session and written only on
 *      confirm (ImportSessionService.claimForConfirmation).
 *   4. staff open an original only with a written reason, audited, and the customer is told:
 *      OriginalStatementViewService (AdminOriginalViewIT covers both held paths).
 *   5. deleted file gone 90 days later: StatementStorageSweepService, retention-days 90 from deleted_at.
 *   6. a PDF password is saved only with consent, encrypted: StatementPasswordService.
 * The mobile twin (mobile/src/components/StatementHandlingPanel.tsx) carries the same lines.
 */
const LINES: { lead: string; rest: string }[] = [
  { lead: 'Fynora reads it with its own rules.', rest: 'It is not sent to AI services.' },
  {
    lead: 'The original file is kept in your account,',
    rest: 'so you can download it or have it read again later.',
  },
  { lead: 'You check every row before anything is saved.', rest: 'If you cancel, nothing is added.' },
  {
    lead: "If it can't be read automatically,",
    rest: 'our team may open it to fix the problem. Every opening needs a written reason, is logged, and you are told.',
  },
  {
    lead: 'You can delete a statement any time',
    rest: 'from Statement History. Its file is deleted from our storage 90 days later.',
  },
  { lead: 'A PDF password is saved only if you choose,', rest: 'and is stored encrypted.' },
];

export default function StatementHandlingPanel() {
  const [open, setOpen] = useState(false);
  const listId = useId();

  return (
    <div className="bg-card rounded shadow-card border border-border p-4">
      <button
        type="button"
        aria-expanded={open}
        aria-controls={listId}
        onClick={() => setOpen((o) => !o)}
        className="w-full flex items-center justify-between gap-2 text-sm font-medium text-ink"
      >
        What happens to my statement?
        <ChevronDown size={16} aria-hidden="true" className={`text-muted transition-transform ${open ? 'rotate-180' : ''}`} />
      </button>
      {open && (
        <ul id={listId} data-testid="statement-handling-list" className="mt-3 space-y-2 text-sm text-muted list-disc pl-5">
          {LINES.map((line) => (
            <li key={line.lead}>
              <strong className="text-ink">{line.lead}</strong> {line.rest}
            </li>
          ))}
          <li className="list-none -ml-5">
            <Link to="/privacy#uploaded-statements" className="text-primary hover:underline">
              Read the full privacy policy
            </Link>
          </li>
        </ul>
      )}
    </div>
  );
}
