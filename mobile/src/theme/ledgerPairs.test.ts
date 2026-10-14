import { dark, light, type Palette } from './palette';

/**
 * The Transactions screen draws some text on a solid fill rather than on glass: row tags, the
 * selected segment of the type switch, the Filters count, the sheet's selected status and its
 * button, and the active filter chips. Each pair is checked here in both themes. Text that sits on
 * glass uses tokens glassContrast.test.ts already measures against the backdrop.
 */
const channel = (v: number) => { const c = v / 255; return c <= 0.03928 ? c / 12.92 : ((c + 0.055) / 1.055) ** 2.4; };
const rgb = (hex: string) => { const n = parseInt(hex.slice(1), 16); return [(n >> 16) & 255, (n >> 8) & 255, n & 255]; };
const luminance = (hex: string) => { const [r, g, b] = rgb(hex); return 0.2126 * channel(r) + 0.7152 * channel(g) + 0.0722 * channel(b); };
const ratio = (a: string, b: string) => { const [x, y] = [luminance(a), luminance(b)].sort((p, q) => q - p); return (x + 0.05) / (y + 0.05); };

// [text, fill, where it is used]
const PAIRS: [keyof Palette, keyof Palette, string][] = [
  ['warningInk', 'warningBg', 'Needs Review and Reversed tags'],
  ['dangerInk', 'dangerBg', 'Bank Correction and Duplicate tags'],
  ['successInk', 'successBg', 'Refund tag'],
  // `ink`, not `mutedInk`: the dark theme's mutedInk on its border measures 4.42:1, under the line.
  ['ink', 'border', 'neutral tags'],
  ['onPrimary', 'primary', 'selected segment, count badge, selected status, sheet button'],
  ['primary', 'primaryLight', 'active filter chip'],
];

describe.each([['light', light], ['dark', dark]] as const)('%s theme', (_, p) => {
  it.each(PAIRS)('%s on %s clears 4.5:1 (%s)', (text, fill) => {
    expect(ratio(p[text] as string, p[fill] as string)).toBeGreaterThanOrEqual(4.5);
  });
});
