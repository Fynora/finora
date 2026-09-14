import { useQuery } from '@tanstack/react-query';
import { Link } from 'react-router-dom';
import { Sparkles } from 'lucide-react';
import { FinoraCard, Skeleton } from '../design-system';
import { dashboardApi } from '../api/endpoints';
import { useDelayedLoading } from '../hooks/useDelayedLoading';
import type { TimelineEvent } from '../types';

// Most recent Landmark event, falling back to the most recent Major event when the user has
// no Landmark event yet -- see the design spec's importance tiers (§4.3): Minor events never
// surface here, only in the full timeline list.
function mostRecentHighlight(events: TimelineEvent[]): TimelineEvent | undefined {
  return events.find((e) => e.importance === 'LANDMARK') ?? events.find((e) => e.importance === 'MAJOR');
}

export function JourneyWidget() {
  // Bug fix: same gap as ChecklistWidget's own fix -- this rendered nothing at all while its
  // query was in flight (not even a skeleton), so it silently popped into existence whenever the
  // fetch took long enough to notice. `momentum` stays ungated here, same as the real content
  // below: it's a supplementary caption, not something the card's own appearance waits on.
  const { data, isLoading } = useQuery({ queryKey: ['timeline'], queryFn: dashboardApi.timeline });
  const { data: momentum } = useQuery({ queryKey: ['timeline', 'momentum'], queryFn: dashboardApi.momentum });
  const highlight = data ? mostRecentHighlight(data) : undefined;
  const showSkeleton = useDelayedLoading(isLoading);

  if (isLoading) {
    return showSkeleton ? (
      <Skeleton.Region label="Loading your journey" className="mb-6">
        <FinoraCard padding="lg">
          <div className="flex items-center gap-2 mb-2">
            <Skeleton.Circle size={32} />
            <Skeleton.Text width="w-24" className="h-4" />
          </div>
          <Skeleton.Text width="w-48" className="mt-1" />
        </FinoraCard>
      </Skeleton.Region>
    ) : null;
  }

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
      {momentum && momentum.activeMonths > 0 && (
        <p className="text-xs text-muted mt-1">
          Active {momentum.activeMonths} of the last {momentum.windowMonths} months
        </p>
      )}
      <Link to="/app/journey" className="inline-block mt-2 text-2xs font-medium text-primary">
        View your journey
      </Link>
    </FinoraCard>
  );
}
