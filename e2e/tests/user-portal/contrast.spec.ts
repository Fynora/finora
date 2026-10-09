import { test, expect, uploadStatement } from '../../fixtures/test';
import { csv, FIVE_ROW_STATEMENT } from '../../fixtures/statements';
import { contrastFailures, useTheme, type Theme } from '../../fixtures/contrast';

/**
 * Every signed-in screen of the user app, in both themes, holds its text to WCAG AA -- measured on
 * the rendered page (see fixtures/contrast.ts for why this exists alongside the Vitest guard).
 *
 * The account first imports a real statement, so the ledger, dashboard, reports and badges render
 * rows rather than empty states, and then re-uploads it, so the duplicate-review state of the
 * import screen is on the page too.
 */
const ROUTES = [
  '/app', '/app/financial-memory', '/app/accounts', '/app/transactions', '/app/statements',
  '/app/budgets', '/app/goals', '/app/journey', '/app/wrapped', '/app/investments', '/app/reports',
  '/app/money-review', '/app/reports/advanced', '/app/insights', '/app/profile', '/app/settings',
  '/app/billing', '/app/referrals', '/app/support',
];
const THEMES: Theme[] = ['light', 'dark'];

test('every signed-in screen meets WCAG AA text contrast in both themes', async ({ userPage, api, allowConsoleErrors }) => {
  test.setTimeout(240_000);
  // Insights asks for Fyn's AI narration, which the free plan is refused (403); the page catches
  // it and renders the rule-based insights instead (Insights.tsx), but Chrome still logs the 403.
  allowConsoleErrors('Insights requests AI narration, which a free-plan account is refused by design');
  await api.importStatement(FIVE_ROW_STATEMENT, { accountName: 'Primary' });
  const failures: string[] = [];

  await userPage.goto('/app/import');
  await uploadStatement(userPage, 'statement.csv', 'text/csv', csv(FIVE_ROW_STATEMENT));
  await expect(userPage.getByTestId('duplicate-review')).toBeVisible({ timeout: 20_000 });
  for (const theme of THEMES) {
    await useTheme(userPage, theme);
    failures.push(...await contrastFailures(userPage, '/app/import (duplicate review)', theme));
  }

  for (const route of ROUTES) {
    await userPage.goto(route);
    for (const theme of THEMES) {
      await useTheme(userPage, theme);
      failures.push(...await contrastFailures(userPage, route, theme));
    }
  }

  expect(failures).toEqual([]);
});

const PUBLIC_ROUTES = [
  '/', '/login', '/register', '/forgot-password', '/about', '/help', '/contact', '/careers', '/trust',
  '/your-data', '/terms', '/privacy', '/cookie-policy', '/refund-policy', '/shipping-policy',
];

test('every signed-out page meets WCAG AA text contrast in both themes', async ({ page }) => {
  test.setTimeout(240_000);
  const failures: string[] = [];
  for (const route of PUBLIC_ROUTES) {
    await page.goto(route);
    for (const theme of THEMES) {
      await useTheme(page, theme);
      failures.push(...await contrastFailures(page, route, theme));
    }
  }
  expect(failures).toEqual([]);
});
