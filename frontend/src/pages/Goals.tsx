import { useEffect, useState } from 'react';
import { useQueryClient } from '@tanstack/react-query';
import { Target } from 'lucide-react';
import { goalsApi } from '../api/endpoints';
import type { Goal } from '../types';
import { FinoraCard, EmptyState, ConfirmDialog, Button, Skeleton, Badge } from '../design-system';
import { useDelayedLoading } from '../hooks/useDelayedLoading';
import { toLocalDateString } from '../utils/date';

function fmt(n: number) {
  // Negative amounts (e.g. a month where spend exceeded income) must render as "-₹500",
  // not "₹-500" -- string concatenation put the currency symbol before the sign.
  return (n < 0 ? '-₹' : '₹') + Math.round(Math.abs(n)).toLocaleString('en-IN');
}

// Goal has no creation date (see types/index.ts), so an "on track / behind" read against elapsed
// time can't be computed honestly -- there's nothing to measure elapsed time FROM. This sticks to
// the two states the data actually supports: fully funded, or past its own target date and not
// yet funded. Anything else renders no badge rather than a guessed status.
//
// targetDate is a plain "YYYY-MM-DD" LocalDate, same shape date.ts's own formatDate documents --
// comparing it as a Date object (`new Date(targetDate) < new Date()`) parses the date-only string
// as UTC midnight, which reads as yesterday for anyone east of UTC (IST included) until UTC catches
// up -- the exact bug #1358 just fixed for the transaction-date default. String-comparing against
// toLocalDateString(new Date()) (also "YYYY-MM-DD", also in local time) sidesteps that entirely:
// ISO date strings sort lexicographically in calendar order.
function goalStatus(pct: number, targetDate: string | undefined): { tone: 'success' | 'danger'; label: string } | null {
  if (pct >= 100) return { tone: 'success', label: 'Completed' };
  if (targetDate && targetDate < toLocalDateString(new Date())) {
    return { tone: 'danger', label: 'Past due' };
  }
  return null;
}

