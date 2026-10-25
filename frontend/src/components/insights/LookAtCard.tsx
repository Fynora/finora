import { useEffect, useRef, useState } from 'react';
import { Link, useNavigate } from 'react-router-dom';
import { useQuery } from '@tanstack/react-query';
import { ArrowLeftRight, ChevronRight, EyeOff, FileText, Repeat, TrendingUp, Upload, type LucideIcon } from 'lucide-react';
import { inflowApi, type PeopleSuggestion } from '../../api/endpoints';
import { Button, FinoraCard, Skeleton } from '../../design-system';
import { useDelayedLoading } from '../../hooks/useDelayedLoading';
import { ledgerLinkForCategory } from '../../lib/ledgerUrlFilters';
import { monthLong, rowKey, rowLabel, type LookAtRow } from '../../lib/insightsFeed';
import { rupees } from '../../lib/people';
import { trackNavigation } from '../../lib/trackNavigation';
import { toLocalDateString } from '../../utils/date';
import { useAskForMoney } from '../people/AskForMoney';
import { BillPickerModal } from '../people/BillPickerModal';
import { PeopleQuestion } from '../people/PeopleQuestion';
import { RecurringQuestion } from '../RecurringQuestion';

/** How long a "Got it" waits before it is saved, so a slip can be undone (website spec, "Got it"). */
export const UNDO_MS = 5000;

interface Props {
  status: 'loading' | 'error' | 'ready';
  /** The server's rows, less what was marked "Got it". */
  rows: LookAtRow[];
  /** The reporting month (YYYY-MM), for the empty lines; null when there is none. */
  month: string | null;
  /** Only one month of data: nothing can be compared yet. */
  firstMonth: boolean;
  /** Saves a "Got it" (once its undo time is over). */
  onAck: (row: LookAtRow) => void;
  onRetry: () => void;
  /** After an answer is saved: the page re-reads its figures. */
  onAnswered: () => void;
}

function cardPhrase(row: Extract<LookAtRow, { kind: 'card-statement' }>): string {
  if (!row.cardName) return 'a credit card';
  // The account's own name, often just its bank's ("Sample Bank"): say "card" unless it already does.
  const name = /\bcard\b/i.test(row.cardName) ? row.cardName : `${row.cardName} card`;
  return row.cardLast4 ? `your ${name} ending ${row.cardLast4}` : `your ${name}`;
}

/** The month's last day, YYYY-MM-DD. */
function lastDayOf(month: string): string {
  const [y, m] = month.split('-').map(Number);
  return `${month}-${String(new Date(y, m, 0).getDate()).padStart(2, '0')}`;
}

function RowIcon({ icon: Icon, tone }: { icon: LucideIcon; tone: 'warning' | 'primary' }) {
  return (
    <div className={`w-8 h-8 rounded-full flex items-center justify-center flex-shrink-0 ${
      tone === 'warning' ? 'bg-warning-bg text-warning' : 'bg-primary-light text-primary'}`}>
      <Icon size={16} aria-hidden />
    </div>
  );
}

/**
 * "To look at" on the website Insights page (docs/superpowers/specs/2026-10-24-web-insights-redesign-
 * design.md): what changed and needs the user, each row something to act on, in the server's order.
 * "Got it" hides a row at once and is saved only after UNDO_MS, so Undo needs nothing from the server;
 * leaving the page saves what is still waiting.
 */
