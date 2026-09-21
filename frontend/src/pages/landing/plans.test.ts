import { describe, expect, it } from 'vitest';
import { LANDING_COMPARISON, PLANS, PRICING_CARDS } from './plans';

describe('landing plan data', () => {
  it('shows only Free and Plus on the public page, in that order', () => {
    expect(PRICING_CARDS.map((p) => p.id)).toEqual(['free', 'plus']);
  });

  it('keeps all three plans in PLANS, because the in-app Billing page still sells Premium', () => {
    expect(PLANS.map((p) => p.id)).toEqual(['free', 'plus', 'premium']);
  });

  it('lists Gmail receipts under Plus and never under Free', () => {
    const free = PLANS.find((p) => p.id === 'free')!;
    const plus = PLANS.find((p) => p.id === 'plus')!;
    expect(plus.features.join(' ')).toMatch(/Gmail/);
    expect(free.features.join(' ')).not.toMatch(/Gmail/);
  });

  it('never sells extended history or long-term trends as Plus features', () => {
    // The 31-day statement limit is only enforced when a statement carries a detected period, so
    // "extended history" is not a benefit we can stand behind. Plus's real, enforced differences are
    // accounts, statement length, Advanced Reports, Ask Fyn and Gmail.
    const plus = PLANS.find((p) => p.id === 'plus')!;
    expect(plus.features.join(' ')).not.toMatch(/extended (financial )?history|long-term trends/i);
  });

  it('compares Free and Plus row by row with text or a tick', () => {
    expect(LANDING_COMPARISON.length).toBeGreaterThanOrEqual(6);
    for (const row of LANDING_COMPARISON) {
      expect(['boolean', 'string']).toContain(typeof row.free);
      expect(['boolean', 'string']).toContain(typeof row.plus);
    }
    const accounts = LANDING_COMPARISON.find((r) => r.label === 'Accounts');
    expect(accounts).toEqual({ label: 'Accounts', free: 'Up to 2', plus: 'No limit' });
  });

  it('mentions no investment or bank-feed row', () => {
    const text = LANDING_COMPARISON.map((r) => r.label).join(' ');
    expect(text).not.toMatch(/invest|bank sync|bank feed|aggregator/i);
  });
});
