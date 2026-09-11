import type { TimelineEvent } from '../types';

// Badges are a display label derived from a Landmark timeline event -- never a separately
// tracked state, per the design spec's Layer 3 requirement. Only Landmark-importance event
// types appear here; Minor/Major events are timeline texture, not badge-worthy.
const BADGE_LABELS: Record<string, string> = {
  FIRST_GOAL_CREATED: 'Goal Setter',
  GOAL_COMPLETED: 'Goal Achiever',
  NET_WORTH_10K: 'First ₹10K Saved',
  NET_WORTH_100K: 'Six-Figure Saver',
};

export function badgeForEvent(event: TimelineEvent): string | null {
  if (event.importance !== 'LANDMARK') return null;
  return BADGE_LABELS[event.eventType] ?? null;
}
