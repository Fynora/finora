# Mobile Glass UI Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.
>
> **Finora overrides:** the repo `CLAUDE.md` wins over any skill default. Work only in a fresh worktree, never in `/Users/sid/Downloads/finora`. No `Co-Authored-By` trailer on any commit. Never call anything verified unless a command you ran produced that result. Run jest under Node 22 as well as local Node before claiming CI parity.

**Goal:** Restyle the whole Fynora mobile app (`mobile/`) as a "soft mesh + glass" UI. A static, low-saturation mesh-gradient backdrop sits behind every screen. Every card, sheet, modal and the tab bar becomes a translucent glass surface. Text keeps WCAG AA (4.5:1) everywhere.

**Architecture:** There are three new pieces, and every screen reaches them through existing seams:
- `GlassScreen` replaces the opaque `backgroundColor: c.bg` root of each screen and paints the mesh PNG behind it.
- `GlassSurface` replaces `c.card` surfaces. `Card` and `DashboardCard` delegate to it, so their 31+ call sites change for free.
- `useReduceTransparency` turns everything back to solid when the OS accessibility setting asks for it.

The work ships in two phases:
- **Phase 1** is JS plus image assets only, so it can ship by OTA. Glass is a tinted translucent fill with no live blur.
- **Phase 2** swaps `GlassSurface`'s internals to real `GlassView` (iOS 26+) or `BlurView` (iOS 16.4–25). Android keeps the Phase 1 tinted fill. Phase 2 needs new native modules, so it ships only in a new store build with a bumped app version.

**Tech Stack:** Expo SDK 57 (`expo ~57.0.20`), React Native 0.86.3, React Navigation 7 (bottom-tabs `^7.18.14`, native-stack `^7.18.6`), jest-expo, `pngjs@7.0.0` (devDependency, generator and test only), `expo-glass-effect@~57.0.4` and `expo-blur@~57.0.3` (Phase 2 only).

**Spec:** No separate spec file. The design was agreed in chat on 2026-10-09 and is recorded in full under "Agreed design" below. Executors read that section first.

---

## Agreed design (decisions made with Sid, 2026-10-09)

| Decision | Choice |
|---|---|
| Scope | Whole app. Every card, sheet, modal and the tab bar becomes glass. |
| Goal | Feel premium and modern. |
| Backdrop | Soft mesh: three large, low-saturation blobs (slate `#94A3B8`, brass `#B8862E`, blue `#60A5FA`) on the theme's `bg`. Static, not animated. |
| Android | Translucent tinted fill with a hairline edge. No live blur. |
| Approach | One `GlassSurface` component with platform paths, shipped in two phases (Phase 1 OTA, Phase 2 store build). |

Facts established during design (each one came from a command or a package's own typings, not from memory):

- `expo-glass-effect@57.0.4` exports `GlassView` (`glassEffectStyle: 'clear' | 'regular' | 'none'`, `tintColor`, `colorScheme: 'auto' | 'light' | 'dark'`, `isInteractive`), plus `GlassContainer`, `isLiquidGlassAvailable()` and `isGlassEffectAPIAvailable()`.
  - Its README says: "This package only supports iOS 26+. On unsupported platforms, it will fallback to a regular `View`."
  - `isGlassEffectAPIAvailable()` exists because some iOS 26 betas crash without the API. Check it before rendering `GlassView`.
  - `isLiquidGlassAvailable()` can return `true` even when the user has turned on Reduce Transparency. The package's own doc comment points to `AccessibilityInfo.isReduceTransparencyEnabled()` for that check.
- `expo-blur@57.0.3` `BlurView` props: `tint` (including `systemThinMaterialLight` and `systemThinMaterialDark`), `intensity`, `blurMethod` and `blurTarget`. Its README says it renders "a native blur view on iOS and falls back to a semi-transparent view on Android".
- RN 0.86's `AccessibilityInfo` has `isReduceTransparencyEnabled(): Promise<boolean>` and the iOS-only event `'reduceTransparencyChanged'`.
- `mobile/app.config.ts` uses `runtimeVersion: { policy: 'appVersion' }`. An OTA reaches every binary of the same app version. So Phase 2 JS (which imports the new native modules) must never be OTA'd to an older binary: Phase 2 bumps the app version.
- The minimum OS is iOS 16.4 and Android 7.0 (memory `mobile-min-os-version-decision`).
- Surfaces today:
  - `Card` is imported in 31 files.
  - 36 non-test files paint `c.card` directly.
  - 47 non-test files paint `backgroundColor: c.bg`. Some of those are not screen roots and must stay opaque (see Task 4).
- `c.bg` is also used as a deliberately opaque colour in places that must not turn transparent:
  - `AppLockGate.tsx:260` and `:329`, the lock cover. This one is security-relevant: it must hide the app.
  - `RootErrorBoundary.tsx:67`.
  - The `StagedRowCard.tsx` badges.
  - The `AccountUI.tsx` tile.
  - `PremiumFeatureGate.tsx`.
  - `BrandMark.tsx:22`, as a glyph colour.
  - The status chips in `SupportTicketsScreen` and `SupportTicketDetailScreen`.

  So **`c.bg` is never changed to transparent.** Screen roots are migrated one file at a time instead.

### Contrast numbers (measured 2026-10-09, worst case over each blob's peak)

These were computed with the WCAG relative-luminance formula used by `mobile/src/theme/palette.test.ts`. Glass is `glassTint` composited at `glassAlpha` over the blob's peak colour.

| Theme | Blob peak opacity (slate / brass / blue) | Glass | Worst result |
|---|---|---|---|
| light | 0.35 / 0.20 / 0.30 | `#FFFFFF` @ 0.72 | ink 16.36, mutedInk 6.94, brassInk 5.81, successInk 6.53, warningInk 6.50, dangerInk 7.61. **`muted` #64748B = 4.36: fails** |
| light, text directly on backdrop | same | none | `muted` 3.43 **fails**; `#475569` 5.46 passes |
| dark | 0.25 / 0.25 / 0.25 | `#262A33` @ 0.72 | ink 11.52, brass 5.65. `muted` #98968F = 4.57 on glass but **3.86 on backdrop: fails** |
| dark, `#B5B3AC` as muted | same | glass / backdrop | 6.44 / 5.45, both pass |

The current `muted` tokens can't stay. Light `muted` becomes `#475569` and dark `muted` becomes `#B5B3AC` (Task 1). The higher blob opacities shown in the in-chat demo (light 0.7, dark 0.55) failed AA. The demo was a visual sketch, and these are the real values.

### Open decision for Sid (not blocking Phase 1)

**D1 — Floating tab bar.** In Phase 1 the tab bar stays in normal layout flow, with a glass surface over the mesh. A true iOS-style bar, where content scrolls *under* it, needs `tabBarStyle: { position: 'absolute' }`. It also needs `useBottomTabBarHeight()` bottom padding added to every scroll container in every tab and in every `MoreStack` screen, about 30 screens. Each missed one hides its last rows behind the bar. That's a separate, sizeable change, so it is deliberately left out of this plan until Sid decides. The tab bar in this plan already reads as glass, but nothing scrolls under it.

### External review (2026-10-09): what changed and why

The review was checked against the code before any change was accepted. The evidence is next to each item.

| Review point | Verdict | Evidence / change |
|---|---|---|
| Task 7 guard rejects the SupportTicket status chips | **Claim incorrect, but a real neighbouring bug was found** | The chips are written `bg: (c) => c.bg` (`SupportTicketsScreen.tsx:29`, `SupportTicketDetailScreen.tsx:26`), which the guard's `backgroundColor:\s*c\.bg` regex never matches. The real problem: **20 files contain more than one `backgroundColor: c.bg`**. Examples: early-return loading and error roots (`SupportTicketDetailScreen.tsx:65,73`), a `ScrollView` root (`:87`), `ReferralsScreen` ×7, `FynScreen` ×6. A whole-file regex can't tell a root from an inner chip. **Change:** the guard is now per line, with an explicit `// glass-exempt: <reason>` marker (Task 7). |
| Reduce Transparency flashes glass before the OS answers, and a late query can overwrite a newer event | **Accepted** | `useState(false)` plus an async query really does both. **Change:** three states (`null` means unknown, which renders solid), primed at boot so the answer usually arrives under the launch animation, and an event always beats a late initial query (Task 2). |
| The contrast test samples every 3rd pixel | **Accepted** | 540×1170×2 themes is cheap to check exhaustively. **Change:** every pixel, and failures name the theme, layer, token, x, y and ratio (Task 3). |
| Make 5:1 a design target | **Not adopted as a gate** | `resizeMode="cover"` interpolates between neighbouring pixels, so a rendered pixel's luminance lies between ones the exhaustive test already checked. Native blur is measured separately on device (Task 12). The measured margins are recorded instead: the lowest in Phase 1 is light `brassInk` at 4.64 on a sheet over the scrim. |
| Sheets over the scrim, inputs and chips aren't covered | **Accepted for the scrim, checked for the rest** | Sheet over `rgba(0,0,0,0.4)` scrim, worst case measured: light ink 13.06, muted 5.54, brassInk 4.64, successInk 5.22, warningInk 5.19, dangerInk 6.08; dark ink 12.59, muted 7.04, brass 6.18. **Change:** the scrim case is added to Task 3's test. Inputs paint opaque `c.inputBg` (24 call sites) and chips paint opaque `*Bg` fills, so glass doesn't change what's under their text. Their only change is `muted` (next row). |
| Changing `muted` globally needs an impact audit | **Accepted and done** | `c.muted` has 388 uses, 16 of them as `placeholderTextColor`. New vs old `muted` on every opaque fill: light bg 4.55→7.24, card 4.76→7.58, primaryLight 4.22→6.73, successBg 4.33→6.90, **dangerBg 3.90→6.20**, warningBg 4.27→6.81, brassBg 4.02→6.41; dark: every fill rises, minimum card 4.85→6.85. Contrast only goes up, and five light fills that failed AA today now pass. **Visual cost:** light `muted` now equals `mutedInk`, so the muted/mutedInk distinction disappears in light mode. That's for Sid to judge in the pilot screenshots (Task 6b). |
| Pilot before the bulk migration | **Accepted** | **Change:** new Task 6b gates Tasks 7 and 8. |
| Lock cover tested in both themes | **Accepted** | Task 4. |
| Wrapped `ScrollView`/`FlatList`/`KeyboardAvoidingView` roots could break layout | **Accepted** | Layout checklist added to Tasks 6b and 7. |
| Seams on other aspect ratios | **Accepted** | Device matrix added to Task 9. |
| The version-differs test isn't a release gate | **Accepted** | Release checklist added to Task 12. |
| Glass may look like a plain translucent card | **Accepted** | Three-variant comparison added to Task 12. |
| Scroll-end test against the tab bar | **Not adopted** | The tab bar stays in layout flow in this plan, so nothing can sit under it. That test belongs to D1 if D1 is ever approved. |
| Screenshot metadata | **Accepted** | Task 9. |

D1: the review agrees with leaving the floating tab bar out. It stays out of this plan unless Sid says otherwise.

---

## Global Constraints

- Work only in a fresh worktree: `git worktree add ../finora-glass-ui -b feature/glass-ui origin/main`. Use full absolute paths for every command.
- Commit messages never carry a `Co-Authored-By` trailer or any AI attribution.
- The repo is private: green PR checks prove nothing. Before calling the PR merge-ready, add the `full-ci` label and an empty commit (see the repo `CLAUDE.md`). Ask Sid before opening a PR (memory `open-prs-without-asking`).
- Every text token that can sit on glass or on the backdrop must clear **4.5:1**, measured against the real PNG pixels (Task 3), not hypothesised.
- `c.bg` keeps its opaque value. The lock cover (`AppLockGate`) must stay fully opaque.
- Reduce Transparency on means no mesh and no translucency anywhere: solid `c.bg` and `c.card`, exactly today's look.
- Phase 1 adds **no native module**. `pngjs` is a devDependency used only by the generator script and a jest test.
- Phase 2 bumps `version` in `mobile/app.config.ts` in the same commit that adds `expo-glass-effect` and `expo-blur`, because of `runtimeVersion.policy: 'appVersion'`.
- Before writing Phase 2 code, read the versioned docs at https://docs.expo.dev/versions/v57.0.0/sdk/glass-effect/ and https://docs.expo.dev/versions/v57.0.0/sdk/blur-view/ (`mobile/AGENTS.md` requires this).
- Commands, all run from `/Users/sid/Downloads/finora-glass-ui/mobile`:
  - typecheck: `npm run typecheck`
  - lint: `npm run lint`
  - tests: `npm test -- <path>`
  - CI-parity tests: `NODE_OPTIONS=--experimental-vm-modules npx --yes node@22 ./node_modules/jest/bin/jest.js --forceExit <path>`

## Review Focus

These are the five failure modes most likely to bite a real user that no ordinary unit test catches. Each one has its pinning test or check in the named task.

1. **Lock screen leaks app content.** If any change makes `AppLockGate`'s cover translucent, private balances show through the lock. Pinned in Task 4 (a test asserting the cover's background is the opaque `c.bg`).
2. **Unreadable text on a lighter or darker patch of mesh.** The worst pixel decides, not the average. Pinned in Task 3 (a pixel-level contrast test over the real PNGs).
3. **Reduce Transparency ignored or flashed.** Users who turned it on get glass anyway, or see glass for a frame. Pinned in Task 2 (unknown state is solid, an event beats a late query) and Tasks 4 and 5 (`null` and `true` both render solid).
4. **Visible seam where the tab bar meets the screen** or between pushed screens, because each `GlassScreen` scales the mesh to its own size. Pinned in Task 3 (the edge band of the PNG must equal `bg`, so every crop meets flat colour at its edges).
5. **Phase 2 JS reaching an old binary by OTA** and crashing on a missing native view. Pinned in Task 10 (version bump plus a test asserting `app.config.ts`'s version differs from `origin/main`'s).

