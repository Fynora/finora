import { useEffect, useState } from 'react';
import { useQueryClient } from '@tanstack/react-query';
import { PiggyBank } from 'lucide-react';
import { budgetsApi, categoriesApi, type CategoryOption } from '../api/endpoints';
import type { Budget } from '../types';
import { FinoraCard, EmptyState, Button, ConfirmDialog, Skeleton, SectionHeader } from '../design-system';
import { useDelayedLoading } from '../hooks/useDelayedLoading';
import { GROUP_LABEL, groupBudgets, monthClock, overallPace, type BudgetGroup } from '../lib/budgetPace';
import { BudgetHero } from '../components/budgets/BudgetHero';
import { BudgetRow } from '../components/budgets/BudgetRow';

function fmt(n: number) {
  // Negative amounts (e.g. a month where spend exceeded income) must render as "-₹500",
  // not "₹-500" -- string concatenation put the currency symbol before the sign.
  return (n < 0 ? '-₹' : '₹') + Math.round(Math.abs(n)).toLocaleString('en-IN');
}

const GROUP_INK: Record<BudgetGroup, string> = {
  over: 'text-danger',
  'heading-over': 'text-warning',
  'on-track': 'text-success',
};
const GROUP_DOT: Record<BudgetGroup, string> = {
  over: 'bg-danger',
  'heading-over': 'bg-warning',
  'on-track': 'bg-success',
};

/**
 * Budgets: the month at a glance (ring hero), then every budget grouped by risk -- over, heading
 * over at this pace, on track -- with a pace marker on each bar. All numbers and status lines come
 * from lib/budgetPace.ts, shared byte for byte with the phone app.
 * Design: docs/superpowers/specs/2026-11-17-budgets-redesign-design.md
 */
