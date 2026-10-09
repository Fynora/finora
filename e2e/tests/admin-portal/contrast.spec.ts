import { test, expect } from '../../fixtures/test';
import { FIVE_ROW_STATEMENT } from '../../fixtures/statements';
import { contrastFailures, useTheme, type Theme } from '../../fixtures/contrast';

/**
 * Every operator screen of the admin portal, in both themes, holds its text to WCAG AA -- measured
 * on the rendered page (see fixtures/contrast.ts). A user with an imported statement exists first,
 * so the user, merchant and import screens render rows rather than only empty states.
 */
const ROUTES = [
  '/', '/users', '/roles', '/banks', '/merchants', '/merchant-templates', '/trusted-senders', '/rules',
  '/learning', '/merchant-review', '/learning-queue', '/notifications', '/push-campaigns',
  '/held-imports', '/held-statements', '/trust-review-metrics', '/support-tickets', '/feedback',
  '/reconciliation', '/reconciliation-explorer', '/insights-explorer', '/analytics', '/subscriptions',
  '/referrals', '/audit', '/health', '/integrations', '/diagnostics', '/layout-intelligence',
  '/layout-studio', '/import-trace', '/import-row-trace', '/settings',
];
const THEMES: Theme[] = ['light', 'dark'];

// Platform-wide screens, like the other admin specs: serial so a neighbour's rows do not shift.
test.describe.configure({ mode: 'serial' });

test('every operator screen meets WCAG AA text contrast in both themes', async ({ adminPage, api, allowConsoleErrors }) => {
  test.setTimeout(300_000);
  // Settings asks for the operator's MFA status; with admin MFA off (as on this stack) the backend
  // answers AUTH_MFA_NOT_AVAILABLE as a 404 and MfaSection shows "not turned on", by design.
  allowConsoleErrors('admin MFA is off on the test stack, so /admin-mfa/status is a 404 by design');
  await api.importStatement(FIVE_ROW_STATEMENT, { accountName: 'Primary' });
  const failures: string[] = [];
  for (const route of ROUTES) {
    await adminPage.goto(route);
    for (const theme of THEMES) {
      await useTheme(adminPage, theme);
      failures.push(...await contrastFailures(adminPage, route, theme));
    }
  }
  expect(failures).toEqual([]);
});
