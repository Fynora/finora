import { badgeForEvent, groupByYear, mostRecentHighlight } from './timeline';
import type { TimelineEvent } from '../types';

function event(overrides: Partial<TimelineEvent> = {}): TimelineEvent {
  return {
    eventType: 'FIRST_IMPORT', bucket: 'STARTING', importance: 'MINOR', permanent: true,
    title: 'Imported your first statement', detail: null, occurredAt: '2026-03-10T09:00:00Z', ...overrides,
  };
}

describe('badgeForEvent', () => {
  it('labels a Landmark event of a known type', () => {
    expect(badgeForEvent(event({ eventType: 'GOAL_COMPLETED', importance: 'LANDMARK' }))).toBe('Goal Achiever');
    expect(badgeForEvent(event({ eventType: 'NET_WORTH_10K', importance: 'LANDMARK' }))).toBe('First ₹10K Saved');
    expect(badgeForEvent(event({ eventType: 'NET_WORTH_100K', importance: 'LANDMARK' }))).toBe('Six-Figure Saver');
  });

  it('gives no badge to a Landmark event of an unknown type', () => {
    expect(badgeForEvent(event({ eventType: 'SOMETHING_NEW', importance: 'LANDMARK' }))).toBeNull();
  });

  it('gives no badge below Landmark, even for a badge-worthy type', () => {
    expect(badgeForEvent(event({ eventType: 'GOAL_COMPLETED', importance: 'MAJOR' }))).toBeNull();
    expect(badgeForEvent(event({ eventType: 'GOAL_COMPLETED', importance: 'MINOR' }))).toBeNull();
  });
});

describe('mostRecentHighlight', () => {
  it('prefers the first Landmark over a more recent Major', () => {
    const major = event({ title: 'major', importance: 'MAJOR' });
    const landmark = event({ title: 'landmark', importance: 'LANDMARK' });
    expect(mostRecentHighlight([major, landmark])).toBe(landmark);
  });

  it('falls back to the first Major when there is no Landmark', () => {
    const minor = event({ title: 'minor' });
    const major = event({ title: 'major', importance: 'MAJOR' });
    expect(mostRecentHighlight([minor, major])).toBe(major);
  });

  it('returns undefined when every event is Minor, or there are none', () => {
    expect(mostRecentHighlight([event(), event()])).toBeUndefined();
    expect(mostRecentHighlight([])).toBeUndefined();
  });
});

describe('groupByYear', () => {
  it('groups by calendar year, newest year first, keeping arrival order within a year', () => {
    const a = event({ title: 'a', occurredAt: '2026-06-01T12:00:00Z' });
    const b = event({ title: 'b', occurredAt: '2025-06-01T12:00:00Z' });
    const c = event({ title: 'c', occurredAt: '2026-01-15T12:00:00Z' });

    const groups = groupByYear([a, b, c]);

    expect(groups.map(([year]) => year)).toEqual(['2026', '2025']);
    expect(groups[0][1]).toEqual([a, c]);
    expect(groups[1][1]).toEqual([b]);
  });

  it('returns no groups for no events', () => {
    expect(groupByYear([])).toEqual([]);
  });
});
