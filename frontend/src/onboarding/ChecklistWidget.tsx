import { useState } from 'react';
import { useQuery } from '@tanstack/react-query';
import { Check, ListChecks, ChevronDown, ChevronRight } from 'lucide-react';
import { FinoraCard } from '../design-system';
import { onboardingApi } from '../api/endpoints';
import { CHECKLIST_ITEMS } from './checklistItems';

/**
 * Timeline UI, collapse toggle, and staggered reveal all follow the same pattern the (now
 * removed) FinancialJourney widget used -- the two cards showed near-identical onboarding
 * progress (profile/import/budget/goal appeared in both), so this absorbs Financial Journey's
 * richer presentation instead of keeping two redundant cards. `.journey-reveal-item` is reused
 * as-is rather than renamed: it's already a generic staggered-fade-in class (also used by
 * Ledger.tsx), not journey-specific in practice despite the name.
 *
 * Deliberately no per-item completion dates, unlike Financial Journey: only 2 of these 6 items
 * (REVIEW_TRANSACTIONS, VIEW_INSIGHTS) are tracked as explicit, dated events
 * (UserChecklistEvent.completedAt) -- the other 4 (profile/import/budget/goal) are DERIVED from
 * current account state with no historical "when did this become true" record at all. Showing a
 * real date on 2 items and none on the other 4 would look more broken than showing none.
 */
export function ChecklistWidget() {
  const { data } = useQuery({ queryKey: ['onboarding', 'checklist'], queryFn: onboardingApi.getChecklist });
  // Expanded by default -- same reasoning FinancialJourney used: this is a primary onboarding
  // widget, the first thing a new user should see, not a detail panel to tuck away by default.
  const [expanded, setExpanded] = useState(true);

  if (!data || data.completedCount >= data.totalCount) return null;

  const completedKeys = new Set(data.items.filter((i) => i.completed).map((i) => i.key));

  return (
    <FinoraCard padding="lg" className="mb-6">
      <button
        type="button"
        onClick={() => setExpanded((open) => !open)}
        aria-expanded={expanded}
        className={`w-full flex items-center justify-between text-left ${expanded ? 'mb-5' : ''}`}
      >
        <div className="flex items-center gap-2">
          <div className="w-8 h-8 rounded-full bg-primary-light flex items-center justify-center">
            <ListChecks size={15} className="text-primary" />
          </div>
          <h2 className="font-semibold text-ink">Getting Started</h2>
        </div>
        <div className="flex items-center gap-2">
          <span className="text-xs font-medium text-muted bg-bg rounded-full px-2.5 py-1">
            {data.completedCount} of {data.totalCount} complete
          </span>
          {expanded
            ? <ChevronDown size={16} className="text-muted flex-shrink-0" />
            : <ChevronRight size={16} className="text-muted flex-shrink-0" />}
        </div>
      </button>
      {expanded && (
      <ol>
        {CHECKLIST_ITEMS.map((item, i) => {
          const completed = completedKeys.has(item.key);
          const isLast = i === CHECKLIST_ITEMS.length - 1;
          return (
            <li
              key={item.key}
              className="journey-reveal-item flex gap-3"
              style={{ animationDelay: `${i * 80}ms` }}
            >
              <div className="flex flex-col items-center">
                <span
                  className={`w-6 h-6 rounded-full flex items-center justify-center flex-shrink-0 ${
                    completed ? 'bg-primary text-on-primary' : 'bg-bg border-2 border-border'
                  }`}
                >
                  {completed && <Check size={13} strokeWidth={3} />}
                </span>
                {!isLast && (
                  <span
                    className={`w-0.5 flex-1 my-1 ${completed ? 'bg-primary' : 'bg-border'}`}
                    style={{ minHeight: '1.5rem' }}
                  />
                )}
              </div>
              <div className={isLast ? '' : 'pb-5'}>
                <p className={`text-sm font-medium ${completed ? 'text-ink' : 'text-muted'}`}>
                  {item.label}
                </p>
              </div>
            </li>
          );
        })}
      </ol>
      )}
    </FinoraCard>
  );
}
