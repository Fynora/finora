import { useId, useState, type FormEvent } from 'react';
import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query';
import { Plus } from 'lucide-react';
import { AdminLayout } from '../components/AdminLayout';
import { RequirePermission } from '../components/ProtectedRoute';
import { DataTable, type DataTableColumn } from '../components/DataTable';
import { FormPanel } from '../components/FormPanel';
import { ConfirmDialog } from '../components/ConfirmDialog';
import { adminBillingOffersApi } from '../api/endpoints';
import type { BillingOfferAdmin, BillingOfferPlanOption, BillingOfferState } from '../types';
import { endOfIstDay, formatIst, formatIstDate, isoToIstInput, istInputToIso, lastIstDayBefore } from '../lib/istTime';

const CODE_MAX = 40;
const LABEL_MAX = 80;
const INPUT = 'w-full bg-bg border border-border rounded-lg px-3 py-2 text-sm';
const LABEL = 'text-xs font-medium text-muted mb-1 block';

const STATE_LABELS: Record<BillingOfferState, string> = {
  LIVE: 'Live',
  UPCOMING: 'Upcoming',
  ENDED: 'Ended',
};

function stateTone(state: BillingOfferState): string {
  switch (state) {
    case 'LIVE':
      return 'bg-success-bg text-success border-success';
    case 'UPCOMING':
      return 'bg-info-bg text-info border-info';
    default:
      return 'bg-bg text-muted border-border';
  }
}

function apiMessage(err: unknown, fallback: string): string {
  return (err as { response?: { data?: { message?: string } } })?.response?.data?.message ?? fallback;
}

function rupees(amount: number | null | undefined): string {
  if (amount == null) return '—';
  return `Rs ${amount.toLocaleString('en-IN', { minimumFractionDigits: 0, maximumFractionDigits: 2 })}`;
}

function cycleWord(cycle: string): string {
  return cycle === 'YEARLY' ? 'yearly' : 'monthly';
}

function optionKey(o: { planCode: string; billingCycle: string }): string {
  return `${o.planCode}/${o.billingCycle}`;
}

/** "08 Nov 2026, 00:00 IST (last day 07 Nov 2026)" for an end on an IST midnight; the exact time otherwise. */
function describeEnd(iso: string): string {
  const lastDay = lastIstDayBefore(iso);
  return lastDay ? `${formatIst(iso)} (last day ${formatIstDate(lastDay)})` : formatIst(iso);
}

/**
 * Create one offer for one plan and billing cycle. The start is typed in IST; the end is chosen as
 * the offer's last day, and the offer stops at 00:00 IST the day after it, because windows are
 * end-exclusive. The server is the authority on every rule; the checks here only save a round trip,
 * and its own message is shown otherwise.
 */
