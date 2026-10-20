import { useEffect, useMemo, useRef, useState } from 'react';
import { useQuery, useQueryClient } from '@tanstack/react-query';
import { X } from 'lucide-react';
import {
  insightsApi, peopleApi, transactionsApi, type BillOption, type BillShare, type PeopleSuggestion, type PeopleSuggestions,
} from '../../api/endpoints';
import { Button, useDialogA11y } from '../../design-system';
import { useInvalidateMoneyFigures } from '../../lib/invalidateMoneyFigures';
import { dayMonth, rupees, serverMessage } from '../../lib/people';
import type { Transaction } from '../../types';
import { toLocalDateString } from '../../utils/date';
import { PeopleQuestion } from './PeopleQuestion';

/** The picker's window: two weeks before the payment, then two weeks after (spec §6 A). */
const WINDOW_DAYS = 14;

interface Payment {
  txnId: string;
  label: string;
  amount: number;
  date: string;
}

type Props =
  /** A credit: which bill is it a share of? `options` are the server's, best first; null: look them up. */
  | { mode: 'bill'; credit: Payment; options: BillOption[] | null; onClose: () => void }
  /** A bill ("Split this"): whose money was a share of it? */
  | { mode: 'credit'; bill: Payment & { category: string | null }; onClose: () => void };

interface Option {
  id: string;
  category: string | null;
  payee: string;
  date: string;
  amount: number;
  /** What is left of the bill after its shares; for a credit, its amount. */
  remaining: number;
}

function addDays(date: string, days: number): string {
  const [y, m, d] = date.split('-').map(Number);
  return toLocalDateString(new Date(y, m - 1, d + days));
}

function fromTransaction(t: Transaction): Option {
  const amount = Math.abs(t.amount);
  return { id: t.id, category: t.categoryName || null, payee: t.merchant || t.description, date: t.date, amount, remaining: amount };
}

/** Before the anchor date first, nearest first; then after it, nearest first. */
function nearestFirst(options: Option[], anchor: string): Option[] {
  const at = (d: string) => new Date(`${d}T00:00:00`).getTime();
  const distance = (o: Option) => Math.abs(at(o.date) - at(anchor));
  return [...options].sort((a, b) => (Number(a.date > anchor) - Number(b.date > anchor)) || distance(a) - distance(b));
}

/**
 * Links a credit to a bill as someone's share of it -- the web form of the app's bill picker
 * (mobile/src/components/people/BillPickerSheet.tsx; screens 2 and 3 of the approved share mockup).
 * The first bill is chosen for the user, who still confirms. "Something else" searches every
 * expense, for a share that came back outside the two weeks. Once linked, "every month?" is asked
 * in place when the server now suggests it for this credit.
 */