export default function Goals() {
  const [goals, setGoals] = useState<Goal[]>([]);
  const [name, setName] = useState('');
  const [target, setTarget] = useState('');
  const [current, setCurrent] = useState('');
  const [deadline, setDeadline] = useState('');
  const [error, setError] = useState<string | null>(null);
  const [saving, setSaving] = useState(false);
  const [confirmRemoveId, setConfirmRemoveId] = useState<string | null>(null);
  // Bug fix: no loading flag existed, so `goals` starting `[]` was indistinguishable from
  // "genuinely no goals" -- see the identical fix and reasoning on Budgets.tsx.
  const [loading, setLoading] = useState(true);
  const queryClient = useQueryClient();

  function load() {
    setLoading(true);
    goalsApi.list().then(setGoals).catch(() => setError('Could not load goals.')).finally(() => setLoading(false));
  }
  useEffect(load, []);
  const showSkeleton = useDelayedLoading(loading);

  // Dashboard reads goals through TanStack Query under the 'goals' key (30s staleTime, same
  // cache Ledger/Import/AskOnceCard already invalidate after their own mutations). This page
  // keeps its own local `goals` list via load() above, but without also invalidating that shared
  // cache, a goal created/funded/deleted here wouldn't show up on the Dashboard's Goals widget
  // until the cache aged out on its own -- stale numbers on a page whose whole pitch is "watch it
  // update in real time." 'dashboard-summary' goes too since goal progress feeds it.
  function invalidateSharedCaches() {
    void queryClient.invalidateQueries({ queryKey: ['goals'] });
    void queryClient.invalidateQueries({ queryKey: ['dashboard-summary'] });
    // Getting-started checklist's "Create a goal" item is derived directly from Goal
    // existence -- same reasoning as the two invalidations just above.
    void queryClient.invalidateQueries({ queryKey: ['onboarding'] });
  }

  async function addGoal() {
    if (!name || !target) return;
    const targetAmount = parseFloat(target);
    if (!(targetAmount > 0)) {
      setError('Target amount must be greater than zero.');
      return;
    }
    setError(null);
    setSaving(true);
    try {
      await goalsApi.create({ name, targetAmount, currentAmount: parseFloat(current || '0'), targetDate: deadline || undefined });
      setName(''); setTarget(''); setCurrent(''); setDeadline('');
      load();
      invalidateSharedCaches();
    } catch (e: any) {
      setError(e.response?.data?.message ?? 'Could not create this goal. Try again.');
    } finally {
      setSaving(false);
    }
  }

  async function contribute(id: string) {
    const amt = parseFloat(prompt('Contribution amount:') ?? '');
    if (isNaN(amt) || amt <= 0) return;
    try {
      await goalsApi.addContribution(id, amt);
      load();
      invalidateSharedCaches();
    } catch (e: any) {
      setError(e.response?.data?.message ?? 'Could not record this contribution. Try again.');
    }
  }

  async function remove(id: string) {
    try {
      await goalsApi.remove(id);
      load();
      invalidateSharedCaches();
    } catch (e: any) {
      setError(e.response?.data?.message ?? 'Could not delete this goal. Try again.');
    }
  }

  return (
    <div className="space-y-4">
      <FinoraCard padding="sm" className="grid grid-cols-2 md:grid-cols-4 gap-2 items-end">
        <div><label htmlFor="goal-name" className="block text-xs uppercase text-muted mb-1">Name</label><input id="goal-name" value={name} onChange={(e) => setName(e.target.value)} className="bg-card text-ink border rounded px-2 py-1.5 text-sm w-full" /></div>
        <div><label htmlFor="goal-target" className="block text-xs uppercase text-muted mb-1">Target</label><input id="goal-target" type="number" value={target} onChange={(e) => setTarget(e.target.value)} className="bg-card text-ink border rounded px-2 py-1.5 text-sm w-full" /></div>
        <div><label htmlFor="goal-starting-amount" className="block text-xs uppercase text-muted mb-1">Starting amount</label><input id="goal-starting-amount" type="number" value={current} onChange={(e) => setCurrent(e.target.value)} className="bg-card text-ink border rounded px-2 py-1.5 text-sm w-full" /></div>
        <div><label htmlFor="goal-target-date" className="block text-xs uppercase text-muted mb-1">Target date</label><input id="goal-target-date" type="date" value={deadline} onChange={(e) => setDeadline(e.target.value)} className="bg-card text-ink border rounded px-2 py-1.5 text-sm w-full" /></div>
        <Button onClick={addGoal} loading={saving} className="uppercase col-span-2 md:col-span-1">
          Add Goal
        </Button>
      </FinoraCard>
      {error && <p className="text-danger text-sm">{error}</p>}

      <div className="space-y-3">
        {loading ? (
          showSkeleton && (
            <Skeleton.Region label="Loading goals">
              <div className="space-y-3">
                {[0, 1].map((i) => (
                  <FinoraCard key={i} padding="sm">
                    <div className="flex justify-between items-baseline mb-2">
                      <Skeleton.Text width="w-32" className="h-5" />
                      <Skeleton.Text width="w-20" />
                    </div>
                    <Skeleton.Block className="h-2 w-full mb-2" />
                    <Skeleton.Text width="w-40" />
                  </FinoraCard>
                ))}
              </div>
            </Skeleton.Region>
          )
        ) : goals.length === 0 ? (
          <FinoraCard padding="sm">
            <EmptyState
              icon={Target}
              iconBg="bg-accent-red-bg"
              iconColor="text-accent-red"
              title="No goals yet"
              desc="Add your first goal above to start tracking progress."
            />
          </FinoraCard>
        ) : (
          goals.map((g) => {
            const pct = g.targetAmount > 0 ? Math.min(100, (g.currentAmount / g.targetAmount) * 100) : 0;
            const status = goalStatus(pct, g.targetDate);
            return (
              <FinoraCard key={g.id} padding="sm">
                <div className="flex justify-between items-baseline mb-2">
                  <span className="flex items-center gap-2">
                    <span className="text-lg font-semibold">{g.name}</span>
                    {status && <Badge tone={status.tone} label={status.label} />}
                  </span>
                  <span className="text-sm text-muted">{fmt(g.currentAmount)} / {fmt(g.targetAmount)}</span>
                </div>
                <div className="h-2 bg-bg rounded overflow-hidden mb-2">
                  <div className="h-full bg-success" style={{ width: `${pct}%` }} />
                </div>
                <div className="flex justify-between text-xs text-muted">
                  <span>{pct.toFixed(0)}% complete{g.targetDate ? ` · target ${g.targetDate}` : ''}</span>
                  <span className="flex gap-2">
                    <Button onClick={() => contribute(g.id)} variant="secondary" size="sm" className="uppercase">
                      Add Contribution
                    </Button>
                    <Button onClick={() => setConfirmRemoveId(g.id)} variant="danger" size="sm" className="uppercase">
                      Delete
                    </Button>
                  </span>
                </div>
              </FinoraCard>
            );
          })
        )}
      </div>

      {confirmRemoveId && (
        <ConfirmDialog
          title="Delete this goal?"
          message="This can't be undone."
          confirmLabel="Delete"
          danger
          onConfirm={() => {
            const id = confirmRemoveId;
            setConfirmRemoveId(null);
            void remove(id);
          }}
          onCancel={() => setConfirmRemoveId(null)}
        />
      )}
    </div>
  );
}