function OfferEditor({ plans, onCancel, onSaved }: {
  plans: BillingOfferPlanOption[];
  onCancel: () => void;
  onSaved: (offer: BillingOfferAdmin) => void;
}) {
  const id = useId();
  const firstReady = plans.find((p) => p.checkoutReady) ?? plans[0];
  const [planKey, setPlanKey] = useState(firstReady ? optionKey(firstReady) : '');
  const [code, setCode] = useState('');
  const [label, setLabel] = useState('');
  const [price, setPrice] = useState('');
  const [startsAt, setStartsAt] = useState('');
  const [lastDay, setLastDay] = useState('');
  const [error, setError] = useState<string | null>(null);
  const plan = plans.find((p) => optionKey(p) === planKey);

  const save = useMutation({
    mutationFn: adminBillingOffersApi.create,
    onSuccess: (offer) => onSaved(offer),
    onError: (err) => setError(apiMessage(err, 'Could not create the offer.')),
  });

  function onSubmit(e: FormEvent<HTMLFormElement>) {
    e.preventDefault();
    if (!plan || !code.trim() || !label.trim() || !price.trim() || !startsAt || !lastDay) {
      setError('Plan, code, name, price, start and last day are all required.');
      return;
    }
    if (!plan.checkoutReady) {
      setError(`${plan.planName} ${cycleWord(plan.billingCycle)} is not set up for checkout yet, so no one could buy this offer.`);
      return;
    }
    const amount = Number(price);
    if (!Number.isFinite(amount) || amount <= 0) {
      setError('The offer price must be a positive amount in rupees.');
      return;
    }
    if (amount >= plan.regularPrice) {
      setError(`The offer price must be below the regular price of ${rupees(plan.regularPrice)}.`);
      return;
    }
    const start = istInputToIso(startsAt);
    const end = endOfIstDay(lastDay);
    if (new Date(end).getTime() <= new Date(start).getTime()) {
      setError('The last day must be on or after the start day.');
      return;
    }
    setError(null);
    save.mutate({
      code: code.trim().toUpperCase(),
      planCode: plan.planCode,
      billingCycle: plan.billingCycle,
      firstPeriodPrice: amount,
      label: label.trim(),
      startsAt: start,
      endsAt: end,
    });
  }

  const period = plan?.billingCycle === 'YEARLY' ? 'first year' : 'first month';

  return (
    <FormPanel
      title="Create a billing offer"
      onCancel={onCancel}
      onSubmit={onSubmit}
      error={error}
      submitting={save.isPending}
      submitLabel="Create offer"
    >
      <div className="grid gap-4 sm:grid-cols-2">
        <div>
          <label htmlFor={`${id}-plan`} className={LABEL}>Plan and billing cycle</label>
          <select id={`${id}-plan`} className={INPUT} value={planKey} onChange={(e) => setPlanKey(e.target.value)}>
            {plans.map((p) => (
              <option key={optionKey(p)} value={optionKey(p)} disabled={!p.checkoutReady}>
                {p.planName} {cycleWord(p.billingCycle)}, regular {rupees(p.regularPrice)}
                {p.checkoutReady ? '' : ' (not set up for checkout)'}
              </option>
            ))}
          </select>
        </div>
        <div>
          <label htmlFor={`${id}-code`} className={LABEL}>Code (for example HOLI2027)</label>
          <input
            id={`${id}-code`}
            className={`${INPUT} uppercase`}
            value={code}
            maxLength={CODE_MAX}
            onChange={(e) => setCode(e.target.value)}
          />
        </div>
        <div>
          <label htmlFor={`${id}-label`} className={LABEL}>Name customers see (for example “Holi offer”)</label>
          <input
            id={`${id}-label`}
            className={INPUT}
            value={label}
            maxLength={LABEL_MAX}
            onChange={(e) => setLabel(e.target.value)}
          />
        </div>
        <div>
          <label htmlFor={`${id}-price`} className={LABEL}>Price for the {period}, in rupees, GST included</label>
          <input
            id={`${id}-price`}
            type="number"
            inputMode="decimal"
            min="1"
            step="0.01"
            className={INPUT}
            value={price}
            onChange={(e) => setPrice(e.target.value)}
          />
        </div>
        <div>
          <div className="flex items-center justify-between">
            <label htmlFor={`${id}-start`} className={LABEL}>Starts (IST)</label>
            <button
              type="button"
              className="text-xs text-primary hover:underline mb-1"
              onClick={() => setStartsAt(isoToIstInput(new Date().toISOString()))}
            >
              Start now
            </button>
          </div>
          <input
            id={`${id}-start`}
            type="datetime-local"
            className={INPUT}
            value={startsAt}
            onChange={(e) => setStartsAt(e.target.value)}
          />
        </div>
        <div>
          <label htmlFor={`${id}-last`} className={LABEL}>Last day (IST)</label>
          <input
            id={`${id}-last`}
            type="date"
            className={INPUT}
            value={lastDay}
            onChange={(e) => setLastDay(e.target.value)}
          />
          {lastDay && (
            <p className="text-xs text-muted mt-1" data-testid="ends-at-preview">
              Ends at {formatIst(endOfIstDay(lastDay))}.
            </p>
          )}
        </div>
      </div>
      <p className="text-xs text-muted">
        A customer who checks out while the offer is live pays this price for the {period}, then the regular price.
        Offers on the same plan and cycle cannot overlap: end the current one first. This sets the web (Razorpay)
        price only; an Android offer still has to be set up in Play Console.
      </p>
    </FormPanel>
  );
}

