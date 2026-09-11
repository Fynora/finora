import { useQuery } from '@tanstack/react-query';
import { Sparkles } from 'lucide-react';
import { FinoraCard, EmptyState, SectionHeader } from '../design-system';
import { dashboardApi } from '../api/endpoints';
import { badgeForEvent } from '../lib/timelineBadges';
import type { TimelineEvent } from '../types';

function groupByYear(events: TimelineEvent[]): [string, TimelineEvent[]][] {
  const groups = new Map<string, TimelineEvent[]>();
  for (const e of events) {
    const year = new Date(e.occurredAt).getFullYear().toString();
    groups.set(year, [...(groups.get(year) ?? []), e]);
  }
  return [...groups.entries()].sort((a, b) => b[0].localeCompare(a[0]));
}

export default function Timeline() {
  // Bug fix: `data` starts undefined while the query is in flight, which is indistinguishable
  // from "genuinely no events" -- the EmptyState rendered on every mount and then popped to real
  // content once the fetch resolved. Same class of bug already fixed once in this codebase, see
  // Budgets.tsx's own `loading` state doc comment for the identical failure mode. `isLoading`
  // gates the EmptyState branch explicitly now.
  const { data, isLoading } = useQuery({ queryKey: ['timeline'], queryFn: dashboardApi.timeline });
  const groups = data ? groupByYear(data) : [];

  return (
    <div>
      <SectionHeader title="Your Journey" />
      {!isLoading && groups.length === 0 && (
        <EmptyState
          icon={Sparkles}
          iconBg="bg-primary-light"
          iconColor="text-primary"
          title="Your journey starts here"
          desc="Milestones you reach will show up on this page."
        />
      )}
      {groups.map(([year, events]) => (
        <FinoraCard key={year} padding="lg" className="mb-6">
          {/* h3, not h2 -- SectionHeader above already renders the page's one h2 ("Your
              Journey"); a year group is a subsection of that. */}
          <h3 className="font-semibold text-ink mb-4">{year}</h3>
          <ol>
            {events.map((e) => (
              <li key={e.eventType + e.occurredAt} className="mb-3">
                <p className="text-sm font-medium text-ink">{e.title}</p>
                {e.detail && <p className="text-xs text-muted">{e.detail}</p>}
                {badgeForEvent(e) && (
                  <span className="inline-block mt-1 text-xs font-medium text-primary bg-primary-light rounded-full px-2 py-0.5">
                    {badgeForEvent(e)}
                  </span>
                )}
              </li>
            ))}
          </ol>
        </FinoraCard>
      ))}
    </div>
  );
}