export function LookAtCard({ status, rows, month, firstMonth, onAck, onRetry, onAnswered }: Props) {
  const navigate = useNavigate();
  const showSkeleton = useDelayedLoading(status === 'loading');
  // Rows hidden by "Got it" and not yet saved, by row key, with their timers.
  const [pending, setPending] = useState<Map<string, LookAtRow>>(new Map());
  const timers = useRef(new Map<string, { row: LookAtRow; timer: ReturnType<typeof setTimeout> }>());
  // The page's onAck can change between renders; the unmount flush must call the latest one.
  const ackRef = useRef(onAck);
  useEffect(() => { ackRef.current = onAck; }, [onAck]);

  // The built-in Paid back kind, which "Always do this for <person>" remembers the sender with.
  const kindsQ = useQuery({ queryKey: ['inflow-kinds'], queryFn: () => inflowApi.kinds(), retry: false });
  const paidBackKindId = (kindsQ.data ?? []).find((k) => k.builtIn === 'PAID_BACK')?.id ?? null;
  const [picking, setPicking] = useState<PeopleSuggestion | null>(null);
  const { ask, prompt } = useAskForMoney();
  const today = toLocalDateString(new Date());

  useEffect(() => {
    const waiting = timers.current;
    return () => {
      // Leaving the page: what was hidden stays hidden.
      for (const { row, timer } of waiting.values()) {
        clearTimeout(timer);
        ackRef.current(row);
      }
      waiting.clear();
    };
  }, []);

  function hide(row: LookAtRow) {
    const key = rowKey(row);
    if (timers.current.has(key)) return;
    const timer = setTimeout(() => {
      timers.current.delete(key);
      ackRef.current(row);
      setPending((p) => { const next = new Map(p); next.delete(key); return next; });
    }, UNDO_MS);
    timers.current.set(key, { row, timer });
    setPending((p) => new Map(p).set(key, row));
  }

  function undo(key: string) {
    const waiting = timers.current.get(key);
    if (waiting) clearTimeout(waiting.timer);
    timers.current.delete(key);
    setPending((p) => { const next = new Map(p); next.delete(key); return next; });
  }

  /** A row the user answered (not "not now"): gone at once, nothing to undo. */
  function ackNow(row: LookAtRow) {
    onAck(row);
  }

  function gotIt(row: LookAtRow, name: string) {
    return (
      <Button size="sm" variant="secondary" aria-label={`Got it: ${name}`} onClick={() => hide(row)}>Got it</Button>
    );
  }

  function renderRow(row: LookAtRow) {
    const key = rowKey(row);
    if (pending.has(key)) {
      const label = rowLabel(row);
      return (
        <div key={key} className="flex items-center gap-3 py-2 px-3 my-1 rounded bg-black/[0.03] text-sm">
          <EyeOff size={14} aria-hidden className="text-muted flex-shrink-0" />
          <span className="flex-1 min-w-0 text-muted" role="status">{`Hidden: ${label}`}</span>
          <Button size="sm" variant="secondary" aria-label={`Undo: ${label}`} onClick={() => undo(key)}>Undo</Button>
        </div>
      );
    }
    switch (row.kind) {
      case 'card-statement': {
        const name = monthLong(row.month);
        return (
          <div key={key} className="flex items-start gap-3 py-3 border-b border-dashed last:border-b-0">
            <RowIcon icon={FileText} tone="warning" />
            <div className="flex-1 min-w-0 space-y-2">
              <p className="text-sm text-ink">
                {`You paid ${rupees(row.total)} toward ${cardPhrase(row)} in ${name} with no card statement uploaded for it. Upload it to see what you bought.`}
              </p>
              <div className="flex flex-wrap gap-2">
                <Link
                  to="/app/import"
                  onClick={() => trackNavigation('import-statement', 'contextual')}
                  className="inline-flex items-center gap-1 rounded-lg bg-primary text-on-primary hover:bg-primary-dark text-xs font-medium px-3 py-1.5"
                >
                  <Upload size={13} aria-hidden /> Upload statement
                </Link>
                {gotIt(row, `card statement for ${name}`)}
              </div>
            </div>
          </div>
        );
      }
      case 'people':
        return (
          <PeopleQuestion
            key={key}
            item={row.item}
            today={today}
            paidBackKindId={paidBackKindId}
            onAck={() => ackNow(row)}
            onGotIt={() => hide(row)}
            onPickBill={setPicking}
            onAsk={(s) => ask({ label: s.label, owed: s.owed ?? s.amount })}
            // "Paid for something": that day's transactions, to give this one a category. By date
            // alone: the person's name is the server's label, not words the payment's text is known to have.
            onOpenPayment={(s) => {
              if (!s.date) return;
              trackNavigation('transactions', 'contextual');
              void navigate(`/app/transactions?from=${s.date}&to=${s.date}`);
            }}
            onAnswered={onAnswered}
          />
        );
      case 'new-recurring':
        return (
          <div key={key} className="flex items-start gap-3 py-3 border-b border-dashed last:border-b-0">
            <RowIcon icon={Repeat} tone="primary" />
            <div className="flex-1 min-w-0">
              <p className="text-sm font-medium text-ink">{`New repeating payment: ${row.item.merchant}`}</p>
              <RecurringQuestion
                merchant={row.item.merchant} state="NEEDS_ANSWER" answer={null}
                amount={row.item.averageAmount} label={row.item.label} onSaved={onAnswered}
              />
            </div>
          </div>
        );
      case 'amount-changed':
        return (
          <div key={key} className="flex items-start gap-3 py-3 border-b border-dashed last:border-b-0">
            <RowIcon icon={ArrowLeftRight} tone="warning" />
            <div className="flex-1 min-w-0 space-y-1">
              <p className="text-sm font-medium text-ink">{`Amount changed: ${row.merchant}`}</p>
              <RecurringQuestion
                merchant={row.merchant} state="AMOUNT_CHANGED" answer={row.category || null}
                amount={row.latestAmount} onSaved={onAnswered}
              />
              <div className="flex flex-wrap gap-2 pt-1">{gotIt(row, row.merchant)}</div>
            </div>
          </div>
        );
      case 'running-high': {
        const title = `${row.category} is ${rupees(row.current - row.priorAverage)} above your usual month`;
        const detail = `${rupees(row.current)} vs ${rupees(row.priorAverage)}`;
        return (
          <div key={key} className="flex items-center gap-3 py-3 border-b border-dashed last:border-b-0">
            <Link
              to={ledgerLinkForCategory(row.category, row.month, lastDayOf(row.month))}
              onClick={() => trackNavigation('transactions', 'contextual')}
              aria-label={`${title}, ${detail}. Shows the payments`}
              className="flex items-center gap-3 flex-1 min-w-0 group"
            >
              <RowIcon icon={TrendingUp} tone="warning" />
              <span className="flex-1 min-w-0">
                <span className="block text-sm font-medium text-ink group-hover:underline">{title}</span>
                <span className="block text-xs text-muted">{detail}</span>
              </span>
              <ChevronRight size={16} aria-hidden className="text-muted flex-shrink-0" />
            </Link>
            {gotIt(row, row.category)}
          </div>
        );
      }
    }
  }

  const visible = rows.filter((r) => !pending.has(rowKey(r)));
  const name = month ? monthLong(month) : null;

  return (
    <FinoraCard>
      <h2 id="look-at" className="font-semibold text-ink mb-2">
        To look at{visible.length > 0 && <>{' '}<span className="text-muted font-normal">{visible.length}</span></>}
      </h2>
      {status === 'loading' ? (
        <Skeleton.Region label="Loading what needs a look">
          {showSkeleton && (
            <div className="space-y-2">
              {[0, 1, 2].map((i) => <Skeleton.Block key={i} className="h-10 w-full" />)}
            </div>
          )}
        </Skeleton.Region>
      ) : status === 'error' ? (
        <div className="space-y-2">
          <p className="text-sm text-muted">Couldn't load what needs a look.</p>
          <Button size="sm" variant="secondary" onClick={onRetry}>Try again</Button>
        </div>
      ) : rows.length === 0 ? (
        firstMonth && name ? (
          <div className="space-y-2">
            <p className="text-sm text-ink">{`Nothing to compare ${name} with yet. Add the month before and Fynora can tell you what changed.`}</p>
            <Link
              to="/app/import"
              onClick={() => trackNavigation('import-statement', 'contextual')}
              className="inline-flex items-center gap-1 rounded-md border border-border text-xs font-medium px-3 py-1.5 hover:bg-black/[0.03]"
            >
              <Upload size={13} aria-hidden /> Upload last month's statement
            </Link>
          </div>
        ) : (
          <p className="text-sm text-muted">{name ? `Nothing new to look at for ${name}.` : 'Nothing new to look at.'}</p>
        )
      ) : (
        <div>{rows.map(renderRow)}</div>
      )}
      {prompt}
      {picking && picking.txnId && (
        <BillPickerModal
          mode="bill"
          credit={{ txnId: picking.txnId, label: picking.label, amount: picking.amount, date: picking.date ?? today }}
          options={picking.bills}
          onClose={() => { setPicking(null); onAnswered(); }}
        />
      )}
    </FinoraCard>
  );
}
