import { useQuery } from '@tanstack/react-query';
import { Link } from 'react-router-dom';
import { Sparkles } from 'lucide-react';
import { FinoraCard } from '../design-system';
import { dashboardApi } from '../api/endpoints';
import type { TimelineEvent } from '../types';

// Most recent Landmark event, falling back to the most recent Major event when the user has
// no Landmark event yet -- see the design spec's importance tiers (§4.3): Minor events never
// surface here, only in the full timeline list.
function mostRecentHighlight(events: TimelineEvent[]): TimelineEvent | undefined {
  return events.find((e) => e.importance === 'LANDMARK') ?? events.find((e) => e.importance === 'MAJOR');
}

export function JourneyWidget() {
  const { data } = useQuery({ queryKey: ['timeline'], queryFn: dashboardApi.timeline });
  const highlight = data ? mostRecentHighlight(data) : undefined;

  if (!highlight) return null;

  return (
    <FinoraCard padding="lg" className="mb-6">
      <div className="flex items-center gap-2 mb-2">
        <div className="w-8 h-8 rounded-full bg-primary-light flex items-center justify-center">
          <Sparkles size={15} className="text-primary" />
        </div>
        <h2 className="font-semibold text-ink">Your Journey</h2>
      </div>
      <p className="text-sm text-ink">{highlight.title}</p>
      <Link to="/app/journey" className="inline-block mt-2 text-[11px] font-medium text-primary">
        View your journey
      </Link>
    </FinoraCard>
  );
}
