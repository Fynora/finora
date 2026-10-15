import { readFileSync } from 'fs';
import { join } from 'path';

/**
 * Source-scan guard for the floating tab pill (same pattern as src/theme/glassMigration.test.ts).
 * The pill floats over every screen reachable from the tabs, so each one must ask for bottom room
 * with useTabBarSpace(); one that forgets hides its last rows behind the pill. A text check: it
 * proves each screen asks, not that the room is right. The simulator pass checks that.
 */
const SRC = join(__dirname, '..');
const read = (rel: string) => readFileSync(join(SRC, rel), 'utf8');

// Screens a reachable screen shows in its own place, which AppTabs never imports.
const SHOWN_INSIDE: Record<string, string[]> = {
  'screens/SubscriptionScreen.tsx': ['screens/PaywallScreen.tsx', 'screens/MySubscriptionScreen.tsx'],
};

function reachableScreens(): string[] {
  const imported = [...read('navigation/AppTabs.tsx').matchAll(/from '\.\.\/(screens\/[^']+)'/g)].map((m) => `${m[1]}.tsx`);
  return [...new Set(imported.flatMap((f) => [f, ...(SHOWN_INSIDE[f] ?? [])]))].sort();
}

// A vertical scrolling list: the tag, then its props up to the closing `>`, with no `horizontal`.
const VERTICAL_SCROLL = /<(ScrollView|FlatList|SectionList)\b(?![^>]*\bhorizontal\b)/;
const LAYOUT_WITH_ROOM = /<AuthScreenLayout\b/;
const ASKS = /\buseTabBarSpace\(/;

it('finds every screen reachable from the tabs', () => {
  // 34 imported by AppTabs plus Paywall and My Subscription. A drop means the import scan broke.
  expect(reachableScreens()).toHaveLength(36);
});

it('every screen reachable from the tabs that scrolls asks for room under the pill', () => {
  const missing = reachableScreens().filter((f) => {
    const src = read(f);
    return VERTICAL_SCROLL.test(src) && !ASKS.test(src);
  });
  expect(missing).toEqual([]);
});

it('screens that scroll through AuthScreenLayout get the room from the layout itself', () => {
  const throughLayout = reachableScreens().filter((f) => LAYOUT_WITH_ROOM.test(read(f)));
  expect(throughLayout).toEqual(['screens/settings/VerifyEmailChangeScreen.tsx']);
  expect(ASKS.test(read('components/AuthScreenLayout.tsx'))).toBe(true);
});
