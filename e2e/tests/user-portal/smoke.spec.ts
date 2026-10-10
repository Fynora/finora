import { expect, test } from '@playwright/test';

/**
 * User portal smoke tests.
 *
 * Deliberately limited to the public, unauthenticated surface. Everything past the login wall
 * needs a running backend, a migrated database and a seeded user -- real fixtures that are a
 * separate decision from installing the harness, and inventing them here would mean shipping
 * tests that cannot pass on a clean checkout. What these do prove is the part a component test
 * cannot: the app builds, boots, serves, routes and renders in an actual browser.
 */
test.describe('public pages', () => {
  test('the landing page renders and offers a route to register', async ({ page }) => {
    await page.goto('/');

    await expect(page).toHaveTitle(/Fynora/);

    // Located by href rather than by link text: the CTA's wording is marketing copy that changes
    // (this audit already corrected several such strings), but the route it points at is the
    // actual behaviour worth asserting. Marketing CTAs route through the unified /auth entry
    // screen rather than /register directly.
    await expect(page.locator('a[href="/auth"]').first()).toBeVisible();
  });

  test('the register step opens with its Terms and Privacy links safely configured', async ({ page }) => {
    // /register redirects to /auth's identify step; an identifier with no account reaches the
    // register step, which is where these Terms/Privacy links actually live now.
    await page.goto('/register');
    await page.getByLabel(/email|phone/i).first().fill(`new-${Date.now()}@example.com`);
    await page.getByRole('button', { name: /continue/i }).click();

    // Regression guard for the reverse-tabnabbing fix: these two links open in a new tab, and a
    // new tab opened without rel=noopener keeps a live window.opener handle back to the
    // registration form. An ESLint rule blocks the pattern at author time; this asserts the
    // shipped, rendered DOM actually carries the attribute a browser will act on.
    const newTabLinks = page.locator('a[target="_blank"]');

    // `locator.count()` is a one-shot DOM query with no auto-waiting, unlike the `expect(locator)`
    // matchers. Calling it directly raced React's first render: under parallel workers it sampled
    // an empty DOM, counted zero links, and the loop below then asserted nothing at all while the
    // test still reported green. Waiting for the first link to be visible anchors the count to a
    // rendered page and makes the assertion deterministic.
    await expect(newTabLinks.first()).toBeVisible();

    const count = await newTabLinks.count();
    expect(count).toBeGreaterThan(0);

    for (let i = 0; i < count; i++) {
      await expect(newTabLinks.nth(i)).toHaveAttribute('rel', /noopener/);
    }
  });

  test('an unknown route shows the not-found page, keeps its URL and asks not to be indexed', async ({ page }) => {
    await page.goto('/this-route-does-not-exist');

    // Asserted by role and the h1 the page is named by, not by its body copy. It used to be a
    // redirect to the homepage, which a crawler reads as a soft 404; the URL staying put and the
    // robots meta are what make it a real not-found page.
    await expect(page.getByRole('heading', { level: 1, name: 'Page not found' })).toBeVisible();
    await expect(page).toHaveURL(/\/this-route-does-not-exist$/);
    await expect(page.locator('meta[name="robots"]')).toHaveAttribute('content', /noindex/);
  });
});
