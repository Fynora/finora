import { isPlanVisible, paidMembershipName, visiblePlanCode, visiblePlanName } from './planDisplay';

const mockPremium = { visible: false };
jest.mock('./premiumVisibility', () => ({
  get PREMIUM_PLAN_VISIBLE() {
    return mockPremium.visible;
  },
}));

describe('planDisplay while Premium is hidden', () => {
  beforeEach(() => {
    mockPremium.visible = false;
  });

  it('shows a Premium holder as Plus, and leaves every other plan alone', () => {
    expect(visiblePlanCode('PREMIUM')).toBe('PLUS');
    expect(visiblePlanCode('PLUS')).toBe('PLUS');
    expect(visiblePlanCode('FREE')).toBe('FREE');
    expect(visiblePlanCode(null)).toBeNull();
    expect(visiblePlanCode(undefined)).toBeUndefined();
    expect(visiblePlanName('PREMIUM', 'Premium')).toBe('Plus');
    expect(visiblePlanName('FREE', 'Free')).toBe('Free');
  });

  it('does not offer Premium as a purchasable option, in any case', () => {
    expect(isPlanVisible('PREMIUM')).toBe(false);
    expect(isPlanVisible('premium')).toBe(false);
    expect(isPlanVisible('PLUS')).toBe(true);
  });

  it('calls the paid membership Plus in generic copy', () => {
    expect(paidMembershipName()).toBe('Plus');
  });
});

describe('planDisplay once Premium is brought back', () => {
  it('shows Premium as itself again', () => {
    mockPremium.visible = true;
    expect(visiblePlanCode('PREMIUM')).toBe('PREMIUM');
    expect(visiblePlanName('PREMIUM', 'Premium')).toBe('Premium');
    expect(isPlanVisible('PREMIUM')).toBe(true);
    expect(paidMembershipName()).toBe('Premium');
  });
});
