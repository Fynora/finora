import { useCallback, useEffect, useRef, useState } from 'react';
import { useQueryClient } from '@tanstack/react-query';
import { Plus, Wallet } from 'lucide-react';
import { accountsApi, networthApi, type NetWorthData } from '../api/endpoints';
import type { Account } from '../types';
import { useAsyncGuard } from '../hooks/useAsyncGuard';
import { useDelayedLoading } from '../hooks/useDelayedLoading';
import { Button, ConfirmDialog, EmptyState, FinoraCard, Skeleton } from '../design-system';
import { ComingUpCard } from '../components/investments/ComingUpCard';
import { AddHoldingDialog, UpdateValueDialog } from '../components/investments/HoldingDialogs';
import { HoldingsCard } from '../components/investments/HoldingsCard';
import { MoneyGoingInCard } from '../components/investments/MoneyGoingInCard';
import { NetWorthCard } from '../components/investments/NetWorthCard';
import { PortfolioCard } from '../components/investments/PortfolioCard';
import { assetShare, comingUp, shareLabel, summarizeHoldings } from '../lib/investmentSummary';

function fmt(n: number) {
  // Negative amounts must render as "-₹500", not "₹-500".
  return (n < 0 ? '-₹' : '₹') + Math.round(Math.abs(n)).toLocaleString('en-IN');
}

/**
 * Investments: the total and how it is spread, what matures next, money going in, then every holding
 * by type. All numbers and status lines come from lib/investmentSummary.ts, shared byte for byte with
 * the phone app. Design: docs/superpowers/specs/2026-11-18-investments-redesign-design.md
 */