---

## File structure

| File | Status | Responsibility |
|---|---|---|
| `mobile/src/theme/palette.ts` | modify | Add `glassTint`, `glassAlpha`, `glassEdge` to `light` and `dark`; change `muted` (and dark `mutedInk`) |
| `mobile/src/theme/glass.ts` | create | `withAlpha(hex, alpha)` and `glassFill(palette)`, pure helpers |
| `mobile/assets/glass/mesh.json` | create | Single source of truth for blob colours, positions, radii and peak opacities per theme |
| `mobile/scripts/generate-glass-mesh.mjs` | create | Deterministic PNG generator that reads `mesh.json` |
| `mobile/assets/glass/mesh-light.png`, `mesh-dark.png` | generated, committed | Backdrops |
| `mobile/src/theme/glassContrast.test.ts` | create | Pixel-level AA test over the committed PNGs |
| `mobile/src/lib/useReduceTransparency.ts` (+ test) | create | OS Reduce Transparency setting, live |
| `mobile/src/components/GlassScreen.tsx` (+ test) | create | Screen root: opaque `c.bg` plus mesh image |
| `mobile/src/components/GlassSurface.tsx` (+ test) | create | Glass panel or row; Phase 2 platform switch lives here only |
| `mobile/src/components/Card.tsx`, `dashboard/DashboardCard.tsx` | modify | Delegate to `GlassSurface` |
| `mobile/src/navigation/AppTabs.tsx`, `RootNavigator.tsx` | modify | Glass tab bar, mesh at the tabs root |
| Screen-root files (Task 7 list) | modify | `View` + `c.bg` becomes `GlassScreen` |
| Raw `c.card` files (Task 8 list) | modify | Become `GlassSurface` |
| `mobile/src/theme/glassMigration.test.ts` | create | Source-scan guard against regressions |

Hooks live in `mobile/src/lib/` (for example `useLargeFontScale.ts` and `useTransientFlag.ts`). There is no `src/hooks/` directory, checked 2026-10-09.

---

# PHASE 1 — JS + assets (OTA-able)

### Task 0: Worktree and plan commit

**Files:** Create `docs/superpowers/plans/2026-10-09-mobile-glass-ui.md` (this file).

- [ ] **Step 1: Create the worktree**

```bash
git -C /Users/sid/Downloads/finora fetch origin
git -C /Users/sid/Downloads/finora worktree add ../finora-glass-ui -b feature/glass-ui origin/main
```

- [ ] **Step 2: Verify location**

```bash
cd /Users/sid/Downloads/finora-glass-ui && pwd && git branch --show-current && git status --short
```

Expected: the path ends in `finora-glass-ui`, the branch is `feature/glass-ui`, and the status is empty.

- [ ] **Step 3: Install deps.** Run `npm ci` in `/Users/sid/Downloads/finora-glass-ui/mobile`. Copy the gitignored Firebase config only if a prebuild is needed later (memory `mobile-worktree-firebase-config-gitignored`).

- [ ] **Step 4: Copy this plan into `docs/superpowers/plans/` and commit it**

```bash
git -C /Users/sid/Downloads/finora-glass-ui add docs/superpowers/plans/2026-10-09-mobile-glass-ui.md
git -C /Users/sid/Downloads/finora-glass-ui commit -m "docs(mobile): glass UI implementation plan"
```

---

### Task 1: Glass tokens and AA-safe `muted`

**Files:**
- Modify: `mobile/src/theme/palette.ts` (`light` around lines 16–80, `dark` around lines 83–155)
- Create: `mobile/src/theme/glass.ts`
- Modify: `mobile/src/theme/palette.test.ts`

**Interfaces:**
- Produces: `Palette` gains `glassTint: string`, `glassAlpha: number` and `glassEdge: string`. `glass.ts` exports `withAlpha(hex: string, alpha: number): string` (returns `rgba(r,g,b,a)`) and `glassFill(p: Palette): string`.

- [ ] **Step 1: Write the failing tests** (append to `palette.test.ts`; replace the `light.mutedInk has a real margin over light.muted` test, whose premise this task removes on purpose)

```ts
import { glassFill, withAlpha } from './glass';

describe('glass tokens', () => {
  it('withAlpha converts #RRGGBB to rgba', () => {
    expect(withAlpha('#262A33', 0.72)).toBe('rgba(38,42,51,0.72)');
    expect(withAlpha('#ffffff', 1)).toBe('rgba(255,255,255,1)');
  });

  it('withAlpha rejects malformed input instead of emitting a broken colour', () => {
    expect(() => withAlpha('#fff', 0.5)).toThrow();
    expect(() => withAlpha('#FFFFFF', 1.2)).toThrow();
    expect(() => withAlpha('#FFFFFF', -0.1)).toThrow();
  });

  it.each([['light', light], ['dark', dark]])('%s: glassFill uses glassTint at glassAlpha', (_n, p) => {
    expect(glassFill(p)).toBe(withAlpha(p.glassTint, p.glassAlpha));
  });

  it.each([['light', light], ['dark', dark]])('%s: glassAlpha is the measured 0.72', (_n, p) => {
    // 0.72 is the value the 2026-10-09 contrast measurement cleared AA with; lowering it must go
    // back through glassContrast.test.ts, which reads this same token.
    expect(p.glassAlpha).toBe(0.72);
  });
});

it('muted now clears AA on its own against bg and card (glass makes the old margin unusable)', () => {
  for (const p of [light, dark]) {
    expect(contrastRatio(p.muted, p.bg)).toBeGreaterThanOrEqual(AA_SMALL_TEXT);
    expect(contrastRatio(p.muted, p.card)).toBeGreaterThanOrEqual(AA_SMALL_TEXT);
  }
});
```

