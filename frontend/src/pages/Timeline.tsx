import { useQuery } from '@tanstack/react-query';
import { Sparkles } from 'lucide-react';
import { FinoraCard, EmptyState, SectionHeader } from '../design-system';
import { dashboardApi } from '../api/endpoints';
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
  const { data } = useQuery({ queryKey: ['timeline'], queryFn: dashboardApi.timeline });
  const groups = data ? groupByYear(data) : [];

  return (
    <div>
      <SectionHeader title="Your Journey" />
      {groups.length === 0 && (
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
              </li>
            ))}
          </ol>
        </FinoraCard>
      ))}
    </div>
  );
}
