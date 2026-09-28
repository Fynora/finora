import { useState, type ReactNode } from 'react';
import { X, ChevronDown, ChevronRight } from 'lucide-react';
import type { RefreshRunDetail, RefreshRowView } from '../../api/endpoints';
import { useDialogA11y } from '../../design-system';
import { formatDateDDMMMYYYY } from '../../utils/date';
import { describeChange, fieldValue, inr, outcomeLabel, period } from './refreshFormat';

/**
 * Statement refresh, step 5: what updating the user's statements changed, statement by statement --
 * shown right after the update, since the changes are applied without a review step first.
 */
export function RefreshSummaryDialog({ results, onClose }: { results: RefreshRunDetail[]; onClose: () => void }) {
  const panelRef = useDialogA11y<HTMLDivElement>({ onClose });
  const updated = results.filter((r) => r.status === 'APPLIED').length;

  return (
    <>
      <div className="fixed inset-0 bg-black/40 z-30" onClick={onClose} />
      <div className="fixed inset-0 z-40 flex items-center justify-center p-4 pointer-events-none">
        <div
          ref={panelRef}
          role="dialog"
          aria-modal="true"
          aria-labelledby="refresh-summary-title"
          tabIndex={-1}
          data-testid="refresh-summary"
          className="bg-card border border-border rounded-xl2 shadow-soft w-full max-w-2xl max-h-[85vh] overflow-y-auto p-5 pointer-events-auto space-y-4"
        >
          <div className="flex items-start justify-between gap-3">
            <div>
              <h3 id="refresh-summary-title" className="font-semibold text-ink">What changed</h3>
              <p className="text-xs text-muted mt-0.5">
                {updated === 0
                  ? 'None of your statements needed changes.'
                  : `${updated} ${updated === 1 ? 'statement was' : 'statements were'} updated. Your own edits, categories and notes were kept.`}
              </p>
            </div>
            <button type="button" onClick={onClose} aria-label="Close" className="text-muted hover:text-ink shrink-0">
              <X size={18} />
            </button>
          </div>
          <ul className="space-y-3">
            {results.map((r) => <ResultCard key={r.runId ?? r.statementImportId} result={r} />)}
          </ul>
        </div>
      </div>
    </>
  );
}

function ResultCard({ result: r }: { result: RefreshRunDetail }) {
  const [open, setOpen] = useState(r.status === 'APPLIED');
  const hasDetail = r.changed.length + r.added.length + r.removed.length + r.skippedAsDuplicate.length + r.facts.length > 0;
  const heading = [r.accountName, period(r.periodStart, r.periodEnd)].filter(Boolean).join(' · ');

  return (
    <li className="border border-border rounded-lg p-3" data-testid="refresh-result">
      <button
        type="button"
        className="w-full flex items-start justify-between gap-3 text-left"
        onClick={() => hasDetail && setOpen((o) => !o)}
        aria-expanded={hasDetail ? open : undefined}
        disabled={!hasDetail}
      >
        <div className="min-w-0">
          <p className="text-sm font-medium text-ink break-all">{r.fileName ?? 'Statement'}</p>
          {heading && <p className="text-2xs text-muted">{heading}</p>}
          <p className={`text-xs mt-1 ${r.status === 'APPLIED' || r.status === 'NO_CHANGES' ? 'text-ink' : 'text-warning'}`}>
            {outcomeLabel(r)}
            {r.status === 'APPLIED' && counts(r)}
          </p>
          {r.balanceChange !== null && r.balanceChange !== undefined && Number(r.balanceChange) !== 0 && (
            <p className="text-xs text-muted">Account balance changed by {inr(r.balanceChange)}</p>
          )}
        </div>
        {hasDetail && (open ? <ChevronDown size={16} className="text-muted shrink-0" /> : <ChevronRight size={16} className="text-muted shrink-0" />)}
      </button>

      {open && hasDetail && (
        <div className="mt-3 space-y-3 text-xs">
          {r.changed.length > 0 && (
            <Section title="Corrected">
              {r.changed.map((c, i) => (
                <li key={c.transactionId ?? i}>
                  <RowLine row={c} />
                  <ul className="ml-3 text-muted">
                    {c.changes.map((f, j) => <li key={j}>{describeChange(f)}</li>)}
                  </ul>
                </li>
              ))}
            </Section>
          )}
          {r.added.length > 0 && (
            <Section title="Added — rows we had missed">
              {r.added.map((a, i) => <li key={a.transactionId ?? i}><RowLine row={a} /></li>)}
            </Section>
          )}
          {r.removed.length > 0 && (
            <Section title="Removed — not on the statement">
              {r.removed.map((m, i) => (
                <li key={m.transactionId ?? i}>
                  <RowLine row={m} />
                  {m.userEdited && <span className="text-warning"> — you had edited this row</span>}
                </li>
              ))}
            </Section>
          )}
          {r.skippedAsDuplicate.length > 0 && (
            <Section title="Not added — already imported from another statement">
              {r.skippedAsDuplicate.map((s, i) => <li key={i}><RowLine row={s} /></li>)}
            </Section>
          )}
          {r.facts.length > 0 && (
            <Section title="Statement details">
              {r.facts.map((f, i) => <li key={i}>{describeChange(f)}</li>)}
            </Section>
          )}
        </div>
      )}
    </li>
  );
}

function counts(r: RefreshRunDetail): string {
  const parts = [
    r.rowsChanged && `${r.rowsChanged} corrected`,
    r.rowsAdded && `${r.rowsAdded} added`,
    r.rowsRemoved && `${r.rowsRemoved} removed`,
    r.factsChanged && `${r.factsChanged} statement ${r.factsChanged === 1 ? 'detail' : 'details'}`,
  ].filter(Boolean);
  return parts.length ? ` — ${parts.join(', ')}` : '';
}

function Section({ title, children }: { title: string; children: ReactNode }) {
  return (
    <div>
      <p className="font-medium text-ink mb-1">{title}</p>
      <ul className="space-y-1">{children}</ul>
    </div>
  );
}

function RowLine({ row }: { row: RefreshRowView }) {
  return (
    <span className="text-ink">
      {row.date ? formatDateDDMMMYYYY(row.date) : '—'} · {row.description ?? '—'} · {fieldValue('AMOUNT', row.amount)}
    </span>
  );
}
