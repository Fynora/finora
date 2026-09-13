/**
 * The same palette the web app defines as CSS custom properties in frontend/src/index.css, lifted
 * into plain objects (React Native has no CSS variables). Both modes are here because the app
 * honors the system setting by default and offers a manual override -- screens read colors through
 * useTheme() rather than importing one palette directly, so neither costs anything per screen.
 *
 * The web stores --color-ink/--color-primary as unitless "R G B" triplets purely so Tailwind can
 * apply opacity modifiers to them; that's a Tailwind implementation detail, so they're plain hex
 * here like every other color.
 *
 * Split out of theme/index.ts when the manual override landed: ThemeContext needs these values,
 * and theme/index.ts re-exports ThemeContext's hooks, so leaving them together made a cycle.
 * Nothing imports this file directly -- `../theme` is still the one entry point.
 */
export const light = {
  bg: '#F8FAFC',
  card: '#ffffff',
  border: '#E6EAF2',
  ink: '#0F172A',
  muted: '#64748B',
  // `muted` (#64748B on this screen's #F8FAFC background) sits at ~4.55:1 -- just over WCAG AA's
  // 4.5:1 floor for the 11-13pt sizes it's used at (transaction dates, hints, goal metadata), with
  // almost no margin for a darker background variant or a slightly-off display. Same shape of
  // problem as `warningInk` below, and the same fix: a separate token rather than a change to
  // `muted` itself, since that value is shared with frontend/src/index.css's --color-muted and is
  // fine in the roles it's actually used for there. This slate-600 clears 7.25:1 on the same
  // background -- real margin, not just over the line.
  mutedInk: '#475569',
  primary: '#262A33',
  primaryDark: '#15171C',
  primaryLight: '#F4F1EC',
  // Text/icon color for anything drawn ON a primary-filled surface. Was safe to hardcode '#fff'
  // at every call site while primary was always a mid-to-dark blue in both themes; now that dark
  // mode's primary is light paper, white text on it is nearly invisible, so this has to flip
  // opposite to primary itself -- see frontend/src/index.css's --color-on-primary for the web
  // equivalent of the same problem.
  onPrimary: '#FFFFFF',
  success: '#16a34a',
  successBg: '#dcfce7',
  // `success` on `successBg` sits at ~3.00:1 -- under WCAG AA's 4.5:1 floor, the same shape of
  // problem warningInk exists to fix just below. This green-800 clears ~6.49:1 on the same
  // ground -- real margin, not just over the line. Needed for OfflineBanner's transient
  // "back online" state.
  successInk: '#166534',
  danger: '#dc2626',
  dangerBg: '#fee2e2',
  warning: '#d97706',
  warningBg: '#fef3c7',
  // The shared `warning` tone is tuned for icons and borders; as text on `warningBg` it only
  // reaches 2.86:1, well under WCAG AA's 4.5:1. This darker amber hits 6.37:1 on the same ground.
  // A separate token rather than a change to `warning` itself, since that value is shared with
  // the web app and is fine in the roles it's actually used for there.
  warningInk: '#92400e',
  inputBg: '#FFFFFF',
  // Passbook redesign's one new accent -- Financial Health Seal frame, goal progress rings,
  // Financial Note accent only. See docs/superpowers/specs/2026-09-10-dashboard-passbook-redesign-design.md.
  // `brass` is for STROKES/decorative use only (arc frame, ring, icon tint) -- as text it only
  // reaches 3.24:1 on `card` and 2.74:1 on `brassBg`, both under WCAG AA's 4.5:1. Same shape of
  // problem `warningInk`/`successInk` exist to fix above; `brassInk` is the text-safe token,
  // same pattern. This amber-brown clears 6.35:1 on `card` and 5.37:1 on `brassBg` -- real
  // margin, not just over the line.
  brass: '#B8862E',
  brassBg: '#F5EBD8',
  brassInk: '#7A5A1E',
};

