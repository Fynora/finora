import { useRef, useState } from 'react';
import { useQuery } from '@tanstack/react-query';
import { X } from 'lucide-react';
import { peopleApi } from '../../api/endpoints';
import { Button, useDialogA11y } from '../../design-system';
import { useInvalidateMoneyFigures } from '../../lib/invalidateMoneyFigures';
import { dayMonth, rupees, serverMessage } from '../../lib/people';
import type { Transaction } from '../../types';

interface Props {
  transaction: Transaction;
  onClose: () => void;
  /** A credit: pick the bill it is a share of. The Ledger closes this window first. */
  onShareOfABill: () => void;
  /** A bill: pick whose money was a share of it. */
  onSplit: () => void;
  /** The person's page, by their key. */
  onOpenPerson: (personKey: string) => void;
}

/** Whether a row has anything to say about money with people: any plain expense (a bill can be split),
 *  or plain money from a person. A transfer, refund or card payment is none of these. */
export function qualifiesForPeople(t: Transaction): boolean {
  if (t.reconciliationStatus !== 'OK') return false;
  return t.type === 'EXPENSE' || (t.type === 'INCOME' && t.counterpartyType === 'PERSON');
}

/**
 * A Ledger row's money with people (spec §7, "Bill") -- the web form of the app's details section
 * (mobile/src/components/people/PeopleTransactionSection.tsx): a bill shows what was paid, the
 * user's share and each share with Undo; any expense can be split; a payment to a person can be
 * marked lent; money from a person can be a share of a bill, paid back or borrowed.
 */
export function PeopleModal({ transaction: t, onClose, onShareOfABill, onSplit, onOpenPerson }: Props) {
  const refreshFigures = useInvalidateMoneyFigures();
  const [saved, setSaved] = useState<string | null>(null);
  const [failed, setFailed] = useState<string | null>(null);
  const [busy, setBusy] = useState(false);
  const inFlight = useRef(false);
  const panelRef = useDialogA11y({ onClose, closeDisabled: busy });

  const isBill = t.type === 'EXPENSE';
  const fromPerson = t.type === 'INCOME' && t.counterpartyType === 'PERSON';
  // An older server has no such endpoint: the shares are then simply not shown.
  const sharesQ = useQuery({ queryKey: ['people', 'bill', t.id], queryFn: () => peopleApi.billShares(t.id), enabled: isBill, retry: false });
  // A payee key that does not name one person has no page: the call fails and the link stays away.
  const personQ = useQuery({
    queryKey: ['people', 'of', t.id], queryFn: () => peopleApi.personOf(t.id), enabled: t.counterpartyType === 'PERSON', retry: false,
  });

  async function save(write: () => Promise<unknown>, done: string | null) {
    if (inFlight.current) return;
    inFlight.current = true;
    setBusy(true);
    setFailed(null);
    setSaved(null);
    try {
      await write();
      refreshFigures();
      setSaved(done);
    } catch (err) {
      setFailed(serverMessage(err, "Couldn't save that. Try again."));
    } finally {
      inFlight.current = false;
      setBusy(false);
    }
  }

  const shares = sharesQ.data ?? [];
  const cameBack = shares.reduce((sum, s) => sum + s.amount, 0);
  const amount = Math.abs(t.amount);
  const personKey = personQ.data?.personKey;

  type Go = { then: () => void } | { write: () => Promise<unknown>; done: string };
  const actions: { label: string; go: Go }[] = [
    ...(isBill ? [{ label: 'Split this', go: { then: onSplit } }] : []),
    ...(isBill && t.counterpartyType === 'PERSON'
      ? [{ label: 'Lent', go: { write: () => peopleApi.addEntry(t.id, 'LENT'), done: 'Saved as lent.' } }]
      : []),
    ...(fromPerson ? [
      { label: 'Share of a bill', go: { then: onShareOfABill } },
      { label: 'Paid back', go: { write: () => peopleApi.addEntry(t.id, 'PAID_BACK'), done: 'Saved as paid back.' } },
      { label: 'Borrowed', go: { write: () => peopleApi.addEntry(t.id, 'BORROWED'), done: 'Saved as borrowed.' } },
    ] : []),
    ...(personQ.data ? [{ label: `See ${personQ.data.label}`, go: { then: () => onOpenPerson(personKey as string) } }] : []),
  ];

  return (
    <>
      <div className="fixed inset-0 bg-black/40 z-30" onClick={busy ? undefined : onClose} />
      <div className="fixed inset-0 z-40 flex items-center justify-center p-4 pointer-events-none">
        <div
          ref={panelRef}
          role="dialog"
          aria-modal="true"
          aria-labelledby="people-title"
          tabIndex={-1}
          className="bg-card border border-border rounded-xl2 shadow-soft w-full max-w-md p-5 pointer-events-auto"
        >
          <div className="flex items-center justify-between mb-1">
            <h3 id="people-title" className="font-semibold text-ink text-sm">With people</h3>
            <button type="button" onClick={onClose} disabled={busy} aria-label="Close" className="text-muted hover:text-ink">
              <X size={18} />
            </button>
          </div>
          <p className="text-xs text-muted mb-3">
            {t.merchant || t.description} · {t.type === 'INCOME' ? '+' : '-'}{rupees(amount)} · {dayMonth(t.date)}
          </p>

          {isBill && shares.length > 0 && (
            <div className="border border-border rounded-lg p-3 mb-3 space-y-1">
              <p className="text-sm font-semibold text-ink">{`Your share ${rupees(Math.max(0, amount - cameBack))}`}</p>
              <p className="text-xs text-muted">{`Paid ${rupees(amount)} · ${rupees(cameBack)} came back`}</p>
              <ul>
                {shares.map((s) => (
                  <li key={s.creditId} className="flex items-center gap-3 text-sm py-1">
                    <span className="flex-1 min-w-0 truncate text-ink">{`${s.label} · ${dayMonth(s.date)}`}</span>
                    <span className="font-medium text-success">{`+${rupees(s.amount)}`}</span>
                    {s.fromRule ? (
                      // A rule's share is undone by removing the rule, on the person's page.
                      <span className="text-2xs text-muted">Every month</span>
                    ) : (
                      <button
                        type="button"
                        disabled={busy}
                        onClick={() => void save(() => peopleApi.unlink(s.creditId, t.id), null)}
                        aria-label={`Undo the share from ${s.label}`}
                        className="text-xs font-medium text-primary hover:underline disabled:opacity-50"
                      >
                        Undo
                      </button>
                    )}
                  </li>
                ))}
              </ul>
            </div>
          )}

          <div className="flex flex-wrap gap-2">
            {actions.map(({ label, go }) => (
              <Button
                key={label}
                size="sm"
                variant="secondary"
                disabled={busy}
                onClick={() => ('then' in go ? go.then() : void save(go.write, go.done))}
              >
                {label}
              </Button>
            ))}
          </div>
          {saved && <p role="status" className="text-success text-xs mt-2">{saved}</p>}
          {failed && <p role="alert" className="text-danger text-xs mt-2">{failed}</p>}
        </div>
      </div>
    </>
  );
}
