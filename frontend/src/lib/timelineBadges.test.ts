import { describe, expect, it } from 'vitest';
import { badgeForEvent } from './timelineBadges';
import type { TimelineEvent } from '../types';

function eventOf(eventType: string, importance: TimelineEvent['importance'] = 'LANDMARK'): TimelineEvent {
  return { eventType, bucket: 'TRANSFORMATION', importance, permanent: true, title: 't', detail: null, occurredAt: '2026-01-01T00:00:00Z' };
}

describe('badgeForEvent', () => {
  it('returns a badge label for a known Landmark event type', () => {
    expect(badgeForEvent(eventOf('GOAL_COMPLETED'))).toBe('Goal Achiever');
  });

  it('returns null for a Minor/Major event type with no badge defined', () => {
    expect(badgeForEvent(eventOf('GOAL_PROGRESS_50', 'MINOR'))).toBeNull();
  });

  it('returns null for an unknown event type', () => {
    expect(badgeForEvent(eventOf('SOMETHING_NEW'))).toBeNull();
  });
});