export default function Investments() {
  const [holdings, setHoldings] = useState<Account[]>([]);
  const [netWorth, setNetWorth] = useState<NetWorthData | null>(null);
  const [loading, setLoading] = useState(true);
  // "The data below is not trustworthy": stops the empty state telling someone with holdings that
  // they have none when the fetch simply failed. Only a load with nothing on screen sets these.
  const [holdingsFailed, setHoldingsFailed] = useState(false);
  const [netWorthFailed, setNetWorthFailed] = useState(false);
  const [error, setError] = useState<string | null>(null);
  const [status, setStatus] = useState('');
  const [addOpen, setAddOpen] = useState(false);
  const [updating, setUpdating] = useState<Account | null>(null);
  const [confirmingDelete, setConfirmingDelete] = useState<Account | null>(null);
  const [deletingId, setDeletingId] = useState<string | null>(null);
  const hasHoldings = useRef(false);
  const hasNetWorth = useRef(false);
  const queryClient = useQueryClient();
  // Only the newest load may fill the page: a slower, older answer would undo a newer save or delete.
  const { beginRequest } = useAsyncGuard();

  const load = useCallback(() => {
    const isCurrent = beginRequest();
    // Settled separately, so one failing leaves the other usable.
    return Promise.allSettled([accountsApi.list(), networthApi.current()]).then(([accounts, worth]) => {
      if (!isCurrent()) return;
      if (accounts.status === 'fulfilled') {
        hasHoldings.current = true;
        setHoldings(accounts.value.filter((a) => a.accountType === 'INVESTMENT'));
        setHoldingsFailed(false);
      } else if (!hasHoldings.current) {
        setHoldingsFailed(true);
      }
      if (worth.status === 'fulfilled') {
        hasNetWorth.current = true;
        setNetWorth(worth.value);
        setNetWorthFailed(false);
      } else if (!hasNetWorth.current) {
        setNetWorthFailed(true);
      }
      setLoading(false);
    });
  }, [beginRequest]);
  useEffect(() => { void load(); }, [load]);
  const showSkeleton = useDelayedLoading(loading);

  /** A holding changes total assets, so the Dashboard's net worth and health score move too. */
  function refreshAfterChange() {
    void load();
    void queryClient.invalidateQueries({ queryKey: ['accounts'] });
    void queryClient.invalidateQueries({ queryKey: ['dashboard-summary'] });
  }

  async function saveValue(holding: Account, balance: number) {
    await accountsApi.update(holding.id, { name: holding.name, accountType: 'INVESTMENT', balance });
    setHoldings((current) => current.map((h) => (h.id === holding.id ? { ...h, balance } : h)));
    setUpdating(null);
    setStatus(`${holding.name} value saved.`);
    refreshAfterChange();
  }

  async function addHolding(body: { name: string; balance: number; investmentKind: string }) {
    const created = await accountsApi.create({ ...body, accountType: 'INVESTMENT' });
    setHoldings((current) => [...current, created]);
    setAddOpen(false);
    setStatus(`${body.name} added.`);
    refreshAfterChange();
  }

  async function deleteHolding(holding: Account) {
    setError(null);
    setDeletingId(holding.id);
    try {
      await accountsApi.remove(holding.id);
      setHoldings((current) => current.filter((h) => h.id !== holding.id));
      setStatus(`${holding.name} deleted.`);
      refreshAfterChange();
    } catch {
      setError('Could not delete this holding.');
    } finally {
      setDeletingId(null);
    }
  }

  const today = new Date();
  const summary = summarizeHoldings(holdings);
  const upcoming = comingUp(holdings, today);
  const invested = assetShare(summary.total, netWorth?.totalAssets);
  const countLabel = `${summary.count} ${summary.count === 1 ? 'holding' : 'holdings'}`;
  const showSummary = !loading && !holdingsFailed && summary.count > 0;

  return (
    <div className="space-y-5">
      <div className="flex flex-wrap items-end justify-between gap-3">
        <div>
          <h1 className="font-display text-2xl font-extrabold text-ink md:text-3xl">Investments</h1>
          {showSummary && (
            <p className="text-sm text-muted">{invested === null ? countLabel : `${countLabel} · ${shareLabel(invested)} of your assets`}</p>
          )}
        </div>
        <Button onClick={() => setAddOpen(true)}>
          <Plus size={16} aria-hidden="true" />
          Add holding
        </Button>
      </div>

      {error && <p role="alert" className="text-sm text-danger">{error}</p>}
      <p role="status" className="sr-only">{status}</p>

      {loading ? (
        <Skeleton.Region label="Loading your investments">
          {showSkeleton && (
            <div className="space-y-5">
              <Skeleton.Card />
              <div className="grid gap-5 lg:grid-cols-2">
                <Skeleton.Card />
                <Skeleton.Card />
              </div>
              <Skeleton.Card />
            </div>
          )}
        </Skeleton.Region>
      ) : (
        <>
          {holdingsFailed ? (
            <FinoraCard padding="lg">
              <p className="text-sm font-medium text-danger">Could not load your investments.</p>
            </FinoraCard>
          ) : summary.count === 0 ? (
            <FinoraCard>
              <EmptyState
                icon={Wallet}
                iconBg="bg-accent-blue-bg"
                iconColor="text-accent-blue"
                title="No investments yet"
                desc="Add a holding, or import a deposit statement."
              />
            </FinoraCard>
          ) : (
            <PortfolioCard summary={summary} money={fmt} />
          )}

          <div className={`grid items-start gap-5 ${upcoming.length > 0 ? 'lg:grid-cols-2' : ''}`}>
            <ComingUpCard rows={upcoming} money={fmt} />
            <MoneyGoingInCard today={today} />
          </div>

          {showSummary && (
            <HoldingsCard
              summary={summary}
              today={today}
              money={fmt}
              onUpdate={setUpdating}
              onDelete={setConfirmingDelete}
              busyId={deletingId}
            />
          )}

          <NetWorthCard netWorth={netWorth} failed={netWorthFailed} money={fmt} />
        </>
      )}

      {addOpen && <AddHoldingDialog onClose={() => setAddOpen(false)} onSave={addHolding} />}
      {updating && (
        <UpdateValueDialog
          holding={updating}
          money={fmt}
          onClose={() => setUpdating(null)}
          onSave={(value) => saveValue(updating, value)}
        />
      )}
      {confirmingDelete && (
        <ConfirmDialog
          title="Delete this holding?"
          message={`"${confirmingDelete.name}" will be removed from your net worth. This can't be undone.`}
          confirmLabel="Delete"
          danger
          onConfirm={() => {
            const holding = confirmingDelete;
            setConfirmingDelete(null);
            void deleteHolding(holding);
          }}
          onCancel={() => setConfirmingDelete(null)}
        />
      )}
    </div>
  );
}