- [ ] **Step 2: Run the tests to see them fail**

Run: `npm test -- src/theme/palette.test.ts`
Expected: FAIL. `Cannot find module './glass'`, and `glassAlpha` is undefined.

- [ ] **Step 3: Implement**

`mobile/src/theme/glass.ts`:

```ts
import type { Palette } from './palette';

/** `#RRGGBB` + alpha -> `rgba(r,g,b,a)`. Throws on anything else so a typo can't silently paint
 *  an invalid colour (React Native renders an invalid colour string as transparent/black). */
export function withAlpha(hex: string, alpha: number): string {
  if (!/^#[0-9a-fA-F]{6}$/.test(hex)) throw new Error(`withAlpha: expected #RRGGBB, got ${hex}`);
  if (!(alpha >= 0 && alpha <= 1)) throw new Error(`withAlpha: alpha out of range: ${alpha}`);
  const n = parseInt(hex.slice(1), 16);
  return `rgba(${(n >> 16) & 255},${(n >> 8) & 255},${n & 255},${alpha})`;
}

/** The translucent fill every glass surface paints (Phase 1 everywhere; Android/rows in Phase 2). */
export function glassFill(p: Palette): string {
  return withAlpha(p.glassTint, p.glassAlpha);
}
```

In `palette.ts`, `light`:

```ts
  // Was #64748B (4.55:1 on bg). The glass redesign puts muted text on translucent surfaces over a
  // mesh backdrop, where #64748B measured 4.36:1 on glass and 3.43:1 directly on the backdrop --
  // under AA. #475569 (slate-600, the old mutedInk) measured 6.94 / 5.46 on the same pixels.
  muted: '#475569',
  ...
  // Glass surfaces: glassTint composited at glassAlpha. 0.72 is the measured value -- see
  // glassContrast.test.ts, which checks every text token against the real mesh PNG pixels.
  glassTint: '#FFFFFF',
  glassAlpha: 0.72,
  glassEdge: 'rgba(255,255,255,0.85)',
```

`dark`:

```ts
  // Was #98968F: 4.57:1 on glass but 3.86:1 directly on the dark mesh backdrop. #B5B3AC measured
  // 6.44 / 5.45. mutedInk follows so the existing "dark.mutedInk equals dark.muted" guard holds.
  muted: '#B5B3AC',
  mutedInk: '#B5B3AC',
  glassTint: '#262A33',
  glassAlpha: 0.72,
  glassEdge: 'rgba(255,255,255,0.14)',
```

Update the comment above light `mutedInk` so it no longer claims `muted` is `#64748B`. Also grep your own edit for any other comment that cites the old values: `grep -n "64748B\|98968F" mobile/src -r`.

- [ ] **Step 4: Run the tests to see them pass**

Run: `npm test -- src/theme/palette.test.ts`
Expected: PASS, every test in the file.

- [ ] **Step 5: Typecheck.** Run `npm run typecheck`. Expected: clean. `dark: typeof light` forces both themes to carry the new keys.

- [ ] **Step 6: Commit**

```bash
git -C /Users/sid/Downloads/finora-glass-ui add mobile/src/theme/palette.ts mobile/src/theme/glass.ts mobile/src/theme/palette.test.ts
git -C /Users/sid/Downloads/finora-glass-ui commit -m "feat(mobile): glass tokens and AA-safe muted for the glass redesign"
```

---

### Task 2: `useReduceTransparency`

**Files:** Create `mobile/src/lib/useReduceTransparency.ts` and `useReduceTransparency.test.ts`. Modify `mobile/App.tsx`: one call at module scope.

**Interfaces:**
- Produces:
  - `useReduceTransparency(): boolean | null`. `null` means not known yet; consumers **must** treat it as solid.
  - `primeReduceTransparency(): void`, called once at app start so the answer usually lands while the launch animation and bootstrapping still cover the app.
  - `__resetReduceTransparencyForTests(): void`.
- Store rule: a value from the live `'reduceTransparencyChanged'` event is never overwritten by a later-resolving initial query.

- [ ] **Step 1: Write the failing test**

```ts
import { act, renderHook } from '@testing-library/react-native';
import { AccessibilityInfo } from 'react-native';
import {
  __resetReduceTransparencyForTests, primeReduceTransparency, useReduceTransparency,
} from './useReduceTransparency';

let emit: ((v: boolean) => void) | undefined;
let resolveQuery!: (v: boolean) => void;
let rejectQuery!: (e: Error) => void;

beforeEach(() => {
  __resetReduceTransparencyForTests();
  emit = undefined;
  jest.spyOn(AccessibilityInfo, 'addEventListener').mockImplementation((_e: any, cb: any) => {
    emit = cb;
    return { remove: jest.fn() } as any;
  });
  jest.spyOn(AccessibilityInfo, 'isReduceTransparencyEnabled').mockReturnValue(
    new Promise((res, rej) => { resolveQuery = res; rejectQuery = rej; }),
  );
});
afterEach(() => jest.restoreAllMocks());

it('is null (unknown, render solid) until the OS answers', () => {
  primeReduceTransparency();
  const { result } = renderHook(() => useReduceTransparency());
  expect(result.current).toBeNull();
});

it.each([true, false])('reports the OS answer %s', async (v) => {
  primeReduceTransparency();
  const { result } = renderHook(() => useReduceTransparency());
  await act(async () => resolveQuery(v));
  expect(result.current).toBe(v);
});

it('follows live changes both ways without restart', async () => {
  primeReduceTransparency();
  const { result } = renderHook(() => useReduceTransparency());
  await act(async () => resolveQuery(false));
  act(() => emit!(true));
  expect(result.current).toBe(true);
  act(() => emit!(false));
  expect(result.current).toBe(false);
});

it('a late initial query never overwrites a newer event', async () => {
  primeReduceTransparency();
  const { result } = renderHook(() => useReduceTransparency());
  act(() => emit!(true));
  await act(async () => resolveQuery(false));
  expect(result.current).toBe(true);
});

it('a rejected query resolves to false (glass), not stuck on null', async () => {
  primeReduceTransparency();
  const { result } = renderHook(() => useReduceTransparency());
  await act(async () => rejectQuery(new Error('x')));
  expect(result.current).toBe(false);
});

it('priming twice queries the OS once', () => {
  primeReduceTransparency();
  primeReduceTransparency();
  expect(AccessibilityInfo.isReduceTransparencyEnabled).toHaveBeenCalledTimes(1);
});

it('the hook primes on its own if App.tsx did not', () => {
  renderHook(() => useReduceTransparency());
  expect(AccessibilityInfo.isReduceTransparencyEnabled).toHaveBeenCalledTimes(1);
});

it('unmounting stops updates to that hook (no act() warning on a later event)', async () => {
  primeReduceTransparency();
  const { unmount } = renderHook(() => useReduceTransparency());
  unmount();
  await act(async () => resolveQuery(true));
  act(() => emit!(false));
});
```

- [ ] **Step 2: Run the test to see it fail.** Run `npm test -- src/lib/useReduceTransparency.test.ts`. Expected: FAIL, module not found.

- [ ] **Step 3: Implement**

```ts
import { useSyncExternalStore } from 'react';
import { AccessibilityInfo } from 'react-native';

/**
 * iOS "Reduce Transparency" as an app-wide store. `null` = not known yet, and every glass
 * consumer renders the SOLID look for null -- so a user who has the setting on never sees a
 * frame of glass. primeReduceTransparency() runs at app start (App.tsx), so the answer normally
 * arrives under the launch animation. A live event always wins over a late initial query.
 * Android has no such setting; RN resolves false there.
 */
let value: boolean | null = null;
let primed = false;
let eventSeen = false;
const listeners = new Set<() => void>();
const set = (v: boolean) => { value = v; listeners.forEach((l) => l()); };

export function primeReduceTransparency(): void {
  if (primed) return;
  primed = true;
  AccessibilityInfo.addEventListener('reduceTransparencyChanged', (v: boolean) => { eventSeen = true; set(v); });
  AccessibilityInfo.isReduceTransparencyEnabled()
    .then((v) => { if (!eventSeen) set(v); })
    .catch(() => { if (!eventSeen) set(false); });
}

export function useReduceTransparency(): boolean | null {
  primeReduceTransparency();
  return useSyncExternalStore(
    (l) => { listeners.add(l); return () => listeners.delete(l); },
    () => value,
  );
}

export function __resetReduceTransparencyForTests(): void {
  value = null; primed = false; eventSeen = false; listeners.clear();
}
```

The OS listener lives for the app's lifetime on purpose: it's one subscription for the whole app, not one per component.

- [ ] **Step 4: Prime at boot.** Add `primeReduceTransparency();` at module scope in `mobile/App.tsx`. Read the file first and place it where other one-time setup already runs.

- [ ] **Step 5: Run the test to see it pass.** Expected: PASS, 9 tests. Then run `npm test -- App` and confirm App's own tests still pass.

- [ ] **Step 6: Commit** with `feat(mobile): Reduce Transparency store, solid until known`.

---

### Task 3: Mesh source, generator, PNGs, pixel-level contrast test

**Files:**
- Create: `mobile/assets/glass/mesh.json`, `mobile/scripts/generate-glass-mesh.mjs`, `mobile/assets/glass/mesh-light.png`, `mobile/assets/glass/mesh-dark.png`, `mobile/src/theme/glassContrast.test.ts`
- Modify: `mobile/package.json` (add the devDependency `pngjs@7.0.0` and the script `"generate:glass-mesh": "node scripts/generate-glass-mesh.mjs"`)

**Interfaces:**
- Produces: two PNGs at 540×1170, and `mesh.json` with this shape: `{ width, height, edgeBand, themes: { light: { base, blobs: [{ color, cx, cy, r, peak }] }, dark: {...} } }`. `cx`, `cy` and `r` are fractions of the width.

- [ ] **Step 1: Write `mesh.json`.** The peaks are the measured AA-safe values. Positions keep every blob well inside the frame, so edges fall to `base`.

```json
{
  "width": 540,
  "height": 1170,
  "edgeBand": 0.08,
  "themes": {
    "light": {
      "base": "#F8FAFC",
      "blobs": [
        { "color": "#94A3B8", "cx": 0.28, "cy": 0.20, "r": 0.42, "peak": 0.35 },
        { "color": "#B8862E", "cx": 0.70, "cy": 0.50, "r": 0.40, "peak": 0.20 },
        { "color": "#60A5FA", "cx": 0.32, "cy": 0.78, "r": 0.42, "peak": 0.30 }
      ]
    },
    "dark": {
      "base": "#15171C",
      "blobs": [
        { "color": "#94A3B8", "cx": 0.28, "cy": 0.20, "r": 0.42, "peak": 0.25 },
        { "color": "#B8862E", "cx": 0.70, "cy": 0.50, "r": 0.40, "peak": 0.25 },
        { "color": "#60A5FA", "cx": 0.32, "cy": 0.78, "r": 0.42, "peak": 0.25 }
      ]
    }
  }
}
```

- [ ] **Step 2: Write the failing test** `mobile/src/theme/glassContrast.test.ts`

```ts
import { readFileSync } from 'fs';
import { join } from 'path';
import { PNG } from 'pngjs';
import { dark, light, type Palette } from './palette';

const ASSETS = join(__dirname, '../../assets/glass');
const mesh = JSON.parse(readFileSync(join(ASSETS, 'mesh.json'), 'utf8'));

const ch = (v: number) => { const c = v / 255; return c <= 0.03928 ? c / 12.92 : ((c + 0.055) / 1.055) ** 2.4; };
const lum = ([r, g, b]: number[]) => 0.2126 * ch(r) + 0.7152 * ch(g) + 0.0722 * ch(b);
const rgb = (hex: string) => { const n = parseInt(hex.slice(1), 16); return [(n >> 16) & 255, (n >> 8) & 255, n & 255]; };
const ratio = (a: number[], b: number[]) => { const [x, y] = [lum(a), lum(b)].sort((p, q) => q - p); return (x + 0.05) / (y + 0.05); };
const over = (top: number[], a: number, bot: number[]) => top.map((v, i) => v * a + bot[i] * (1 - a));

// Text tokens that can render on glass, and the subset that can render straight on the backdrop.
const ON_GLASS: Record<'light' | 'dark', (keyof Palette)[]> = {
  light: ['ink', 'muted', 'mutedInk', 'primary', 'brassInk', 'successInk', 'warningInk', 'dangerInk'],
  dark: ['ink', 'muted', 'mutedInk', 'primary', 'brassInk', 'successInk', 'warningInk', 'dangerInk'],
};
const ON_BACKDROP: (keyof Palette)[] = ['ink', 'muted', 'mutedInk', 'primary', 'brassInk'];

function load(theme: string) {
  return PNG.sync.read(readFileSync(join(ASSETS, `mesh-${theme}.png`)));
}

describe.each([['light', light], ['dark', dark]] as const)('%s mesh', (theme, p) => {
  const png = load(theme);
  type Hit = { r: number; x: number; y: number };
  // EXHAUSTIVE: every pixel. `layer(px)` maps a backdrop pixel to the surface the text sits on.
  const worst = (tokens: (keyof Palette)[], layer: (px: number[]) => number[]) => {
    const res: Record<string, Hit> = {};
    for (let y = 0; y < png.height; y++) for (let x = 0; x < png.width; x++) {
      const i = (y * png.width + x) * 4;
      const surface = layer([png.data[i], png.data[i + 1], png.data[i + 2]]);
      for (const t of tokens) {
        const r = ratio(rgb(p[t] as string), surface);
        if (!(t in res) || r < res[t].r) res[t] = { r, x, y };
      }
    }
    return res;
  };
  const expectAA = (res: Record<string, Hit>, where: string) => {
    const fails = Object.entries(res)
      .filter(([, h]) => h.r < 4.5)
      .map(([t, h]) => `${theme} / ${where} / ${t} / x=${h.x}, y=${h.y} / ${h.r.toFixed(2)}:1 < 4.50:1`);
    expect(fails).toEqual([]);
  };
  const glass = (px: number[]) => over(rgb(p.glassTint), p.glassAlpha, px);
  const SCRIM = [0, 0, 0], SCRIM_ALPHA = 0.4; // QuickActionSheet's rgba(0,0,0,0.4) backdrop

  it('PNG matches mesh.json dimensions', () => {
    expect([png.width, png.height]).toEqual([mesh.width, mesh.height]);
  });

  it('every text token clears AA on a glass card, at every pixel', () => {
    expectAA(worst(ON_GLASS[theme], glass), 'glass card');
  });

  it('text that may sit directly on the backdrop clears AA at every pixel', () => {
    expectAA(worst(ON_BACKDROP, (px) => px), 'backdrop');
  });

  it('a glass sheet over the dimming scrim clears AA, whether the scrim covers bare mesh or a glass card', () => {
    expectAA(worst(ON_GLASS[theme], (px) => glass(over(SCRIM, SCRIM_ALPHA, px))), 'sheet/scrim/mesh');
    expectAA(worst(ON_GLASS[theme], (px) => glass(over(SCRIM, SCRIM_ALPHA, glass(px)))), 'sheet/scrim/card');
  });

  it('edge band is flat base colour, so differently-sized crops meet without a seam', () => {
    const base = rgb(mesh.themes[theme].base);
    const band = Math.ceil(mesh.edgeBand * png.width);
    for (let y = 0; y < png.height; y++) for (let x = 0; x < png.width; x++) {
      if (x >= band && x < png.width - band && y >= band && y < png.height - band) continue;
      const i = (y * png.width + x) * 4;
      for (let k = 0; k < 3; k++) expect(Math.abs(png.data[i + k] - base[k])).toBeLessThanOrEqual(2);
    }
  });

  it('PNG base colour equals the palette bg it is drawn over', () => {
    expect(mesh.themes[theme].base.toLowerCase()).toBe(p.bg.toLowerCase());
  });
});
```

`dark.primary` is `#F4F1EC` and is used as text on dark surfaces. If the `dark` row fails on a token that is only ever used as a fill and never as text (check with `grep -rn "color: c.<token>" mobile/src`), remove it from `ON_GLASS.dark` and say why in a comment. Don't relax the 4.5 threshold.

- [ ] **Step 3: Install the generator dependency and confirm the test fails.** Run `npm i -D pngjs@7.0.0`, then `npm test -- src/theme/glassContrast.test.ts`. Expected: FAIL with `ENOENT ... mesh-light.png`.

- [ ] **Step 4: Write the generator** `mobile/scripts/generate-glass-mesh.mjs`

```js
// Deterministic mesh-gradient backdrop for the glass UI. Reads assets/glass/mesh.json and writes
// mesh-<theme>.png. Each blob is a smoothstep radial falloff from `peak` opacity at its centre to
// 0 at radius r (fraction of width), source-over composited onto `base`. A final mask forces the
// outer `edgeBand` to pure base so any crop of the image meets flat colour at its edges.
// Re-run after changing mesh.json: `npm run generate:glass-mesh`; glassContrast.test.ts gates it.
import { readFileSync, writeFileSync } from 'node:fs';
import { dirname, join } from 'node:path';
import { fileURLToPath } from 'node:url';
import { PNG } from 'pngjs';

const dir = join(dirname(fileURLToPath(import.meta.url)), '../assets/glass');
const spec = JSON.parse(readFileSync(join(dir, 'mesh.json'), 'utf8'));
const rgb = (h) => { const n = parseInt(h.slice(1), 16); return [(n >> 16) & 255, (n >> 8) & 255, n & 255]; };
const smooth = (t) => t * t * (3 - 2 * t);

for (const [theme, { base, blobs }] of Object.entries(spec.themes)) {
  const { width: W, height: H, edgeBand } = spec;
  const png = new PNG({ width: W, height: H });
  const b = rgb(base);
  const band = edgeBand * W;
  for (let y = 0; y < H; y++) for (let x = 0; x < W; x++) {
    let px = [...b];
    for (const blob of blobs) {
      const dx = x - blob.cx * W, dy = y - blob.cy * H;
      const d = Math.sqrt(dx * dx + dy * dy) / (blob.r * W);
      if (d >= 1) continue;
      const a = blob.peak * smooth(1 - d);
      const c = rgb(blob.color);
      px = px.map((v, k) => c[k] * a + v * (1 - a));
    }
    // Fade to base across a second band inside the hard edge band, so the mask has no visible line.
    const e = Math.min(x, y, W - 1 - x, H - 1 - y);
    const m = e <= band ? 0 : e >= 2 * band ? 1 : smooth((e - band) / band);
    const i = (y * W + x) * 4;
    for (let k = 0; k < 3; k++) png.data[i + k] = Math.round(b[k] + (px[k] - b[k]) * m);
    png.data[i + 3] = 255;
  }
  writeFileSync(join(dir, `mesh-${theme}.png`), PNG.sync.write(png));
  console.log(`wrote mesh-${theme}.png`);
}
```

- [ ] **Step 5: Generate the PNGs and run the test**

```bash
npm run generate:glass-mesh
npm test -- src/theme/glassContrast.test.ts
```

Expected: both files written, all tests PASS. Runtime should be a few seconds: about 632k pixels × 8 tokens × 4 layers per theme. Record the actual time from jest's output. If a contrast test fails, read the printed `theme / layer / token / x, y / ratio` first, then lower that blob's `peak` in `mesh.json` (never the 4.5 threshold) and regenerate. The tightest pre-measured case is light `brassInk` on a sheet over the scrim, at about 4.64.

- [ ] **Step 6: Determinism check.** Run the generator twice and run `shasum mobile/assets/glass/*.png` after each. Expected: identical hashes. Record both PNG sizes with `ls -l`; each should be well under 200 KB. If one isn't, report the actual size; don't assume.

- [ ] **Step 7: Look at them.** Open both PNGs with the Read tool. Confirm visually that the blobs are soft, that there is no banding, and that the edges are flat.

- [ ] **Step 8: Run under Node 22** with `NODE_OPTIONS=--experimental-vm-modules npx --yes node@22 ./node_modules/jest/bin/jest.js --forceExit src/theme/glassContrast.test.ts`. Expected: PASS.

- [ ] **Step 9: Commit** with `feat(mobile): mesh backdrop assets with pixel-level AA contrast test`.

---

### Task 4: `GlassScreen`

**Files:** Create `mobile/src/components/GlassScreen.tsx` and `GlassScreen.test.tsx`. Modify `mobile/src/components/AppLockGate.test.tsx` (pin the opaque cover).

**Interfaces:**
- Consumes: `useTheme()` (Palette), `useThemeSetting().resolved` (`'light' | 'dark'`), `useReduceTransparency()`.
- Produces: `GlassScreen(props: ViewProps)`, a drop-in for a screen-root `View`. It always paints `c.bg` (opaque) and, unless Reduce Transparency is on, an absolutely-filled, non-interactive, accessibility-hidden mesh `Image` as its first child.

- [ ] **Step 1: Write the failing tests**

```tsx
import { render, screen } from '@testing-library/react-native';
import { Text } from 'react-native';
import { GlassScreen } from './GlassScreen';

jest.mock('../lib/useReduceTransparency', () => ({ useReduceTransparency: jest.fn(() => false) }));
const { useReduceTransparency } = jest.requireMock('../lib/useReduceTransparency');

// Wrap in the app's real ThemeProvider the same way existing component tests do -- copy the
// wrapper from Card's nearest sibling test (e.g. AppModal.test.tsx) rather than inventing one.

it('paints opaque bg and the mesh behind children', () => {
  render(<GlassScreen testID="root"><Text>hi</Text></GlassScreen>);
  const root = screen.getByTestId('root');
  expect(root).toHaveStyle({ backgroundColor: '#F8FAFC' });
  expect(screen.getByTestId('glass-mesh')).toBeTruthy();
  expect(screen.getByText('hi')).toBeTruthy();
});

it('mesh is decorative: no touches, hidden from screen readers', () => {
  render(<GlassScreen />);
  const mesh = screen.getByTestId('glass-mesh', { includeHiddenElements: true });
  expect(mesh.props.pointerEvents).toBe('none');
  expect(mesh.props.accessibilityElementsHidden).toBe(true);
  expect(mesh.props.importantForAccessibility).toBe('no-hide-descendants');
});

afterEach(() => useReduceTransparency.mockReturnValue(false));

it.each([true, null])('renders no mesh when Reduce Transparency is %s (on, or not yet known)', (v) => {
  useReduceTransparency.mockReturnValue(v);
  render(<GlassScreen />);
  expect(screen.queryByTestId('glass-mesh', { includeHiddenElements: true })).toBeNull();
});

it('caller style wins for layout but cannot make the root transparent by omission', () => {
  render(<GlassScreen testID="root" style={{ paddingTop: 20 }} />);
  expect(screen.getByTestId('root')).toHaveStyle({ paddingTop: 20, backgroundColor: '#F8FAFC' });
});
```

Add this to `AppLockGate.test.tsx` (Review Focus 1):

```tsx
it.each([['light', '#F8FAFC'], ['dark', '#15171C']])('%s: lock cover stays fully opaque under the glass redesign', (theme, bg) => {
  // render the gate in its locked/covering state exactly as the existing tests in this file do,
  // with the theme forced to `theme` (use the file's existing theme wrapper or setting mock)
  expect(screen.getByTestId('app-lock-cover')).toHaveStyle({ backgroundColor: bg });
  expect(screen.queryByTestId('glass-mesh', { includeHiddenElements: true })).toBeNull();
});
```

- [ ] **Step 2: Run the tests to see them fail.** Run `npm test -- src/components/GlassScreen.test.tsx src/components/AppLockGate.test.tsx`. Expected: GlassScreen fails with module not found. The AppLockGate test should already **pass**. That's fine: it's a regression pin, and it must stay green through Task 7.

- [ ] **Step 3: Implement**

```tsx
import { Image, StyleSheet, View, type ViewProps } from 'react-native';
import { useTheme, useThemeSetting } from '../theme';
import { useReduceTransparency } from '../lib/useReduceTransparency';

const MESH = {
  light: require('../../assets/glass/mesh-light.png'),
  dark: require('../../assets/glass/mesh-dark.png'),
};

/**
 * Screen root for the glass redesign: opaque `c.bg` (so native-stack transitions and anything
 * behind a screen never show through) plus the static mesh backdrop glass surfaces read against.
 * Drop-in for the `<View style={[..., { backgroundColor: c.bg }]}>` root every screen had.
 * Never use this for the lock cover or anything that must hide content -- those keep plain c.bg.
 */
export function GlassScreen({ style, children, ...rest }: ViewProps) {
  const c = useTheme();
  const { resolved } = useThemeSetting();
  const solid = useReduceTransparency() !== false; // null (unknown) renders solid too
  return (
    <View {...rest} style={[style, { backgroundColor: c.bg }]}>
      {solid ? null : (
        <Image
          testID="glass-mesh"
          source={MESH[resolved]}
          resizeMode="cover"
          style={StyleSheet.absoluteFill}
          pointerEvents="none"
          accessibilityElementsHidden
          importantForAccessibility="no-hide-descendants"
        />
      )}
      {children}
    </View>
  );
}
```

- [ ] **Step 4: Run the tests to see them pass.** Expected: PASS, both files.
- [ ] **Step 5: Commit** with `feat(mobile): GlassScreen root with mesh backdrop`.

---

### Task 5: `GlassSurface` (Phase 1 body) and `Card`/`DashboardCard` delegation

**Files:**
- Create: `mobile/src/components/GlassSurface.tsx` and `GlassSurface.test.tsx`
- Modify: `mobile/src/components/Card.tsx` (`Card` only, lines 7–14), `mobile/src/components/dashboard/DashboardCard.tsx`

**Interfaces:**
- Produces: `GlassSurface({ children, style, testID, variant = 'panel', ...ViewProps })`, where `variant: 'panel' | 'row'`.
  - `panel` is for cards, sheets, modals and the tab bar. These get real blur in Phase 2.
  - `row` is for list rows, inputs and chips. These never blur, for performance: a `FlatList` of native blur views is costly.
  - In Phase 1 both render `View` with `backgroundColor: glassFill(c)` and a hairline `glassEdge` border. With Reduce Transparency on, both render `c.card` with a `c.border` edge.

- [ ] **Step 1: Write the failing tests**

```tsx
import { render, screen } from '@testing-library/react-native';
import { GlassSurface } from './GlassSurface';
import { Card } from './Card';

jest.mock('../lib/useReduceTransparency', () => ({ useReduceTransparency: jest.fn(() => false) }));
const { useReduceTransparency } = jest.requireMock('../lib/useReduceTransparency');
afterEach(() => useReduceTransparency.mockReturnValue(false));

it.each(['panel', 'row'] as const)('%s paints translucent glass fill and glass edge', (variant) => {
  render(<GlassSurface testID="s" variant={variant} />);
  expect(screen.getByTestId('s')).toHaveStyle({
    backgroundColor: 'rgba(255,255,255,0.72)',
    borderColor: 'rgba(255,255,255,0.85)',
  });
});

it.each([true, null])('falls back to solid card + border when Reduce Transparency is %s', (v) => {
  useReduceTransparency.mockReturnValue(v);
  render(<GlassSurface testID="s" />);
  expect(screen.getByTestId('s')).toHaveStyle({ backgroundColor: '#ffffff', borderColor: '#E6EAF2' });
});

it('caller style can set radius/padding but not the fill', () => {
  render(<GlassSurface testID="s" style={{ borderRadius: 4, padding: 3, backgroundColor: 'red' }} />);
  expect(screen.getByTestId('s')).toHaveStyle({ borderRadius: 4, padding: 3, backgroundColor: 'rgba(255,255,255,0.72)' });
});

it('Card renders through GlassSurface and keeps its testID/children contract', () => {
  render(<Card testID="c" />);
  expect(screen.getByTestId('c')).toHaveStyle({ backgroundColor: 'rgba(255,255,255,0.72)' });
});
```

Use the same ThemeProvider wrapper as Task 4. If a theme test helper renders dark, add the dark equivalents: `rgba(38,42,51,0.72)` and `rgba(255,255,255,0.14)`.

- [ ] **Step 2: Run the tests to see them fail.** Expected: FAIL, module not found.

- [ ] **Step 3: Implement**

```tsx
import { StyleSheet, View, type ViewProps } from 'react-native';
import { useTheme } from '../theme';
import { glassFill } from '../theme/glass';
import { useReduceTransparency } from '../lib/useReduceTransparency';

export type GlassVariant = 'panel' | 'row';

/**
 * The one glass surface. Phase 1: translucent fill + hairline edge on every platform. Phase 2
 * swaps the `panel` body for GlassView (iOS 26+) / BlurView (iOS 16.4-25) HERE ONLY -- callers
 * never branch on platform. `row` never blurs (lists of native blur views are costly).
 * Fill is applied after caller style on purpose: a call site can shape the surface but cannot
 * make it opaque or transparent by accident.
 */
export function GlassSurface({ style, children, variant = 'panel', ...rest }: ViewProps & { variant?: GlassVariant }) {
  const c = useTheme();
  const solid = useReduceTransparency() !== false; // null (unknown) renders solid too
  void variant; // consumed in Phase 2 (Task 11)
  return (
    <View
      {...rest}
      style={[
        styles.edge,
        style,
        solid
          ? { backgroundColor: c.card, borderColor: c.border }
          : { backgroundColor: glassFill(c), borderColor: c.glassEdge },
      ]}
    >
      {children}
    </View>
  );
}

const styles = StyleSheet.create({ edge: { borderWidth: StyleSheet.hairlineWidth } });
```

`Card`:

```tsx
export function Card({ children, style, testID }: { children: ReactNode; style?: ViewStyle; testID?: string }) {
  return <GlassSurface testID={testID} style={[styles.card, style]}>{children}</GlassSurface>;
}
```

Keep `styles.card`'s `borderWidth: 1`. Caller style comes after `styles.edge`, so `Card` keeps its 1px border. `DashboardCard` gets the same delegation and keeps its hairline width, padding and shadow. Drop the shadow if, on Android, `elevation` on a translucent view draws a visible dark box *inside* the surface. Check this on the emulator in Task 9 and record what was observed.

- [ ] **Step 4: Run the tests to see them pass, plus Card's existing users.** Run `npm test -- src/components src/screens/DashboardScreen`. Expected: PASS. If an existing test asserted `backgroundColor: '#ffffff'` for a card, update it to the glass value and name each such test in the commit body.

- [ ] **Step 5: Commit** with `feat(mobile): GlassSurface; Card and DashboardCard render as glass`.

---

### Task 6: Navigation: glass tab bar and mesh behind it

**Files:** Modify `mobile/src/navigation/AppTabs.tsx` (root `View` around lines 180–186, `tabBarStyle`) and `mobile/src/navigation/RootNavigator.tsx` (`navTheme` around lines 158–169). Test: `AppTabs.test.tsx`.

- [ ] **Step 1: Write the failing test** (add to `AppTabs.test.tsx`, following how that file already reads `screenOptions`):

```tsx
it('tab bar is glass: transparent style with a GlassSurface background', () => {
  // obtain screenOptions({ route }) the same way existing tests in this file do
  expect(opts.tabBarStyle).toMatchObject({ backgroundColor: 'transparent' });
  const bg = render(opts.tabBarBackground());
  expect(bg.getByTestId('tab-bar-glass')).toBeTruthy();
});
```

- [ ] **Step 2: Run it to see it fail.**

- [ ] **Step 3: Implement**
  - Change the AppTabs root to `<GlassScreen style={styles.flexFill}>`. The mesh then sits behind the in-flow tab bar; this is open decision D1.
  - In `screenOptions`:

    ```tsx
    tabBarStyle: { backgroundColor: 'transparent', borderTopColor: c.glassEdge },
    tabBarBackground: () => <GlassSurface testID="tab-bar-glass" style={StyleSheet.absoluteFill} />,
    ```

  - In `RootNavigator.tsx` `navTheme.colors`, set `card: glassFill(c)` so any native header React Navigation draws matches. **Leave `background: c.bg` unchanged.** Screens paint their own `GlassScreen`, and an opaque navigator background keeps native-stack transitions from showing the previous screen through a translucent one.

- [ ] **Step 4: Run the navigation tests.** Run `npm test -- src/navigation`. Expected: PASS.
- [ ] **Step 5: Commit** with `feat(mobile): glass tab bar over mesh`.

---

### Task 6b: Pilot gate (blocks Tasks 7 and 8)

Migrate a small set covering different rendering conditions, prove it on devices, and get Sid's sign-off before the bulk migration. Problems found here get fixed once in `GlassScreen` or `GlassSurface`, not in 40 screens.

**Pilot files:**
- `DashboardScreen` (cards, hero, the main look)
- `LedgerScreen` (dense rows, `searchWrap` input, long `FlatList`)
- `dashboard/QuickActionSheet` (sheet over scrim)
- `AppLockGate` (stays opaque; add the two `glass-exempt` markers only)
- `SettingsGeneralScreen` (a plain screen, plus the Reduce Transparency check)

- [ ] **Step 1: Migrate the pilot files** using exactly the edits described in Tasks 7 and 8, including `glass-exempt` markers for their inner opaque uses. Commit with `refactor(mobile): glass pilot -- dashboard, ledger, quick action sheet, general settings`.
- [ ] **Step 2: Run the tests:** `npm test -- src/screens/DashboardScreen src/screens/LedgerScreen src/components/dashboard src/screens/SettingsGeneralScreen src/components/AppLockGate`, then `npm run typecheck` and `npm run lint`.
- [ ] **Step 3: Layout checklist on the iOS simulator and the Android emulator, per pilot screen.** Record pass/fail per item:
  - The root still fills the viewport.
  - Safe-area top padding is unchanged against a screenshot taken from an `origin/main` build.
  - Ledger scrolls to its last row.
  - Ledger search input: keyboard avoidance unchanged.
  - Pull-to-refresh still works where it exists.
  - Pushing and popping a More-stack screen shows no flash of a different background.
- [ ] **Step 4: Visual set.** Screenshot light and dark, Reduce Transparency off and on, for each pilot screen, the sheet open, and the lock screen. Send the set to Sid (SendUserFile) with two specific questions:
  1. Does Ledger's density still read clearly?
  2. Is light `muted` (now the same as `mutedInk`) acceptable, or should secondary text keep a lighter step?
- [ ] **Step 5: Gate.** Do not start Task 7 until Sid approves. If he asks for changes, make them in the shared components or tokens and repeat Steps 2–4.

---

### Task 7: Migrate screen roots to `GlassScreen`

**Files (screen roots that paint `backgroundColor: c.bg`; verified list from `grep -rlE "backgroundColor: c\.bg\b" mobile/src`):**

`screens/`: SettingsDataScreen, BudgetsScreen, InsightsScreen, GmailReviewScreen, CategoryReviewScreen, AccountsScreen, SettingsSecurityScreen, SupportTicketsScreen, SettingsGeneralScreen, SettingsBankSyncScreen, SettingsBankSyncConfirmScreen, InvestmentsScreen, JourneyScreen, ProfileScreen, StatementHistoryScreen, SupportTicketDetailScreen, MoreScreen, SettingsAccountScreen, SettingsScreen, DashboardScreen, ReportsScreen, FinancialMemoryScreen, ReferralsScreen, FynScreen, LedgerScreen, SettingsConnectedAppsScreen, AdvancedReportsScreen, MySubscriptionScreen, GoalsScreen, WrappedScreen, PaywallScreen, SettingsCategorizationScreen, MoneyReviewScreen, `import/ImportScreen`.
`onboarding/`: FinancialFocusScreen, WelcomeScreen, OnboardingNavigator, SuccessScreen, SpendingTrackingQuestionScreen.
`components/`: AuthScreenLayout. Keep `AuthAmbientBackground` above the mesh, so auth gets the mesh plus its drifting circles.
`navigation/`: RootNavigator's two splash `View`s (lines 143 and 199).

**Not migrated. These must keep plain opaque `c.bg`, and the guard test allowlists them:** `AppLockGate.tsx` (lock cover, security), `RootErrorBoundary.tsx`, `StagedRowCard.tsx` (badges and match box), `AccountUI.tsx` (tile), `PremiumFeatureGate.tsx`, `BrandMark.tsx`, and the status-chip maps in the SupportTicket* screens.

- [ ] **Step 1: Write the guard test** `mobile/src/theme/glassMigration.test.ts`

```ts
import { readdirSync, readFileSync, statSync } from 'fs';
import { join, relative } from 'path';

const SRC = join(__dirname, '..');
// Per LINE, not per file: 20 files mix a screen root with inner opaque uses (chips, tiles,
// alternative loading/error roots), so a whole-file allowlist would either block legitimate chips
// or hide a new opaque root. An intentionally opaque line carries a trailing
// `// glass-exempt: <reason>` marker on the same line, visible in review.
const EXEMPT = /\/\/\s*glass-exempt:\s*\S/;
const files = (d: string): string[] => readdirSync(d).flatMap((f) => {
  const p = join(d, f);
  return statSync(p).isDirectory() ? files(p) : /\.tsx$/.test(f) && !/\.test\.tsx$/.test(f) ? [p] : [];
});
const unmarked = (pattern: RegExp, skip: string[] = []) => files(SRC).flatMap((p) => {
  const r = relative(SRC, p);
  if (skip.includes(r)) return [];
  return readFileSync(p, 'utf8').split('\n')
    .map((line, i) => ({ line, n: i + 1 }))
    .filter(({ line }) => pattern.test(line) && !EXEMPT.test(line))
    .map(({ n, line }) => `${r}:${n}: ${line.trim()}`);
});

it('every opaque c.bg background is either migrated to GlassScreen or marked glass-exempt', () => {
  expect(unmarked(/backgroundColor:\s*c\.bg\b/)).toEqual([]);
});

it('the lock cover is marked exempt (opaque on purpose), not migrated', () => {
  const src = readFileSync(join(SRC, 'components/AppLockGate.tsx'), 'utf8');
  expect(src).not.toMatch(/GlassScreen/);
  expect(src.match(/backgroundColor:\s*c\.bg\b.*glass-exempt/g)?.length).toBe(2);
});
```

- [ ] **Step 2: Run it to see it fail.** Expected: FAIL. The failure lists every `file:line` that paints opaque `c.bg`. Counted on 2026-10-09: 47 files; these have more than one use: RootNavigator ×2, SettingsSecurity ×3, Accounts ×2, SettingsGeneral ×3, Journey ×2, Investments ×2, Profile ×3, SupportTicketDetail ×3, SettingsAccount ×3, SettingsBankSyncConfirm ×2, FinancialMemory ×2, Reports ×4, Dashboard ×3, Referrals ×7, Goals ×2, Wrapped ×2, SettingsCategorization ×3, Fyn ×6, StagedRowCard ×4, Import ×3, AppLockGate ×2. **Save the printed list.** It's this task's checklist.

  Classify every line as one of:
  - (a) **A root.** This includes early-return loading and error roots such as `SupportTicketDetailScreen.tsx:65,73`, and scroll roots such as `:87`. Migrate it.
  - (b) **An inner opaque element** (chip, tile, match box). Keep it, and append `// glass-exempt: <what it is>`.
  - (c) **Must stay opaque for safety:** the two `AppLockGate` lines and `RootErrorBoundary`. Keep it, and append `// glass-exempt: <reason>`.

- [ ] **Step 3: Migrate each file.** The mechanical edit:

```tsx
// before
<View style={[styles.flex, { backgroundColor: c.bg, paddingTop: insets.top }]}>
// after
<GlassScreen style={[styles.flex, { paddingTop: insets.top }]}>
```

  - If the root is a `ScrollView`, `KeyboardAvoidingView` or `FlatList` (not a `View`), wrap it in `<GlassScreen style={{ flex: 1 }}>` and remove its `c.bg`. Don't put the mesh *inside* a scroll view, or it scrolls with the content.
  - Do this in batches of about 8 files. After each batch run `npm test -- <those screens' tests>` and `npm run typecheck`, then commit, e.g. `refactor(mobile): settings screens render on GlassScreen`.

- [ ] **Step 3b: Layout check for every root that wasn't a plain `View`.** Use Task 6b Step 3's checklist on the simulator for each wrapped `ScrollView`, `FlatList` or `KeyboardAvoidingView` root. Record each one in the PR body.

- [ ] **Step 4: Guard and regression run.** Run `npm test -- src/theme/glassMigration.test.ts`, then the full `npm test`. Expected: guard PASS and full suite PASS. Re-run `npm test -- src/components/AppLockGate.test.tsx` and confirm the opaque-cover pin is still green.

---

### Task 8: Migrate raw `c.card` surfaces to `GlassSurface`

**Files (verified via `grep -rlE "c\.card\b" mobile/src`, non-test):**

| Group | Files | Variant |
|---|---|---|
| Sheets and modals | AmountPromptModal, CategoryDeleteSheet, CategoryEditSheet, CategoryPickerModal, InflowKindPicker, MarkTransferModal, OptionPickerModal, ReferralCodePrompt, UploadProgressPanel, dashboard/QuickActionSheet, dashboard/AccountsCard, AccountFormSheet, AddTransactionSheet, EditTransactionSheet, TransactionDetailSheet, settings/AccountActionSheet, settings/ChangeEmailSheet, settings/ChangePasswordSheet, support/FeedbackSheet, support/NewTicketSheet | `panel` |
| Overlays | AppAlertOverlay, AppBannerOverlay, StatementRefreshBanner, onboarding/TourOverlay | `panel` |
| Rows, inputs, chips | LedgerScreen (row at line 810, `searchWrap` at 564), import/StagedRowCard, SupportTicketsScreen, SupportTicketDetailScreen, FinancialMemoryScreen, InsightsScreen, AdvancedReportsScreen, ProfileScreen, ReferralsScreen, PaywallScreen, AccountUI | `row`, unless the element is a stand-alone card, in which case `panel` |
| Already handled | Card (Task 5), AppTabs and RootNavigator (Task 6), AuthScreenLayout and RootErrorBoundary (decide per use: RootErrorBoundary stays solid) | — |

- [ ] **Step 1: Extend the guard test** in `glassMigration.test.ts`:

```ts
it('every opaque c.card background is either a GlassSurface or marked glass-exempt', () => {
  // GlassSurface's own Reduce-Transparency fallback is the one legitimate unmarked solid card.
  expect(unmarked(/backgroundColor:\s*c\.card\b/, ['components/GlassSurface.tsx'])).toEqual([]);
});
```

`c.card` used as a *text colour* isn't matched, because the regex requires `backgroundColor:`. `RootErrorBoundary`'s card stays solid and gets `// glass-exempt: crash screen must render with no dependencies on glass`.

- [ ] **Step 2: Run it to see it fail and save the offender list.**

- [ ] **Step 3: Migrate in batches of about 6 files** (`<View style={[s.sheet, { backgroundColor: c.card }]}>` becomes `<GlassSurface style={s.sheet}>`). After each batch: tests for those files, `npm run typecheck`, then commit.

  Sheets keep their `rgba(0,0,0,0.4)` dimming backdrop. Glass over a dimmed app is intended. Check each sheet over its scrim in Task 9 for legibility; the pixel test in Task 3 does not cover scrim-dimmed backgrounds.

- [ ] **Step 4: Full run.** Run `npm test`, `npm run typecheck` and `npm run lint`. Expected: all clean, and both guards PASS.

---

### Task 9: Phase 1 device verification and OTA readiness

No new code. This task produces evidence. CLAUDE.md's "Mandatory post-implementation verification" applies in full.

- [ ] **Step 1: CI-parity suite.** Run `NODE_OPTIONS=--experimental-vm-modules npx --yes node@22 ./node_modules/jest/bin/jest.js --forceExit`. Expected: PASS. Paste the final summary line into the PR body.
- [ ] **Step 1b: Record metadata with every screenshot** in the PR body or the file name: device, OS version, theme, screen, Reduce Transparency on/off, and the build or commit SHA.
- [ ] **Step 1c: Device matrix for seams and cropping (`resizeMode="cover"` crops differently per aspect ratio):**
  - a standard iPhone simulator
  - a large iPhone (Pro Max)
  - the Pixel_10 Android emulator
  - one more Android AVD with a different aspect ratio (check `list-devices`; boot an existing AVD rather than creating one)

  On each, inspect the status-bar edge, the tab-bar edge, a native-stack push, a scroll root and an open sheet for seams or jumps.
- [ ] **Step 2: iOS simulator (dev client), light and dark.** Use the iOS simulator tools to screenshot Dashboard, Ledger (with rows), Import, Insights, More, Goals, one bottom sheet (QuickActionSheet), an AppAlert, the lock screen, and Login. Check that there is no seam at the tab-bar edge and that text reads clearly.
- [ ] **Step 3: Reduce Transparency on (simulator Settings, Accessibility).** Screenshot Dashboard. Expected: identical to the pre-glass app (solid, no mesh).
- [ ] **Step 4: Android emulator (Pixel_10 AVD; see memory `pixel10-emulator-signed-build-blocks-debug-install`).** Take the same screenshots. Specifically check `DashboardCard`'s elevation on a translucent view (Task 5 note) and record what was observed.
- [ ] **Step 5: Lock screen.** Lock the app with content on screen. Screenshot the lock. Expected: no app content visible through the cover.
- [ ] **Step 6: Scroll performance spot-check.** Fling Ledger with 200+ rows on the Android emulator and watch for dropped frames with the React profiler tools. Phase 1 adds no blur, so this is expected to be unchanged. Record the observation rather than assuming it.
- [ ] **Step 7: Send Sid the screenshots** (SendUserFile) and get his visual sign-off before any OTA.
- [ ] **Step 8: OTA.** Phase 1 contains only JS and PNG assets, which `expo-updates` bundles. Confirm with `npx expo export --platform ios` that the PNGs appear in the export's asset list. Then follow the repo's existing OTA procedure **only after Sid approves**.

---

# PHASE 2 — real glass (new store build)

### Task 10: Add native modules and bump the app version

**Files:** Modify `mobile/package.json`, `mobile/package-lock.json` and `mobile/app.config.ts` (`version`), plus `mobile/src/test/setup.ts` if mocks are needed.

- [ ] **Step 1: Read the versioned docs** (glass-effect and blur-view, v57 links in Global Constraints). Write down anything that contradicts the "Facts established" section and stop to tell Sid if something does.
- [ ] **Step 2: Install** with `npx expo install expo-glass-effect expo-blur`. Expected: it resolves to `~57.0.4` and `~57.0.3` (check `package.json`).
- [ ] **Step 3: Bump the version.** In `app.config.ts`, raise `version` from `'1.0.0'` (value on 2026-10-09, `app.config.ts:77`; re-read it first) to the next minor, `'1.1.0'`, unless a newer version has shipped since.
- [ ] **Step 4: Guard test** (add to `glassMigration.test.ts`; Review Focus 5):

```ts
import { execFileSync } from 'child_process';
it('app version differs from origin/main once native glass modules are added', () => {
  const pkg = JSON.parse(readFileSync(join(SRC, '../package.json'), 'utf8'));
  if (!pkg.dependencies['expo-glass-effect']) return;
  const mainCfg = execFileSync('git', ['show', 'origin/main:mobile/app.config.ts'], { encoding: 'utf8' });
  const ours = readFileSync(join(SRC, '../app.config.ts'), 'utf8');
  const v = (s: string) => /\bversion:\s*'([^']+)'/.exec(s)?.[1];
  expect(v(ours)).toBeDefined();
  expect(v(ours)).not.toBe(v(mainCfg));
});
```

  If `git show origin/main:...` already contains `expo-glass-effect` after merge, this test becomes vacuous. That's acceptable: after merge, the next store build carries the module.

- [ ] **Step 5: Run the full suite.** If jest fails with a native-module error from either package, add this to `src/test/setup.ts`:

```ts
jest.mock('expo-glass-effect', () => {
  const { View } = require('react-native');
  return { GlassView: View, GlassContainer: View, isLiquidGlassAvailable: () => false, isGlassEffectAPIAvailable: () => false };
});
jest.mock('expo-blur', () => { const { View } = require('react-native'); return { BlurView: View }; });
```

  Only add the mock if the failure actually happened, and quote the error in the commit body.

- [ ] **Step 6: Commit** with `build(mobile): add expo-glass-effect and expo-blur; bump app version (native change)`.

---

### Task 11: `GlassSurface` platform paths

**Files:** Modify `mobile/src/components/GlassSurface.tsx` and `GlassSurface.test.tsx`.

**Interfaces:** Same exported signature as Task 5. Internals only.

- [ ] **Step 1: Write the failing tests**

```tsx
jest.mock('react-native/Libraries/Utilities/Platform', () => ({ OS: 'ios', select: (o: any) => o.ios }));
const glass = jest.requireMock('expo-glass-effect');

it('iOS 26 panel uses GlassView with the app colour scheme', () => {
  glass.isLiquidGlassAvailable = () => true; glass.isGlassEffectAPIAvailable = () => true;
  render(<GlassSurface testID="s" />);
  expect(screen.getByTestId('s').type).toBe(/* GlassView mock's host name */ 'View');
  // assert the props on the GlassView element: glassEffectStyle 'regular', colorScheme === resolved
});

it('iOS < 26 panel uses BlurView with the thin material for the theme', () => { /* isLiquidGlassAvailable false */ });
it('API missing (iOS 26 beta) falls back to BlurView, never GlassView', () => { /* available true, API false */ });
it('row never blurs on any platform', () => { /* variant row -> plain View with glassFill */ });
it('Android panel is the Phase 1 tinted View', () => { /* Platform.OS android */ });
it.each([true, null])('Reduce Transparency %s beats every native path', (v) => { /* mock useReduceTransparency -> v; expect solid c.card View, no GlassView/BlurView */ });
```

Fill each body in fully before running. Use `UNSAFE_getByType(GlassView)` / `UNSAFE_getByType(BlurView)` from the mocked modules to assert which component rendered, and assert its props: `glassEffectStyle`, `colorScheme`, `tint`, `intensity`.

- [ ] **Step 2: Run them to see them fail.**

- [ ] **Step 3: Implement.** Keep the Phase 1 tint as an inner layer on the native paths, so contrast never drops below the measured Phase 1 floor until Task 12 proves a lower alpha is safe.

```tsx
import { Platform, StyleSheet, View, type ViewProps } from 'react-native';
import { BlurView } from 'expo-blur';
import { GlassView, isGlassEffectAPIAvailable, isLiquidGlassAvailable } from 'expo-glass-effect';
import { useTheme, useThemeSetting } from '../theme';
import { glassFill } from '../theme/glass';
import { useReduceTransparency } from '../lib/useReduceTransparency';

const LIQUID = Platform.OS === 'ios' && isLiquidGlassAvailable() && isGlassEffectAPIAvailable();

export function GlassSurface({ style, children, variant = 'panel', ...rest }: ViewProps & { variant?: GlassVariant }) {
  const c = useTheme();
  const { resolved } = useThemeSetting();
  const solid = useReduceTransparency() !== false; // null (unknown) renders solid too

  if (solid) {
    return <View {...rest} style={[styles.edge, style, { backgroundColor: c.card, borderColor: c.border }]}>{children}</View>;
  }
  const tint = <View pointerEvents="none" style={[StyleSheet.absoluteFill, { backgroundColor: glassFill(c) }]} />;
  const edge = { borderColor: c.glassEdge };

  if (variant === 'panel' && LIQUID) {
    return (
      <GlassView {...rest} glassEffectStyle="regular" colorScheme={resolved} style={[styles.edge, styles.clip, style, edge]}>
        {tint}{children}
      </GlassView>
    );
  }
  if (variant === 'panel' && Platform.OS === 'ios') {
    return (
      <BlurView {...rest} intensity={60} tint={resolved === 'dark' ? 'systemThinMaterialDark' : 'systemThinMaterialLight'}
        style={[styles.edge, styles.clip, style, edge]}>
        {tint}{children}
      </BlurView>
    );
  }
  return <View {...rest} style={[styles.edge, style, { backgroundColor: glassFill(c) }, edge]}>{children}</View>;
}

const styles = StyleSheet.create({
  edge: { borderWidth: StyleSheet.hairlineWidth },
  clip: { overflow: 'hidden' },
});
```

  `LIQUID` is computed once at module load. Rendering the `GlassView` before availability is known would crash on the iOS 26 betas the package's own doc warns about.

- [ ] **Step 4: Run the tests to see them pass, then the full suite, typecheck and lint.**
- [ ] **Step 5: Commit** with `feat(mobile): GlassSurface uses Liquid Glass on iOS 26 and blur on older iOS`.

---

### Task 12: Phase 2 device verification and tint tuning

- [ ] **Step 1: Build dev clients** (EAS commands run from `mobile/`; memory `eas-commands-run-from-mobile-dir`) for an iOS 26 simulator, an iOS 18 simulator, and the Android emulator.
- [ ] **Step 2: Take screenshots** of the same set of screens as Task 9 on all three, in light and dark. Attach them to the PR and send them to Sid.
- [ ] **Step 3: Measure contrast on real pixels.** For three screens per iOS target, crop the region directly behind muted text inside a glass card. Compute the ratio against `c.muted` with the same `ratio()` function as Task 3 (copy it into a scratch script; don't commit it). Expected: ≥ 4.5. If it's higher with margin, you *may* lower the inner tint alpha on the native paths in a follow-up commit, then repeat this step. If it's lower, raise it. Record the numbers in the PR body.
- [ ] **Step 4: Check whether glass inside a `Modal` blurs the app underneath** on iOS. This is not established: native `Modal` presentation may isolate the backdrop. Record what the screenshot shows. If it doesn't blur, the sheet still has its tint layer and stays legible; note it as observed behaviour.
- [ ] **Step 5: Performance.** Profile Dashboard on the iOS 18 simulator (`BlurView` path) with the profiler tools: scroll, then open and close a sheet. Report the frame drops you measured.
- [ ] **Step 5b: Three-variant visual comparison** on the iOS 26 simulator, Dashboard, light and dark. Screenshot each:
  1. the Phase 1 tint only (temporarily set `LIQUID = false` and use the Android path);
  2. native glass with the 0.72 tint layer;
  3. native glass with the lowest tint alpha that Step 3's measurement still passed.

  Send all three to Sid. He picks one. Commit only that choice.
- [ ] **Step 5c: Release checklist before the store build ships.** Each item is a command output or observation recorded in the PR:
  1. The built binary's runtime version (the build's details in EAS) equals the new app version, not `1.0.0`.
  2. The store-bound **release** binary (not the dev client) launches and shows `GlassView` on iOS 26 and `BlurView` on iOS 18. Check with a native view-hierarchy tool for the view class names.
  3. Before Phase 2 merges, any pending OTA on the `1.0.0` runtime is built from Phase 1 code only. Confirm with `git log` of the commit the update was published from.
  4. After merge, no OTA publishes to runtime `1.0.0` from a commit that contains `expo-glass-effect`. Confirm the update's runtime version in the publish output.
- [ ] **Step 6: Wrap up.** Run CI-parity jest (Node 22), then ask Sid before opening the PR. Once he agrees and the PR is open, apply the `full-ci` label, push the empty commit, wait for real (not skipped) jobs, and check that the merge includes the latest push (`headRefOid`).

---

## Self-review (done while writing)

- **Coverage:** whole-app scope (Tasks 7 and 8 plus guards), soft mesh (Task 3), Android tinted fallback (Tasks 5 and 11), two phases (Phase 1 / Phase 2), premium goal (Tasks 9 and 12 visual sign-off), Reduce Transparency (Tasks 2, 4, 5 and 11), contrast (Tasks 1 and 3, plus 12 for real blur).
- **Placeholders:** Task 11 Step 1's test bodies are listed by name with the exact assertions to make. The executor writes them in full before running Step 2. Every other code step has complete code.
- **Type consistency:** `glassTint`/`glassAlpha`/`glassEdge` (Task 1) are used unchanged in Tasks 3, 5 and 11. `GlassVariant = 'panel' | 'row'` is the same in Tasks 5 and 11. `useReduceTransparency()` returns `boolean` everywhere. `useThemeSetting().resolved` is `'light' | 'dark'`.
- **Not established, carried as explicit checks rather than assumptions:** Modal backdrop blur (Task 12 Step 4); Android elevation on a translucent view (Task 9 Step 4); real-blur contrast (Task 12 Step 3); whether the jest-expo preset auto-mocks the new modules (Task 10 Step 5).
- **Deferred by decision, not silently:** D1, the floating tab bar with content scrolling under it.
- **Review pass (2026-10-09):** every accepted point maps to a task (see "External review"). The two rejections (a 5:1 gate and the tab-bar scroll-end test) give their reasons there.