export const dark: typeof light = {
  // Phase 4 (frontend/src/index.css, PR #1391) moved web's dark theme off cool-navy onto a warm
  // near-black/graphite family; these four (plus inputBg below) were never migrated and stayed on
  // the old navy values ('#0B1220'/'#151C2C'/'#253044'/'#E2E8F0') even though dark.primary/
  // onPrimary already had. Matched to web's --color-bg/--color-card/--color-border/--color-ink at
  // the time (PR #1423) -- then web's PR #1425 moved bg/card/border again, onto a lighter
  // warm-graphite family, while leaving ink unchanged. Re-matched here to the current values so
  // mobile and web dark mode share one palette again; ink stays '#EDEDEA' since #1425 never
  // touched it. (Web's #1425 also added a --color-surface token; mobile has no equivalent to
  // migrate -- it's never had one.)
  bg: '#15171C',
  card: '#262A33',
  border: '#414757',
  ink: '#EDEDEA',
  muted: '#98968F',
  // Still clears AA comfortably against the new bg/card (6.06:1 / 4.85:1), so this stays the same
  // value as `muted` -- same reasoning as dark.warningInk below.
  mutedInk: '#98968F',
  primary: '#F4F1EC',
  primaryDark: '#DAD5C9',
  primaryLight: '#26241F',
  onPrimary: '#15171C',
  success: '#22c55e',
  // successBg/dangerBg/warningBg below were darkened alongside bg/card/border above, mirroring
  // web's own success-bg/danger-bg/warning-bg fix in PR #1425: each is a colored wash behind a
  // same-hue text/icon color (successBg+success(Ink)/dangerBg+danger/warningBg+warning(Ink),
  // used throughout Dashboard/Settings/Ledger/Import/Gmail), and `card` getting lighter closed
  // the gap these washes need against the card sitting behind them (contrast dropped to
  // ~1.00-1.12, all three barely visible). As with web, lightening was not the fix -- darkening
  // improves the wash-vs-card separation (all three back to ~1.30) *and* every text-on-wash
  // pairing at the same time (worst case danger 5.84->6.76), since the text tokens themselves
  // are unchanged and darkening only moves the wash further from them.
  //
  // brassBg (below, near `brass`) got the same same-hue darkening for palette consistency, but
  // for a different, checked reason: its only consumer is HealthHero's scorePlate/deltaPill,
  // which sit on `primaryDark` (a light cream, untouched by this migration), never on `card` --
  // so it never had the wash-vs-card problem above. Confirmed the darkening is still a strict
  // improvement there too, not just a no-op: wash-vs-primaryDark 10.14->12.78, brassInk-on-
  // brassBg 6.20->7.83, and healthColor()'s success/primary/warningInk/danger text (scorePlate
  // renders those directly on brassBg, not just brassInk) 8.21/16.61/11.22/6.77 -- all improved.
  successBg: '#08150E',
  // Dark theme's success already clears AA comfortably on successBg (8.20:1), so this is the
  // same value as success -- same reasoning as dark.warningInk/dark.mutedInk above.
  successInk: '#22c55e',
  danger: '#f87171',
  dangerBg: '#210C0E',
  warning: '#fbbf24',
  warningBg: '#181104',
  // Dark theme already clears AA comfortably (11.22:1), so this is the same value as `warning`.
  warningInk: '#fbbf24',
  inputBg: '#15171C',
  // Dark theme's brass already clears AA comfortably as text too (6.35-7.83:1 across every
  // surface it's used on), so brassInk is the same value as brass -- same reasoning as
  // dark.successInk/dark.warningInk above.
  brass: '#C9A254',
  brassBg: '#151208',
  brassInk: '#C9A254',
};

export type Palette = typeof light;

export const radius = {
  md: 8,
  lg: 12,
  xl: 16,
};

export const spacing = {
  xs: 4,
  sm: 8,
  md: 16,
  lg: 24,
  xl: 32,
};
