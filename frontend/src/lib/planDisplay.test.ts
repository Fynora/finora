import { describe, expect, it, vi } from 'vitest';

const visibility = vi.hoisted(() => ({ premium: false }));
vi.mock('./premiumVisibility', () => ({
  get PREMIUM_PLAN_VISIBLE() {
    return visibility.premium;
  },
}));

import { isPlanVisible, visiblePlanCode, visiblePlanName } from './planDisplay';

describe('planDisplay while Premium is hidden', () => {
  it('shows a Premium holder as Plus, and leaves every other plan alone', () => {
    visibility.premium = false;
    expect(visiblePlanCode('PREMIUM')).toBe('PLUS');
    expect(visiblePlanCode('PLUS')).toBe('PLUS');
    expect(visiblePlanCode('FREE')).toBe('FREE');
    expect(visiblePlanCode(null)).toBeNull();
    expect(visiblePlanCode(undefined)).toBeUndefined();
    expect(visiblePlanName('PREMIUM', 'Premium')).toBe('Plus');
    expect(visiblePlanName('PLUS', 'Plus')).toBe('Plus');
    expect(visiblePlanName('FREE', 'Free')).toBe('Free');
  });

  it('does not offer Premium as a card, in any case', () => {
    visibility.premium = false;
    expect(isPlanVisible('premium')).toBe(false);
    expect(isPlanVisible('PREMIUM')).toBe(false);
    expect(isPlanVisible('free')).toBe(true);
    expect(isPlanVisible('plus')).toBe(true);
  });
});

describe('planDisplay once Premium is brought back', () => {
  it('shows Premium as itself again', () => {
    visibility.premium = true;
    expect(visiblePlanCode('PREMIUM')).toBe('PREMIUM');
    expect(visiblePlanName('PREMIUM', 'Premium')).toBe('Premium');
    expect(isPlanVisible('premium')).toBe(true);
  });
});
