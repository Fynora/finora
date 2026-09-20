import type { TimelineEvent } from '../types';

// Badges are a display label derived from a Landmark timeline event, never separately tracked
// state. Only Landmark event types appear here; Minor/Major events are timeline texture.
// Same labels as frontend/src/lib/timelineBadges.ts.
const BADGE_LABELS: Record<string, string> = {
  GOAL_COMPLETED: 'Goal Achiever',
  NET_WORTH_10K: 'First ₹10K Saved',
  NET_WORTH_100K: 'Six-Figure Saver',
};

export function badgeForEvent(event: TimelineEvent): string | null {
  if (event.importance !== 'LANDMARK') return null;
  return BADGE_LABELS[event.eventType] ?? null;
}

/** Most recent Landmark event, falling back to the most recent Major one. Minor events never
 *  surface on the dashboard, only in the full timeline. Relies on the API returning events
 *  newest-first, same as the web widget. */
export function mostRecentHighlight(events: TimelineEvent[]): TimelineEvent | undefined {
  return events.find((e) => e.importance === 'LANDMARK') ?? events.find((e) => e.importance === 'MAJOR');
}

/** Newest year first; events keep the order they arrived in within a year. */
export function groupByYear(events: TimelineEvent[]): [string, TimelineEvent[]][] {
  const groups = new Map<string, TimelineEvent[]>();
  for (const e of events) {
    const year = new Date(e.occurredAt).getFullYear().toString();
    groups.set(year, [...(groups.get(year) ?? []), e]);
  }
  return [...groups.entries()].sort((a, b) => b[0].localeCompare(a[0]));
}