function BillingOffersContent() {
  const queryClient = useQueryClient();
  const [creating, setCreating] = useState(false);
  const [ending, setEnding] = useState<BillingOfferAdmin | null>(null);
  const [notice, setNotice] = useState<string | null>(null);

  const offers = useQuery({ queryKey: ['billing-offers'], queryFn: () => adminBillingOffersApi.list() });
  const plans = useQuery({ queryKey: ['billing-offer-plans'], queryFn: () => adminBillingOffersApi.plans() });

  function refresh() {
    void queryClient.invalidateQueries({ queryKey: ['billing-offers'] });
  }

  const endNow = useMutation({
    mutationFn: (offer: BillingOfferAdmin) => adminBillingOffersApi.endNow(offer.id),
    onSuccess: (_, offer) => {
      setEnding(null);
      setNotice(`Ended “${offer.label}” (${offer.code}). New checkouts get the regular price.`);
      refresh();
    },
    onError: (err) => {
      setEnding(null);
      setNotice(apiMessage(err, 'Could not end the offer.'));
    },
  });

  if (creating) {
    if (plans.data && plans.data.length > 0) {
      return (
        <OfferEditor
          plans={plans.data}
          onCancel={() => setCreating(false)}
          onSaved={(offer) => {
            setNotice(`Created “${offer.label}” (${offer.code}).`);
            refresh();
            setCreating(false);
          }}
        />
      );
    }
    return (
      <p className="text-sm text-danger" role="alert">
        {plans.isError ? 'Could not load the plans.' : plans.data ? 'There are no paid plans to make an offer for.' : 'Loading plans…'}
      </p>
    );
  }

  const columns: DataTableColumn<BillingOfferAdmin>[] = [
    {
      header: 'Offer',
      render: (o) => (
        <div>
          <p className="font-medium text-ink">{o.label}</p>
          <p className="text-xs text-muted font-mono">{o.code}</p>
        </div>
      ),
    },
    {
      header: 'Plan',
      render: (o) => `${o.planName ?? o.planCode ?? 'Unknown plan'} ${cycleWord(o.billingCycle)}`,
      cellClassName: 'text-muted',
    },
    {
      header: 'Price',
      render: (o) => (
        <div>
          <p className="text-ink">{rupees(o.firstPeriodPrice)} for the {o.billingCycle === 'YEARLY' ? 'first year' : 'first month'}</p>
          <p className="text-xs text-muted">regular {rupees(o.regularPrice)}</p>
        </div>
      ),
    },
    { header: 'Starts', render: (o) => formatIst(o.startsAt), cellClassName: 'text-muted' },
    { header: 'Ends', render: (o) => describeEnd(o.endsAt), cellClassName: 'text-muted' },
    {
      header: 'Status',
      render: (o) => (
        <div>
          <span className={`inline-block rounded-full border px-2 py-0.5 text-xs ${stateTone(o.state)}`}>
            {STATE_LABELS[o.state]}
          </span>
          {o.state === 'LIVE' && !o.appliedAtCheckout && (
            <p className="text-xs text-danger mt-1">Not charged: its price is not below the regular price.</p>
          )}
        </div>
      ),
    },
    {
      header: '',
      render: (o) =>
        o.state === 'ENDED' ? null : (
          <div className="flex justify-end text-sm">
            <button type="button" className="text-danger hover:underline" onClick={() => setEnding(o)}>
              End now
            </button>
          </div>
        ),
    },
  ];

  return (
    <div className="space-y-6">
      <div className="flex items-center justify-between gap-4">
        <p className="text-sm text-muted max-w-2xl">
          A first-period price on a paid plan for a set time, shown on the pricing card and charged at web checkout.
          All times are India time. Offers are never deleted: ended ones stay here as the record of what was offered.
        </p>
        <button
          type="button"
          onClick={() => { setNotice(null); setCreating(true); }}
          disabled={!plans.data}
          className="inline-flex items-center gap-1.5 bg-primary hover:bg-primary-dark text-on-primary text-sm font-semibold rounded-lg px-4 py-2.5 flex-shrink-0 disabled:opacity-50"
        >
          <Plus size={15} /> New offer
        </button>
      </div>

      {notice && <p className="text-sm text-ink" role="status">{notice}</p>}
      {offers.isError && !offers.data && (
        <p className="text-sm text-danger" role="alert">{apiMessage(offers.error, 'Could not load the offers.')}</p>
      )}

      <DataTable
        columns={columns}
        rows={offers.data ?? []}
        keyFor={(o) => o.id}
        loading={offers.isLoading}
        emptyMessage={offers.isError ? 'The offers could not be loaded.' : 'No offers yet.'}
      />

      {ending && (
        <ConfirmDialog
          title="End this offer now?"
          message={
            ending.state === 'LIVE'
              ? `“${ending.label}” (${ending.code}) stops now. Checkouts started from now on pay the regular price. ` +
                'This cannot be undone; to run it again, create a new offer with a new code.'
              : `“${ending.label}” (${ending.code}) has not started yet. Ending it now means it never goes live. ` +
                'This cannot be undone.'
          }
          confirmLabel="End now"
          danger
          busy={endNow.isPending}
          onConfirm={() => endNow.mutate(ending)}
          onCancel={() => setEnding(null)}
        />
      )}
    </div>
  );
}

export default function BillingOffers() {
  return (
    <AdminLayout title="Billing offers" subtitle="Time-limited first-period prices on paid plans.">
      <RequirePermission permission="SUBSCRIPTION_MANAGEMENT_VIEW">
        <BillingOffersContent />
      </RequirePermission>
    </AdminLayout>
  );
}
