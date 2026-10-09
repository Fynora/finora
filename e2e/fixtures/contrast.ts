import type { Page } from '@playwright/test';

/**
 * Text contrast, measured by axe-core in the real browser on the real rendered page.
 *
 * The apps' own Vitest guards (tailwindColorClasses.test.ts) resolve class names to token values
 * statically, which is fast and catches most mistakes, but they cannot see a colour that reaches an
 * element from another component file, a background set at runtime, or anything a stylesheet does
 * outside Tailwind. axe reads the computed colours the browser actually paints, so this is the check
 * those guards cannot be.
 */
const AXE = require.resolve('axe-core/axe.min.js');

export type Theme = 'light' | 'dark';

interface AxeNode {
  target: string[];
  html: string;
  any: { data?: { fgColor?: string; bgColor?: string; contrastRatio?: number; expectedContrastRatio?: string } }[];
}

/** Switches the OS-level colour scheme. Both apps default to following it ("system"). */
export async function useTheme(page: Page, theme: Theme) {
  await page.emulateMedia({ colorScheme: theme, reducedMotion: 'reduce' });
}

/**
 * Every text node on the page under WCAG AA, one line each:
 * `<label> [<theme>] <selector>: <fg> on <bg> is <ratio>:1, needs <n>:1 -- "<html>"`.
 */
export async function contrastFailures(page: Page, label: string, theme: Theme): Promise<string[]> {
  // Sections that reveal on scroll (Landing's `.reveal`) stay at opacity 0 until they enter the
  // viewport, and axe does not measure invisible text -- so walk the page to the bottom first.
  await page.waitForLoadState('networkidle');
  await page.evaluate(async () => {
    for (let y = 0; y < document.documentElement.scrollHeight; y += window.innerHeight / 2) {
      window.scrollTo(0, y);
      await new Promise((resolve) => setTimeout(resolve, 60));
    }
    window.scrollTo(0, 0);
  });
  // Entrance animations (fade-ins) would otherwise be measured mid-fade, at partial opacity.
  await page.waitForTimeout(800);
  // Both apps theme by toggling `dark` on <html>; a scan of the wrong theme would pass vacuously.
  const dark = await page.evaluate(() => document.documentElement.classList.contains('dark'));
  if (dark !== (theme === 'dark')) return [`${label} [${theme}] the ${theme} theme did not apply`];
  if (!(await page.evaluate(() => 'axe' in window))) await page.addScriptTag({ path: AXE });
  const { violations, measured } = await page.evaluate(async () => {
    type Result = { violations: { nodes: unknown[] }[]; passes: { nodes: unknown[] }[] };
    const axe = (window as unknown as { axe: { run: (c: object, o: object) => Promise<Result> } }).axe;
    // Inside role="img" (Landing's product mock) the text is part of a picture, described by the
    // element's label -- WCAG exempts it from contrast, and ARIA makes those children presentational.
    const context = { include: [['html']], exclude: [['[role="img"]']] };
    const result = await axe.run(context, { runOnly: { type: 'rule', values: ['color-contrast'] } });
    return {
      violations: result.violations.flatMap((v) => v.nodes) as AxeNode[],
      measured: [...result.passes, ...result.violations].reduce((n, r) => n + r.nodes.length, 0),
    };
  });
  // A screen with no measured text at all is a scan of an error page or a blank render, not a pass.
  if (measured === 0) return [`${label} [${theme}] no text was measured`];
  return violations.map((node) => {
    const data = node.any[0]?.data ?? {};
    const html = node.html.replace(/\s+/g, ' ').slice(0, 140);
    return `${label} [${theme}] ${node.target.join(' ')}: ${data.fgColor} on ${data.bgColor} is ${data.contrastRatio}:1, `
      + `needs ${data.expectedContrastRatio} -- ${html}`;
  });
}