export function BillPickerModal(props: Props) {
  const queryClient = useQueryClient();
  const refreshFigures = useInvalidateMoneyFigures();
  const anchor = props.mode === 'bill' ? props.credit : props.bill;
  const [searching, setSearching] = useState(false);
  const [keyword, setKeyword] = useState('');
  const [searchFor, setSearchFor] = useState('');
  const [chosenId, setChosenId] = useState<string | null>(null);
  const [linking, setLinking] = useState(false);
  const inFlight = useRef(false);
  const [failed, setFailed] = useState<string | null>(null);
  const [linked, setLinked] = useState<{ share: BillShare; option: Option } | null>(null);
  const [everyMonth, setEveryMonth] = useState<PeopleSuggestion | null>(null);
  const panelRef = useDialogA11y({ onClose: props.onClose, closeDisabled: linking });

  // The search waits for a pause in typing, so each letter is not a request.
  useEffect(() => {
    const timer = setTimeout(() => setSearchFor(keyword.trim()), 300);
    return () => clearTimeout(timer);
  }, [keyword]);

  const given = props.mode === 'bill' ? props.options : null;
  const windowQ = useQuery({
    queryKey: ['people', 'pick', props.mode, anchor.txnId],
    queryFn: () => transactionsApi.search({
      type: props.mode === 'bill' ? 'EXPENSE' : 'INCOME',
      dateFrom: addDays(anchor.date, -WINDOW_DAYS),
      dateTo: addDays(anchor.date, WINDOW_DAYS),
      size: 50,
    }),
    enabled: !searching && given === null,
  });
  const searchQ = useQuery({
    queryKey: ['people', 'pick', 'search', searchFor],
    queryFn: () => transactionsApi.search({ type: 'EXPENSE', keyword: searchFor || undefined, size: 30, sortField: 'date', sortDir: 'desc' }),
    enabled: searching,
  });

  const options: Option[] = useMemo(() => {
    if (searching) return (searchQ.data?.content ?? []).filter((t) => t.id !== anchor.txnId).map(fromTransaction);
    if (given) {
      return given.map((b) => ({ id: b.billId, category: b.category, payee: b.payeeLabel, date: b.date, amount: b.amount, remaining: b.remaining }));
    }
    const rows = (windowQ.data?.content ?? [])
      .filter((t) => t.id !== anchor.txnId)
      // A share comes from a person; a bill can be paid to anyone.
      .filter((t) => props.mode === 'bill' || t.counterpartyType === 'PERSON');
    return nearestFirst(rows.map(fromTransaction), anchor.date);
  }, [searching, searchQ.data, given, windowQ.data, anchor.txnId, anchor.date, props.mode]);

  const chosen = options.find((o) => o.id === chosenId) ?? options[0] ?? null;
  const loading = searching ? searchQ.isLoading : given === null && windowQ.isLoading;

  async function linkChosen() {
    if (!chosen || inFlight.current) return;
    inFlight.current = true;
    setLinking(true);
    setFailed(null);
    const creditId = props.mode === 'bill' ? props.credit.txnId : chosen.id;
    const billId = props.mode === 'bill' ? chosen.id : props.bill.txnId;
    try {
      const share = await peopleApi.link(creditId, billId);
      refreshFigures();
      setLinked({ share, option: chosen });
      // Screen 3: the same sender in other months? The server says, once the link is in.
      const next = await queryClient.fetchQuery<PeopleSuggestions>({
        queryKey: ['people', 'suggestions'], queryFn: () => peopleApi.suggestions(), staleTime: 0,
      }).catch(() => null);
      setEveryMonth(next?.items.find((s) => s.kind === 'EVERY_MONTH' && s.txnId === creditId) ?? null);
    } catch (err) {
      setFailed(serverMessage(err, "Couldn't link it. Try again."));
    } finally {
      inFlight.current = false;
      setLinking(false);
    }
  }

  function doneText(): string {
    if (!linked) return '';
    const bill = props.mode === 'bill'
      ? { date: linked.option.date, name: linked.option.category ?? linked.option.payee, remaining: linked.option.remaining }
      : { date: props.bill.date, name: props.bill.category ?? props.bill.label, remaining: props.bill.amount };
    // What the server's own figure will be: what was left of the bill, less this share.
    return `Your share of the ${dayMonth(bill.date)} ${bill.name} is now ${rupees(Math.max(0, bill.remaining - linked.share.amount))}.`;
  }

  const title = props.mode === 'bill' ? 'Share of which bill?' : 'Who paid a share?';
  const subtitle = props.mode === 'bill'
    ? `${rupees(props.credit.amount)} from ${props.credit.label} · ${dayMonth(props.credit.date)}`
    : `${rupees(props.bill.amount)} to ${props.bill.label} · ${dayMonth(props.bill.date)}`;
  const linkLabel = (o: Option) => (props.mode === 'bill'
    ? `Link to ${dayMonth(o.date)} ${o.category ?? o.payee}`
    : `Link ${dayMonth(o.date)} ${rupees(o.amount)}`);

  return (
    <>
      <div className="fixed inset-0 bg-black/40 z-30" onClick={linking ? undefined : props.onClose} />
      <div className="fixed inset-0 z-40 flex items-center justify-center p-4 pointer-events-none">
        <div
          ref={panelRef}
          role="dialog"
          aria-modal="true"
          aria-labelledby="bill-picker-title"
          tabIndex={-1}
          className="bg-card border border-border rounded-xl2 shadow-soft w-full max-w-md p-5 pointer-events-auto"
        >
          <div className="flex items-center justify-between mb-1">
            <h3 id="bill-picker-title" className="font-semibold text-ink text-sm">{linked ? 'Linked.' : title}</h3>
            <button type="button" onClick={props.onClose} disabled={linking} aria-label="Close" className="text-muted hover:text-ink">
              <X size={18} />
            </button>
          </div>

          {linked ? (
            <div className="space-y-3">
              <p className="text-sm text-ink">{doneText()}</p>
              {everyMonth ? (
                <PeopleQuestion
                  item={everyMonth}
                  today={toLocalDateString(new Date())}
                  paidBackKindId={null}
                  onAck={() => {
                    // The page asks the same question: once the server has the answer, it goes there too.
                    void insightsApi.acknowledge(everyMonth.kind, everyMonth.ackKey)
                      .then(() => {
                        void queryClient.invalidateQueries({ queryKey: ['insight-acknowledgements'] });
                        void queryClient.invalidateQueries({ queryKey: ['people', 'suggestions'] });
                      })
                      .catch(() => {});
                    props.onClose();
                  }}
                  onAnswered={props.onClose}
                  // Not offered on an every-month question; there only to satisfy the other kinds.
                  onPickBill={() => {}}
                  onAsk={() => {}}
                  onOpenPayment={() => {}}
                />
              ) : (
                <Button onClick={props.onClose}>Done</Button>
              )}
            </div>
          ) : (
            <>
              <p className="text-xs text-muted mb-3">{subtitle}</p>
              {searching && (
                <input
                  type="text"
                  value={keyword}
                  onChange={(e) => setKeyword(e.target.value)}
                  placeholder="Search your payments"
                  aria-label="Search your payments"
                  autoFocus
                  className="w-full border border-border rounded-lg px-3 py-2 text-sm mb-3"
                />
              )}
              <div className="max-h-64 overflow-y-auto -mx-1" role="radiogroup" aria-label={title}>
                {options.length === 0 ? (
                  <p className="text-muted text-xs px-1">
                    {loading ? 'Looking…' : props.mode === 'bill' ? 'No payments here.' : 'No money from people in these two weeks.'}
                  </p>
                ) : (
                  <ul className="space-y-1">
                    {options.map((o) => {
                      const selected = chosen?.id === o.id;
                      return (
                        <li key={o.id}>
                          <label
                            className={`w-full px-2 py-2 rounded-lg border flex items-center gap-3 cursor-pointer ${selected ? 'border-primary' : 'border-border'}`}
                          >
                            <input
                              type="radio"
                              name="bill-picker"
                              checked={selected}
                              onChange={() => setChosenId(o.id)}
                              aria-label={`${o.category ?? 'No category'}, ${dayMonth(o.date)}, ${o.payee}, ${rupees(o.amount)}`}
                            />
                            <span className="min-w-0 flex-1">
                              <span className="block text-ink text-sm truncate">{`${o.category ?? 'No category'} · ${dayMonth(o.date)}`}</span>
                              <span className="block text-muted text-2xs truncate">{o.payee}</span>
                            </span>
                            <span className={`text-sm font-medium whitespace-nowrap ${props.mode === 'bill' ? 'text-danger' : 'text-success'}`}>
                              {`${props.mode === 'bill' ? '−' : '+'}${rupees(o.amount)}`}
                            </span>
                          </label>
                        </li>
                      );
                    })}
                  </ul>
                )}
              </div>
              {props.mode === 'bill' && !searching && (
                <p className="text-2xs text-muted mt-2">
                  Bills in the 2 weeks around it, nearest first. &ldquo;Something else&rdquo; searches all your payments.
                </p>
              )}
              {failed && <p role="alert" className="text-danger text-xs mt-2">{failed}</p>}
              <div className="flex flex-wrap gap-2 mt-4">
                {chosen && (
                  <Button onClick={() => void linkChosen()} loading={linking}>{linkLabel(chosen)}</Button>
                )}
                {props.mode === 'bill' && !searching && (
                  <Button variant="secondary" onClick={() => setSearching(true)} disabled={linking}>Something else</Button>
                )}
                <Button variant="secondary" onClick={props.onClose} disabled={linking}>Cancel</Button>
              </div>
            </>
          )}
        </div>
      </div>
    </>
  );
}
