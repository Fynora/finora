import { useState } from 'react';
import type { ChoiceScope, InflowKind, UnresolvedSender } from '../../api/endpoints';
import { Button, FinoraCard } from '../../design-system';
import { InflowKindPicker } from './InflowKindPicker';

function fmt(n: number) {
  return (n < 0 ? '-₹' : '₹') + Math.round(Math.abs(n)).toLocaleString('en-IN');
}

/**
 * One sender whose payments are not counted as income: tap to open, pick what the money was, then
 * apply it to every payment from them or to one. Shared by Money Review and the import Done step,
 * so the two never ask differently.
 */
export function UnresolvedSenderRow({ sender: s, kinds, open, onToggle, busy, onApply, onCreateKind }: {
  sender: UnresolvedSender;
  kinds: InflowKind[];
  open: boolean;
  onToggle: () => void;
  busy: boolean;
  onApply: (transactionId: string, kindId: string, scope: ChoiceScope, label: string) => void;
  onCreateKind: (name: string, countsAsIncome: boolean) => Promise<InflowKind>;
}) {
  const [picked, setPicked] = useState<InflowKind | null>(null);
  const [oneRow, setOneRow] = useState(false);

  // A row that closes forgets its half-made choice; reset while rendering, not in an effect.
  const [wasOpen, setWasOpen] = useState(open);
  if (open !== wasOpen) {
    setWasOpen(open);
    setPicked(null);
    setOneRow(false);
  }

  return (
    <FinoraCard>
      <button type="button" className="w-full text-left" aria-expanded={open} onClick={onToggle} data-testid="unresolved-sender">
        <div className="flex justify-between gap-2">
          <span className="text-ink text-sm font-medium truncate">{s.label}</span>
          <span className="text-ink text-sm">{fmt(s.total)}</span>
        </div>
        <p className="text-xs text-muted">
          {s.count} {s.count === 1 ? 'payment' : 'payments'} · latest {s.latestDate}{s.accountName ? ` · ${s.accountName}` : ''}
        </p>
      </button>
      {open && (
        <div className="mt-3 space-y-3">
          <InflowKindPicker
            kinds={kinds}
            selectedId={picked?.id ?? null}
            onPick={setPicked}
            busy={busy}
            onCreate={async (name, countsAsIncome) => setPicked(await onCreateKind(name, countsAsIncome))}
          />
          {picked && !oneRow && (
            <div className="flex flex-wrap gap-2">
              {s.senderKnown && (
                <Button size="sm" loading={busy} onClick={() => onApply(s.sampleTransactionId, picked.id, 'SENDER', s.label)}>
                  {`Every payment from ${s.label} (${s.senderPaymentCount})`}
                </Button>
              )}
              <Button size="sm" variant={s.senderKnown ? 'secondary' : 'primary'} disabled={busy}
                onClick={() => (s.rows.length === 1 ? onApply(s.rows[0].id, picked.id, 'ROW', s.label) : setOneRow(true))}>
                Just this one
              </Button>
            </div>
          )}
          {picked && oneRow && (
            <ul className="space-y-1">
              {s.rows.map((r) => (
                <li key={r.id}>
                  <button type="button" disabled={busy}
                    className="w-full flex justify-between text-sm text-ink hover:bg-bg rounded px-2 py-1"
                    onClick={() => onApply(r.id, picked.id, 'ROW', s.label)}>
                    <span>{r.date}</span><span>{fmt(r.amount)}</span>
                  </button>
                </li>
              ))}
            </ul>
          )}
        </div>
      )}
    </FinoraCard>
  );
}
