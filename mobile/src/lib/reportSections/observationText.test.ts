import type { InsightsData } from '../../api/endpoints';
import { buildObservations, SIGNIFICANT_MOVE_PCT } from './observationText';

const rupees = (n: number) => `₹${Math.round(n).toLocaleString('en-IN')}`;
const mover = (category: string, pctChange: number | null, current = 1000, priorAverage = 800) =>
  ({ category, current, priorAverage, pctChange });
// Only what the builder reads; the other InsightsData fields are not its business.
const insights = (movers: ReturnType<typeof mover>[]) => ({ sentences: [], movers }) as unknown as InsightsData;
const build = (i: InsightsData | null) => buildObservations(i, 'September', rupees).map((o) => o.text);

describe('buildObservations', () => {
  it('leads with the biggest move against the recent average, with both amounts', () => {
    const out = build(insights([mover('Food', 20, 1200, 1000), mover('Shopping', -40, 600, 1000)]));
    expect(out[0]).toBe('Shopping is 40% below your recent average (₹600 against ₹1,000).');
    expect(out[1]).toBe('Food is 20% above your recent average (₹1,200 against ₹1,000).');
  });

  it('says only the two biggest moves', () => {
    const out = build(insights([mover('A', 50), mover('B', 40), mover('C', 30), mover('D', 20)]));
    expect(out).toHaveLength(2);
    expect(out[0]).toContain('A is 50%');
  });

  it('ignores a move below the server threshold, and includes one exactly at it', () => {
    expect(build(insights([mover('Food', SIGNIFICANT_MOVE_PCT - 0.1)]))).toEqual([]);
    expect(build(insights([mover('Food', SIGNIFICANT_MOVE_PCT)]))).toHaveLength(1);
    expect(build(insights([mover('Food', -SIGNIFICANT_MOVE_PCT)]))[0]).toContain('below');
  });

  it('names a category that is new, only when there is history to be new against', () => {
    expect(build(insights([mover('Food', 2), mover('Gym', null, 900, 0)])))
      .toEqual(['New: Gym, ₹900 in September, with nothing in the months before.']);
    expect(build(insights([mover('Food', null, 500, 0), mover('Gym', null, 900, 0)]))).toEqual([]);
  });

  it('picks the largest new category when several are new', () => {
    const out = build(insights([mover('Food', 1), mover('Gym', null, 300, 0), mover('Trip', null, 900, 0)]));
    expect(out).toEqual(['New: Trip, ₹900 in September, with nothing in the months before.']);
  });

  it('leaves out the catch-all Uncategorized bucket, as a move or as new', () => {
    expect(build(insights([mover('Uncategorized', 80), mover('Food', 1)]))).toEqual([]);
    expect(build(insights([mover('Food', 1), mover('uncategorized', null, 900, 0)]))).toEqual([]);
  });

  it('puts a move before the new-category note', () => {
    const out = build(insights([mover('Food', 30), mover('Gym', null, 900, 0)]));
    expect(out[0]).toContain('Food is 30%');
    expect(out[1]).toContain('New: Gym');
  });

  it('is empty with no insights, no movers or a missing movers list', () => {
    expect(build(null)).toEqual([]);
    expect(build(insights([]))).toEqual([]);
    expect(buildObservations({ sentences: [] } as unknown as InsightsData, 'September', rupees)).toEqual([]);
  });

  it('ignores a non-finite change instead of printing NaN', () => {
    expect(build(insights([mover('Food', Number.NaN), mover('Shopping', 1)]))).toEqual([]);
  });
});