export default function Budgets() {
  const [budgets, setBudgets] = useState<Budget[]>([]);
  const [newCategory, setNewCategory] = useState('');
  const [newLimit, setNewLimit] = useState('');
  const [error, setError] = useState<string | null>(null);
  const [saved, setSaved] = useState(false);
  const [saving, setSaving] = useState(false);
  const [confirmRemove, setConfirmRemove] = useState<Budget | null>(null);
  const [removingId, setRemovingId] = useState<string | null>(null);
  // `loading` gates the EmptyState explicitly: `budgets` starts `[]`, which can't be told apart from
  // "genuinely no budgets set". `showSkeleton` only decides whether a skeleton shows during that gate.
  const [loading, setLoading] = useState(true);
  const [categoriesById, setCategoriesById] = useState<Map<string, CategoryOption>>(new Map());
  const queryClient = useQueryClient();

  function load() {
    setLoading(true);
    // Categories only supply each row's icon/color, with a safe fallback in the row -- so a
    // categories hiccup must not block budgets. allSettled lets it degrade to the fallback while a
    // budgets failure still surfaces the real error.
    Promise.allSettled([budgetsApi.list(), categoriesApi.list()])
      .then(([budgetsResult, categoriesResult]) => {
        if (budgetsResult.status === 'rejected') {
          setError('Could not load budgets.');
          return;
        }
        setBudgets(budgetsResult.value);
        setCategoriesById(
          categoriesResult.status === 'fulfilled'
            ? new Map(categoriesResult.value.map((c) => [c.id, c]))
            : new Map()
        );
      })
      .catch(() => setError('Could not load budgets.'))
      .finally(() => setLoading(false));
  }
  useEffect(load, []);
  const showSkeleton = useDelayedLoading(loading);

  function invalidateBudgetReaders() {
    // Dashboard reads budgets through TanStack Query ('budgets', 30s staleTime); overspend feeds its
    // notices and health score ('dashboard-summary'); the Getting-started checklist derives "Create a
    // budget" from budget existence ('onboarding').
    void queryClient.invalidateQueries({ queryKey: ['budgets'] });
    void queryClient.invalidateQueries({ queryKey: ['dashboard-summary'] });
    void queryClient.invalidateQueries({ queryKey: ['onboarding'] });
  }

  async function addOrUpdate() {
    if (!newCategory || !newLimit) return;
    const limit = parseFloat(newLimit);
    if (!(limit > 0)) {
      setError('Monthly limit must be greater than zero.');
      return;
    }
    setError(null);
    setSaving(true);
    try {
      await budgetsApi.upsert(newCategory, limit);
      setNewCategory('');
      setNewLimit('');
      setSaved(true);
      setTimeout(() => setSaved(false), 2000);
      load();
      invalidateBudgetReaders();
    } catch (e: any) {
      setError(e.response?.data?.message ?? 'Could not save this budget. Try again.');
    } finally {
      setSaving(false);
    }
  }

  async function remove(budget: Budget) {
    setError(null);
    setRemovingId(budget.id);
    try {
      await budgetsApi.remove(budget.id);
      load();
      invalidateBudgetReaders();
    } catch (e: any) {
      setError(e.response?.data?.message ?? 'Could not remove this budget. Try again.');
    } finally {
      setRemovingId(null);
    }
  }

  const today = new Date();
  const clock = monthClock(today);
  const totalSpent = budgets.reduce((sum, b) => sum + b.spentThisMonth, 0);
  const totalLimit = budgets.reduce((sum, b) => sum + b.monthlyLimit, 0);
  const groups = groupBudgets(budgets, clock);

  return (
    <div className="space-y-5">
      <div className="flex flex-wrap items-end justify-between gap-3">
        <div>
          <h1 className="font-display text-2xl font-extrabold text-ink md:text-3xl">Budgets</h1>
          <p className="text-sm text-muted">{`${clock.monthLabel} · day ${clock.dayOfMonth} of ${clock.daysInMonth}`}</p>
        </div>
      </div>

      {loading ? (
        showSkeleton && (
          <Skeleton.Region label="Loading budget summary">
            <FinoraCard padding="lg" className="flex items-center gap-8">
              <Skeleton.Circle size={148} />
              <div className="grid flex-1 grid-cols-3 gap-6">
                {[0, 1, 2].map((i) => <Skeleton.Block key={i} className="h-16" />)}
              </div>
            </FinoraCard>
          </Skeleton.Region>
        )
      ) : budgets.length > 0 && (
        <BudgetHero totalSpent={totalSpent} totalLimit={totalLimit} overall={overallPace(budgets, clock)} clock={clock} money={fmt} />
      )}

      <FinoraCard>
        <SectionHeader title="Set a Budget" size="sm" />
        <div className="flex gap-2 items-end">
          <div>
            <label htmlFor="budget-category" className="block text-xs uppercase text-muted mb-1">Category</label>
            <input id="budget-category" value={newCategory} onChange={(e) => setNewCategory(e.target.value)} className="bg-card text-ink border rounded px-2 py-1.5 text-sm" />
          </div>
          <div>
            <label htmlFor="budget-monthly-limit" className="block text-xs uppercase text-muted mb-1">Monthly limit</label>
            <input id="budget-monthly-limit" type="number" value={newLimit} onChange={(e) => setNewLimit(e.target.value)} className="bg-card text-ink border rounded px-2 py-1.5 text-sm" />
          </div>
          <Button onClick={addOrUpdate} loading={saving} size="md" className="uppercase">
            Set Budget
          </Button>
          {saved && <span className="text-success text-xs">Saved.</span>}
        </div>
      </FinoraCard>
      {error && <p className="text-danger text-sm">{error}</p>}

      {loading ? (
        showSkeleton && (
          <FinoraCard>
            <Skeleton.Region label="Loading budgets">
              <div className="space-y-4">
                {[0, 1, 2].map((i) => (
                  <div key={i} className="flex items-center gap-4">
                    <Skeleton.Circle size={32} />
                    <Skeleton.Text width="w-32" />
                    <Skeleton.Block className="h-2 flex-1" />
                    <Skeleton.Text width="w-36" />
                    <Skeleton.Text width="w-40" />
                  </div>
                ))}
              </div>
            </Skeleton.Region>
          </FinoraCard>
        )
      ) : budgets.length === 0 ? (
        <FinoraCard>
          <EmptyState
            icon={PiggyBank}
            iconBg="bg-accent-orange-bg"
            iconColor="text-accent-orange"
            title="No budgets set"
            desc="Choose New budget to start tracking a category."
          />
        </FinoraCard>
      ) : (
        <FinoraCard padding="none" className="px-6 pb-5 pt-2">
          {groups.map((g) => (
            <section key={g.group} aria-label={GROUP_LABEL[g.group]}>
              <h3 className={`flex items-center gap-2 pb-1.5 pt-4 text-[13px] font-semibold ${GROUP_INK[g.group]}`}>
                <span aria-hidden="true" className={`h-2 w-2 rounded-full ${GROUP_DOT[g.group]}`} />
                {`${GROUP_LABEL[g.group]} · ${g.items.length}`}
              </h3>
              {g.items.map(({ budget: b, pace }) => (
                <BudgetRow
                  key={b.id}
                  budget={b}
                  pace={pace}
                  clock={clock}
                  today={today}
                  category={categoriesById.get(b.categoryId)}
                  money={fmt}
                  onRemove={() => setConfirmRemove(b)}
                  removing={removingId === b.id}
                />
              ))}
            </section>
          ))}
          <p className="flex items-center gap-2.5 pt-3.5 text-xs text-muted">
            <span aria-hidden="true" className="h-3.5 w-0.5 rounded-full bg-ink" />
            Marks where spending should be today if you spread each budget evenly over the month. Dotted limits can be edited in place.
          </p>
        </FinoraCard>
      )}

      {confirmRemove && (
        <ConfirmDialog
          title={`Remove the ${confirmRemove.categoryName} budget?`}
          message="Your transactions stay as they are. You can set a budget for this category again at any time."
          confirmLabel="Remove"
          danger
          onConfirm={() => {
            const budget = confirmRemove;
            setConfirmRemove(null);
            void remove(budget);
          }}
          onCancel={() => setConfirmRemove(null)}
        />
      )}
    </div>
  );
}
