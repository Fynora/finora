import { PREMIUM_PLAN_VISIBLE } from './premiumVisibility';

/**
 * The plan code a person should SEE. While Premium is hidden, anyone on Premium (bought it, or
 * earned it through referrals) is shown as Plus, because Premium adds nothing over Plus. Only the
 * display changes: entitlements, checkout and billing logic keep using the real code.
 */
export function visiblePlanCode<T extends string | null | undefined>(code: T): T | 'PLUS' {
  return code === 'PREMIUM' && !PREMIUM_PLAN_VISIBLE ? 'PLUS' : code;
}

/** The plan name a person should see, given the real code and the name the backend sent. */
export function visiblePlanName(planCode: string | null | undefined, planName: string): string {
  return planCode === 'PREMIUM' && !PREMIUM_PLAN_VISIBLE ? 'Plus' : planName;
}

/**
 * What to call the paid membership in generic copy ("Upgrade to ...", "... features turn off").
 * Billing.tsx used "Premium" for any paid tier, which named a hidden plan to Free users and to
 * people who hold Plus.
 */
export function paidMembershipName(): string {
  return PREMIUM_PLAN_VISIBLE ? 'Premium' : 'Plus';
}

/** Whether a plan id ('free' | 'plus' | 'premium', any case) may be shown as a purchasable card. */
export function isPlanVisible(planId: string): boolean {
  return planId.toLowerCase() !== 'premium' || PREMIUM_PLAN_VISIBLE;
}
