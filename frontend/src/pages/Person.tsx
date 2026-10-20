import { useRef, useState } from 'react';
import { Link, useParams } from 'react-router-dom';
import { useQuery, useQueryClient } from '@tanstack/react-query';
import { ArrowLeft } from 'lucide-react';
import { peopleApi, type PersonTimelineItem } from '../api/endpoints';
import { Button, FinoraCard, SectionHeader, Skeleton } from '../design-system';
import { useInvalidateMoneyFigures } from '../lib/invalidateMoneyFigures';
import { dayMonth, rupees, serverMessage } from '../lib/people';
import { trackNavigation } from '../lib/trackNavigation';

/** One line of the history, in the words of the approved loan mockup (and the app's person page). */
function describe(item: PersonTimelineItem): string {
  const amount = rupees(item.amount);
  switch (item.kind) {
    case 'LENT': return `You lent ${amount}`;
    case 'PAID_BACK': return `Paid back ${amount}`;
    case 'BORROWED': return `You borrowed ${amount}`;
    case 'REPAID': return `You paid back ${amount}`;
    case 'CASH_SETTLED': return `Settled in cash ${amount}`;
    case 'LET_GO': return `Let go ${amount}`;
    case 'SHARE': return `Their share of a bill ${amount}`;
  }
}

/**
 * One person (spec §7, "Person page"): the balance both ways, the history of lent, back and shares,
 * the every-month rules for them (removed here), and whether they are family -- a family person is
 * never asked "lent?" or "settle it?". Reached from Insights' With people and a Ledger row, not the
 * sidebar.
 */
export default function Person() {
  const { personKey = '' } = useParams();
  const queryClient = useQueryClient();
  const refreshFigures = useInvalidateMoneyFigures();
  const personQ = useQuery({ queryKey: ['people', 'person', personKey], queryFn: () => peopleApi.person(personKey), enabled: !!personKey });
  const [failed, setFailed] = useState<string | null>(null);
  const [busy, setBusy] = useState(false);
  const inFlight = useRef(false);

  async function save(write: () => Promise<unknown>) {
    if (inFlight.current) return;
    inFlight.current = true;
    setBusy(true);
    setFailed(null);
    try {
      await write();
      refreshFigures();
      void queryClient.invalidateQueries({ queryKey: ['people'] });
    } catch (err) {
      setFailed(serverMessage(err, "Couldn't save that. Try again."));
    } finally {
      inFlight.current = false;
      setBusy(false);
    }
  }

  const p = personQ.data;

  return (
    <div className="space-y-6 max-w-2xl">
      <Link to="/app/insights" onClick={() => trackNavigation('insights', 'contextual')} className="inline-flex items-center gap-1 text-xs font-medium text-primary">
        <ArrowLeft size={14} aria-hidden /> Insights
      </Link>

      {personQ.isLoading ? (
        <Skeleton.Region label="Loading this person">
          <Skeleton.Block className="h-24 w-full" />
        </Skeleton.Region>
      ) : !p ? (
        <FinoraCard>
          <p className="text-muted text-sm">Couldn&apos;t load this person. Try again later.</p>
        </FinoraCard>
      ) : (
        <>
          <FinoraCard>
            <h1 className="text-xl font-semibold text-ink">{p.label}</h1>
            <p className={`text-lg font-semibold mt-1 ${p.owedToUser > 0 ? 'text-success' : p.owedToUser < 0 ? 'text-danger' : 'text-ink'}`}>
              {p.owedToUser > 0 ? `Owes you ${rupees(p.owedToUser)}` : p.owedToUser < 0 ? `You owe ${rupees(-p.owedToUser)}` : 'All settled'}
            </p>
            {p.owedToUser !== 0 && p.since && <p className="text-xs text-muted">{`Open since ${dayMonth(p.since)}`}</p>}
          </FinoraCard>

          {p.timeline.length > 0 && (
            <FinoraCard>
              <SectionHeader title="History" />
              <ul>
                {p.timeline.map((item, i) => (
                  <li key={`${item.date}-${item.kind}-${item.txnId ?? i}`} className="flex items-center gap-3 text-sm border-b border-dashed py-2 last:border-b-0">
                    <span className="w-14 text-2xs text-muted">{dayMonth(item.date)}</span>
                    <span className="flex-1 text-ink">{describe(item)}</span>
                  </li>
                ))}
              </ul>
            </FinoraCard>
          )}

          {p.rules.length > 0 && (
            <FinoraCard>
              <SectionHeader title="Every month" />
              <ul>
                {p.rules.map((r) => {
                  const name = r.billCategory ?? 'bill';
                  return (
                    <li key={r.id} className="flex items-center gap-3 text-sm border-b border-dashed py-2 last:border-b-0">
                      <span className="flex-1 min-w-0">
                        <span className="block text-ink">{`${name} shares, every month`}</span>
                        {r.billMerchant && <span className="block text-2xs text-muted truncate">{r.billMerchant}</span>}
                      </span>
                      <Button size="sm" variant="danger" disabled={busy} aria-label={`Remove the ${name} rule`} onClick={() => void save(() => peopleApi.removeSplitRule(r.id))}>
                        Remove
                      </Button>
                    </li>
                  );
                })}
              </ul>
            </FinoraCard>
          )}

          <FinoraCard>
            <label className="flex items-start gap-3 text-sm cursor-pointer">
              <input
                type="checkbox"
                checked={p.family}
                disabled={busy}
                onChange={(e) => void save(() => peopleApi.setFamily(personKey, e.target.checked))}
                aria-label="Family"
                className="mt-1"
              />
              <span>
                <span className="block font-medium text-ink">Family</span>
                <span className="block text-xs text-muted">Money with family is never asked about as a loan.</span>
              </span>
            </label>
          </FinoraCard>
          {failed && <p role="alert" className="text-danger text-xs">{failed}</p>}
        </>
      )}
    </div>
  );
}
