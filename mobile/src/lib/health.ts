import type { Palette } from '../theme';

/**
 * Track C/C1's original helper, moved out of DashboardScreen.tsx so HealthHero/HealthFactorsRow/
 * AIInsightCard can share it without importing the screen component (which would import them
 * back -- a cycle). Same 80/60/40 cutoffs and label vocabulary as
 * frontend/src/pages/Dashboard.tsx's identical helper.
 *
 * `warningInk`, not `warning`: `warning` is tuned for icons/borders/bars and falls under WCAG AA
 * as plain text on this screen's background -- warningInk is the token built for exactly that
 * (see theme/palette.ts's own comment).
 */
export function healthColor(label: string, c: Palette): string {
  switch (label) {
    case 'Excellent': return c.success;
    case 'Good': return c.primary;
    case 'Fair': return c.warningInk;
    default: return c.danger;
  }
}

/**
 * Same cutoffs as healthColor, applied to one breakdown row's own score -- so a perfect
 * sub-score doesn't inherit the overall label's color. A bar/track fill, not text, so `warning`
 * itself (not `warningInk`) is the right token here.
 */
export function healthBarColor(score: number, c: Palette): string {
  if (score >= 80) return c.success;
  if (score >= 60) return c.primary;
  if (score >= 40) return c.warning;
  return c.danger;
}

/** Background token to pair with healthBarColor's foreground, for a factor card's tone pill. */
export function healthToneBg(score: number, c: Palette): string {
  if (score >= 80) return c.successBg;
  if (score >= 60) return c.primaryLight;
  if (score >= 40) return c.warningBg;
  return c.dangerBg;
}

/** Same 0-100 scale and vocabulary as healthColor -- Categorization Confidence reuses it too. */
export function scoreLabel(score: number): string {
  if (score >= 80) return 'Excellent';
  if (score >= 60) return 'Good';
  if (score >= 40) return 'Fair';
  return 'Needs Attention';
}

/**
 * Ported verbatim from frontend/src/pages/Dashboard.tsx's identical helper -- same factor names
 * and copy, so a breakdown factor reads the same suggestion on both platforms.
 */
export function healthImprovementSuggestion(factor: string, score: number): string {
  const good = score >= 80;
  switch (factor) {
    case 'Savings Rate':
      return good ? "You're saving well — keep it up." : 'Aim to save at least 24% of your income each month.';
    case 'Debt Score':
      return good ? "You're managing debt well." : 'Pay down credit card balances to bring utilization under 20%.';
    case 'Emergency Fund':
      return good ? 'You have a solid safety net.' : 'Build your emergency fund toward 4-5 months of expenses.';
    case 'Spend Consistency':
      return good ? 'Your spending has been consistent.' : 'Try to keep monthly spending within about 20% of your average.';
    case 'Cash Flow Stability':
      return good ? 'Your cash flow has been stable.' : 'Work toward income meeting or exceeding expenses most months.';
    default:
      return '';
  }
}
