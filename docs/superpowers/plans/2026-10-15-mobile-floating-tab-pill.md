# Mobile Floating Tab Pill Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Replace the in-flow full-width glass tab bar with a floating glass pill that lists scroll under, and give every screen reachable from the tabs room at the bottom so nothing is hidden behind it.

**Architecture:** A custom `FloatingTabBar` is passed to the bottom-tab navigator as `tabBar`. Its geometry lives in one pure module (`tabBarLayout.ts`) shared with the Toast. `AppTabs` provides the height the pill covers through our own `TabBarSpaceContext`; screens read it with `useTabBarSpace()` and add it to their scroll padding with `padForTabBar()`. A source-scanning jest guard keeps every tab-reachable screen asking for the space.

**Tech Stack:** Expo SDK 57, React Native 0.86 (Fabric), TypeScript 6, React Navigation 7 (`@react-navigation/bottom-tabs` 7.20.0), Reanimated 4.7, Jest 30 with React Native Testing Library 13.

**Spec:** `docs/superpowers/specs/2026-10-15-mobile-floating-tab-pill-design.md`

## Global Constraints

- Work only in `/Users/sid/Downloads/finora/.claude/worktrees/mobile-floating-tab-pill`, branch `feature/mobile-floating-tab-pill`, with absolute paths. The primary checkout is read-only.
- One plain command per Bash call; multi-line scripts go in the scratchpad (`/private/tmp/claude-501/-Users-sid-Downloads-finora/8ef85f19-c519-4b5f-82f7-497ef63610e4/scratchpad`).
- Checks run under Node 22: `NODE_OPTIONS=--experimental-vm-modules npx -y node@22 <worktree>/mobile/node_modules/.bin/jest --rootDir <worktree>/mobile <paths>`.
- Commit messages never carry a `Co-Authored-By` trailer or any AI attribution.
- No real names or amounts in repo text, tests or the PR. Fixtures are invented.
- Pill: 16 pt from each side, 64 pt tall, fully rounded; bottom gap `max(insets.bottom - 6, 12)`; at most 480 pt wide, centred; "+" a 46 pt circle inside; icon 24 pt; label 10 pt, one line, `maxFontSizeMultiplier` 1.3.
- Space: `useTabBarSpace()` = pill cover height + 12 pt inside the tabs, 0 outside; constant while the pill hides for the keyboard.
- Keyboard: iOS `keyboardWillShow`/`keyboardWillHide`, Android `keyboardDidShow`/`keyboardDidHide`; slide about 200 ms; 0 ms under Reduce Motion; hidden pill not pressable and hidden from screen readers.
- Material: `GlassSurface`. Shadow follows the Transactions day card: iOS `boxShadow: shadows.card`, Android `elevation: 2` with `overflow: 'hidden'`.
- The simulator app talks to production signed in as the owner: read only. Never press delete, save, confirm, send or import there.
- Ships in the next store build (1.1.0); never over the air to the 1.0.0 runtime.

## Rulings made while writing this plan

- **Our own `TabBarSpaceContext`, not the library's `BottomTabBarHeightContext`.** The spec has `useTabBarSpace()` read the library's context. Every screen test in this repo runs under the global `@react-navigation/native` stand-in in `mobile/src/test/setup.ts`, which has no `createScreenFactory`; importing `@react-navigation/bottom-tabs` under it fails (`TypeError: (0 , _native.createScreenFactory) is not a function`, probed 2026-10-15). Thirty-five screens would each need a new mock. `AppTabs` provides the same number (`pillCoverHeight(insets.bottom)`) through our own context; React context reaches screens inside both navigators. The pill still reports the height to the library through `BottomTabBarHeightCallbackContext`, so `useBottomTabBarHeight()` stays correct for anyone who calls it. Cost if wrong: none to behaviour; the spec's sentence is updated in Task 11.
- **The guard is a jest source scan** (the pattern `src/theme/glassMigration.test.ts` uses), not a Python script in `scripts/`. It then runs with the mobile suite, where every screen change is tested. Cost if wrong: the guard runs in the Mobile CI job instead of the hygiene job.
- **Tab role follows the library.** React Navigation's own bar gives a tab `role: Platform.select({ ios: 'button', default: 'tab' })` with a FIXME that `tab` does not work as expected on iOS. The pill does the same, with `selected` state on both. Cost if wrong: VoiceOver says "button" rather than "tab", as it does today.
- **Icons and the "+" come from the navigator options AppTabs already sets** (`tabBarIcon`, the Import tab's `tabBarButton`). The tour target registration inside `tabBarIcon` and `ImportFabButton` is untouched.

## Review Focus

1. **Pressing the tab that is already open** (More, after going into Settings): the library pops the stack to its root because the `tabPress` event carries `data.behavior.popToTop`. The pill must send the same data. Pinned in Task 4 ("a press on the open tab sends popToTop and does not navigate").
2. **Keyboard opens, then closes** (search on Transactions, then Done): the pill must come back, pressable and readable. Pinned in Task 3 ("hide then show") and Task 4 ("pill comes back when the keyboard closes").
3. **A narrow phone (320 pt) and an iPad (1024 pt)**: the pill must fit inside 16 pt margins on the first and stop at 480 pt, centred, on the second. Pinned in Task 1 (`pillFrame`).
4. **A screen whose bottom padding comes from `padding` or `paddingVertical`, not `paddingBottom`**: the space must be added to that padding, not replace it. Pinned in Task 2 (`padForTabBar`).
5. **A layout shared with screens outside the tabs** (`AuthScreenLayout` on the sign-in screens): no extra space there. Pinned in Task 2 (`useTabBarSpace` outside a provider) and Task 8 (`AuthScreenLayout` outside the tabs).

---

### Task 0: Baseline scroll profile

**Files:** none in the repo. Results go in the scratchpad file `pill-baseline.md`.

The current app must be measured before any change. Use the recipe from the Transactions redesign (`docs/superpowers/specs/2026-10-11-mobile-transactions-redesign-design.md`, "Build record", "Measured"): Time Profiler on the booted iPhone 17 Pro simulator, attached to the app's process, a 26 second window, ten swipes down the Transactions list, sample counts on the JavaScript thread and the main thread, busiest 11 seconds (`prof_window.py`).

- [ ] **Step 1: Start Metro from this worktree** (the dev client loads whichever Metro is running; it talks to production).

Run: `npx -y node@22 /Users/sid/Downloads/finora/.claude/worktrees/mobile-floating-tab-pill/mobile/node_modules/.bin/expo start --dev-client --port 8081` with `run_in_background: true` and working directory `/Users/sid/Downloads/finora/.claude/worktrees/mobile-floating-tab-pill/mobile`.
Expected: "Waiting on http://localhost:8081".

- [ ] **Step 2: Reload the app and open Transactions.** Run `zsh <scratchpad>/app_reload.sh`, then tap the Transactions tab with `mcp__Claude_Code_iOS_Simulator__control` (`action: "tap"`).
Expected: Transactions list visible.

- [ ] **Step 3: Record three runs.** For each run: `zsh <scratchpad>/prof_start.sh`, ten swipes up from y 700 to y 250 (`action: "swipe"`, 0.3 s each, one second apart), `zsh <scratchpad>/prof_finish.sh`, `python3 -I <scratchpad>/prof_window.py <trace>`.
Expected: three pairs of counts. Write them to `<scratchpad>/pill-baseline.md` with the commit (`git -C <worktree> rev-parse --short HEAD`).

No commit: nothing in the repo changes.

---

### Task 1: Pill geometry

**Files:**
- Create: `mobile/src/navigation/tabBarLayout.ts`
- Test: `mobile/src/navigation/tabBarLayout.test.ts`

**Interfaces:**
- Produces: `PILL_HEIGHT = 64`, `PLUS_SIZE = 46`, `TAB_LABEL_MAX_SCALE = 1.3`, `TAB_BAR_CLEARANCE = 12`, `pillBottomGap(insetBottom: number): number`, `pillCoverHeight(insetBottom: number): number`, `pillFrame(windowWidth: number, insetBottom: number): { left: number; width: number; bottom: number; height: number }`, `pillHideDuration(reducedMotion: boolean): number`.

- [ ] **Step 1: Write the failing test**

```ts
import {
  PILL_HEIGHT, PLUS_SIZE, TAB_BAR_CLEARANCE, TAB_LABEL_MAX_SCALE,
  pillBottomGap, pillCoverHeight, pillFrame, pillHideDuration,
} from './tabBarLayout';

describe('tabBarLayout', () => {
  it('holds the approved sizes', () => {
    expect(PILL_HEIGHT).toBe(64);
    expect(PLUS_SIZE).toBe(46);
    expect(TAB_LABEL_MAX_SCALE).toBe(1.3);
    expect(TAB_BAR_CLEARANCE).toBe(12);
  });

  it('sits just above the home indicator, and 12 pt up on a phone without one', () => {
    expect(pillBottomGap(34)).toBe(28);
    expect(pillBottomGap(0)).toBe(12);
    // An inset smaller than 18 would put the pill under 12 pt; the floor holds.
    expect(pillBottomGap(16)).toBe(12);
  });

  it('covers its own height plus its bottom gap', () => {
    expect(pillCoverHeight(34)).toBe(92);
    expect(pillCoverHeight(0)).toBe(76);
  });

  it('is 16 pt from each side on a phone', () => {
    expect(pillFrame(393, 34)).toEqual({ left: 16, width: 361, bottom: 28, height: 64 });
    expect(pillFrame(320, 0)).toEqual({ left: 16, width: 288, bottom: 12, height: 64 });
  });

  it('stops at 480 pt and centres on a wide screen', () => {
    expect(pillFrame(1024, 20)).toEqual({ left: 272, width: 480, bottom: 14, height: 64 });
    // Exactly at the cap: 480 + 2 x 16.
    expect(pillFrame(512, 0)).toEqual({ left: 16, width: 480, bottom: 12, height: 64 });
  });

  it('slides in 200 ms, at once under Reduce Motion', () => {
    expect(pillHideDuration(false)).toBe(200);
    expect(pillHideDuration(true)).toBe(0);
  });
});
```

- [ ] **Step 2: Run it to see it fail**

Run: `NODE_OPTIONS=--experimental-vm-modules npx -y node@22 /Users/sid/Downloads/finora/.claude/worktrees/mobile-floating-tab-pill/mobile/node_modules/.bin/jest --rootDir /Users/sid/Downloads/finora/.claude/worktrees/mobile-floating-tab-pill/mobile src/navigation/tabBarLayout.test.ts`
Expected: FAIL, "Cannot find module './tabBarLayout'".

- [ ] **Step 3: Write the module**

```ts
/**
 * Geometry of the floating tab pill, as plain numbers. FloatingTabBar draws from it, AppTabs
 * provides the height it covers to every screen (useTabBarSpace), and Toast uses it to sit above
 * the pill from outside the navigator. Design: docs/superpowers/specs/2026-10-15-mobile-floating-tab-pill-design.md.
 */
export const PILL_HEIGHT = 64;
export const PILL_SIDE_GAP = 16;
export const PILL_MAX_WIDTH = 480;
export const PILL_MIN_BOTTOM_GAP = 12;
// How far the pill reaches down into the home-indicator area: 28 pt up on the usual 34 pt inset.
export const PILL_HOME_INDICATOR_OVERLAP = 6;
export const PLUS_SIZE = 46;
export const TAB_LABEL_MAX_SCALE = 1.3;
// Room between the last row of a list and the top of the pill.
export const TAB_BAR_CLEARANCE = 12;
export const PILL_HIDE_MS = 200;

export function pillBottomGap(insetBottom: number): number {
  return Math.max(insetBottom - PILL_HOME_INDICATOR_OVERLAP, PILL_MIN_BOTTOM_GAP);
}

export function pillCoverHeight(insetBottom: number): number {
  return PILL_HEIGHT + pillBottomGap(insetBottom);
}

export function pillFrame(windowWidth: number, insetBottom: number) {
  const width = Math.min(windowWidth - 2 * PILL_SIDE_GAP, PILL_MAX_WIDTH);
  return { left: (windowWidth - width) / 2, width, bottom: pillBottomGap(insetBottom), height: PILL_HEIGHT };
}

export function pillHideDuration(reducedMotion: boolean): number {
  return reducedMotion ? 0 : PILL_HIDE_MS;
}
```

- [ ] **Step 4: Run it to see it pass**

Run: the Step 2 command.
Expected: PASS, 6 tests.

- [ ] **Step 5: Commit**

```bash
git -C /Users/sid/Downloads/finora/.claude/worktrees/mobile-floating-tab-pill add mobile/src/navigation/tabBarLayout.ts mobile/src/navigation/tabBarLayout.test.ts
git -C /Users/sid/Downloads/finora/.claude/worktrees/mobile-floating-tab-pill commit -m "feat(mobile): floating tab pill geometry"
```

---

### Task 2: Bottom space helper

**Files:**
- Create: `mobile/src/navigation/useTabBarSpace.ts`
- Test: `mobile/src/navigation/useTabBarSpace.test.tsx`

**Interfaces:**
- Consumes: `TAB_BAR_CLEARANCE` from Task 1.
- Produces: `TabBarSpaceContext: React.Context<number>` (default 0; the value is the pill cover height), `useTabBarSpace(): number`, `padForTabBar(style: StyleProp<ViewStyle>, space: number): StyleProp<ViewStyle>`.

- [ ] **Step 1: Write the failing test**

```tsx
import type { ReactNode } from 'react';
import { StyleSheet } from 'react-native';
import { renderHook } from '@testing-library/react-native';
import { TabBarSpaceContext, padForTabBar, useTabBarSpace } from './useTabBarSpace';

const inside = (cover: number) => ({ children }: { children: ReactNode }) => (
  <TabBarSpaceContext.Provider value={cover}>{children}</TabBarSpaceContext.Provider>
);

describe('useTabBarSpace', () => {
  it('is the pill cover height plus 12 pt inside the tabs', () => {
    expect(renderHook(() => useTabBarSpace(), { wrapper: inside(92) }).result.current).toBe(104);
    expect(renderHook(() => useTabBarSpace(), { wrapper: inside(76) }).result.current).toBe(88);
  });

  it('is 0 outside the tabs', () => {
    expect(renderHook(() => useTabBarSpace()).result.current).toBe(0);
  });
});

describe('padForTabBar', () => {
  const flat = (s: unknown) => StyleSheet.flatten(s as never);

  it('adds the space to an existing paddingBottom', () => {
    expect(flat(padForTabBar({ paddingBottom: 24, paddingTop: 8 }, 104))).toMatchObject({ paddingBottom: 128, paddingTop: 8 });
  });

  it('adds to paddingVertical or padding when there is no paddingBottom', () => {
    expect(flat(padForTabBar({ paddingVertical: 10 }, 104)).paddingBottom).toBe(114);
    expect(flat(padForTabBar({ padding: 20 }, 104)).paddingBottom).toBe(124);
    expect(flat(padForTabBar([{ padding: 20 }, { paddingBottom: 4 }], 104)).paddingBottom).toBe(108);
  });

  it('adds to nothing when the style has no bottom padding at all', () => {
    expect(flat(padForTabBar({ gap: 8 }, 104)).paddingBottom).toBe(104);
    expect(flat(padForTabBar(undefined, 104)).paddingBottom).toBe(104);
  });

  it('returns the style untouched when there is no space, so screens outside the tabs do not change', () => {
    const style = { paddingBottom: 24 };
    expect(padForTabBar(style, 0)).toBe(style);
  });
});
```

- [ ] **Step 2: Run it to see it fail**

Run: `NODE_OPTIONS=--experimental-vm-modules npx -y node@22 /Users/sid/Downloads/finora/.claude/worktrees/mobile-floating-tab-pill/mobile/node_modules/.bin/jest --rootDir /Users/sid/Downloads/finora/.claude/worktrees/mobile-floating-tab-pill/mobile src/navigation/useTabBarSpace.test.tsx`
Expected: FAIL, "Cannot find module './useTabBarSpace'".

- [ ] **Step 3: Write the module**

```ts
import { createContext, useContext } from 'react';
import { StyleSheet, type StyleProp, type ViewStyle } from 'react-native';
import { TAB_BAR_CLEARANCE } from './tabBarLayout';

/**
 * The height the floating tab pill covers at the bottom of the screen. AppTabs provides it around
 * its navigator; outside the tabs it is 0. Our own context rather than the library's
 * BottomTabBarHeightContext: every screen test runs under the global @react-navigation/native
 * stand-in (src/test/setup.ts), and @react-navigation/bottom-tabs cannot be imported under it.
 */
export const TabBarSpaceContext = createContext(0);

/** Bottom room a scrolling screen needs so its last row clears the pill. Constant while the pill
 *  hides for the keyboard, so lists never jump when typing starts. */
export function useTabBarSpace(): number {
  const cover = useContext(TabBarSpaceContext);
  return cover > 0 ? cover + TAB_BAR_CLEARANCE : 0;
}

/** A scroll's contentContainerStyle with `space` added to whatever bottom padding it already has. */
export function padForTabBar(style: StyleProp<ViewStyle>, space: number): StyleProp<ViewStyle> {
  if (space <= 0) return style;
  const flat = StyleSheet.flatten(style) ?? {};
  const own = flat.paddingBottom ?? flat.paddingVertical ?? flat.padding ?? 0;
  return [style, { paddingBottom: (typeof own === 'number' ? own : 0) + space }];
}
```

- [ ] **Step 4: Run it to see it pass**

Run: the Step 2 command.
Expected: PASS, 6 tests.

- [ ] **Step 5: Commit**

```bash
git -C /Users/sid/Downloads/finora/.claude/worktrees/mobile-floating-tab-pill add mobile/src/navigation/useTabBarSpace.ts mobile/src/navigation/useTabBarSpace.test.tsx
git -C /Users/sid/Downloads/finora/.claude/worktrees/mobile-floating-tab-pill commit -m "feat(mobile): bottom space helper for screens under the floating tab pill"
```

---

### Task 3: Keyboard visibility hook

**Files:**
- Create: `mobile/src/lib/useKeyboardVisible.ts`
- Test: `mobile/src/lib/useKeyboardVisible.test.ts`

**Interfaces:**
- Produces: `useKeyboardVisible(): boolean`.

- [ ] **Step 1: Write the failing test**

```ts
import { Keyboard, Platform } from 'react-native';
import { act, renderHook } from '@testing-library/react-native';
import { useKeyboardVisible } from './useKeyboardVisible';

function captureListeners() {
  const handlers: Record<string, () => void> = {};
  const removed: string[] = [];
  jest.spyOn(Keyboard, 'addListener').mockImplementation(((event: string, handler: () => void) => {
    handlers[event] = handler;
    return { remove: () => removed.push(event) };
  }) as never);
  return { handlers, removed };
}

afterEach(() => jest.restoreAllMocks());

describe('useKeyboardVisible', () => {
  it('on iOS follows the will-show and will-hide events, so the pill moves with the keyboard', () => {
    jest.replaceProperty(Platform, 'OS', 'ios');
    const { handlers } = captureListeners();
    const { result } = renderHook(() => useKeyboardVisible());
    expect(result.current).toBe(false);
    act(() => handlers.keyboardWillShow());
    expect(result.current).toBe(true);
    act(() => handlers.keyboardWillHide());
    expect(result.current).toBe(false);
  });

  it('on Android follows the did-show and did-hide events, which are the only ones Android sends', () => {
    jest.replaceProperty(Platform, 'OS', 'android');
    const { handlers } = captureListeners();
    const { result } = renderHook(() => useKeyboardVisible());
    act(() => handlers.keyboardDidShow());
    expect(result.current).toBe(true);
    act(() => handlers.keyboardDidHide());
    expect(result.current).toBe(false);
  });

  it('stops listening when unmounted', () => {
    jest.replaceProperty(Platform, 'OS', 'ios');
    const { removed } = captureListeners();
    const { unmount } = renderHook(() => useKeyboardVisible());
    unmount();
    expect(removed.sort()).toEqual(['keyboardWillHide', 'keyboardWillShow']);
  });
});
```

- [ ] **Step 2: Run it to see it fail**

Run: `NODE_OPTIONS=--experimental-vm-modules npx -y node@22 /Users/sid/Downloads/finora/.claude/worktrees/mobile-floating-tab-pill/mobile/node_modules/.bin/jest --rootDir /Users/sid/Downloads/finora/.claude/worktrees/mobile-floating-tab-pill/mobile src/lib/useKeyboardVisible.test.ts`
Expected: FAIL, "Cannot find module './useKeyboardVisible'".

- [ ] **Step 3: Write the hook**

```ts
import { useEffect, useState } from 'react';
import { Keyboard, Platform } from 'react-native';

/**
 * Whether the on-screen keyboard is up. iOS announces the keyboard before it moves (will-show),
 * so anything following it can move with it; Android only reports it once it is up (did-show).
 */
export function useKeyboardVisible(): boolean {
  const [visible, setVisible] = useState(false);
  useEffect(() => {
    const ios = Platform.OS === 'ios';
    const show = Keyboard.addListener(ios ? 'keyboardWillShow' : 'keyboardDidShow', () => setVisible(true));
    const hide = Keyboard.addListener(ios ? 'keyboardWillHide' : 'keyboardDidHide', () => setVisible(false));
    return () => {
      show.remove();
      hide.remove();
    };
  }, []);
  return visible;
}
```

- [ ] **Step 4: Run it to see it pass**

Run: the Step 2 command.
Expected: PASS, 3 tests. If `jest.replaceProperty` rejects `Platform.OS` as not configurable, use `Object.defineProperty(Platform, 'OS', { value: 'android', configurable: true })` with the original restored in `afterEach`, and ledger it.

- [ ] **Step 5: Commit**

```bash
git -C /Users/sid/Downloads/finora/.claude/worktrees/mobile-floating-tab-pill add mobile/src/lib/useKeyboardVisible.ts mobile/src/lib/useKeyboardVisible.test.ts
git -C /Users/sid/Downloads/finora/.claude/worktrees/mobile-floating-tab-pill commit -m "feat(mobile): keyboard visibility hook"
```

---

### Task 4: The floating pill

**Files:**
- Create: `mobile/src/navigation/FloatingTabBar.tsx`
- Test: `mobile/src/navigation/FloatingTabBar.test.tsx`

**Interfaces:**
- Consumes: Task 1 (`pillCoverHeight`, `pillFrame`, `pillHideDuration`, `TAB_LABEL_MAX_SCALE`, `PILL_HEIGHT`), Task 3 (`useKeyboardVisible`).
- Produces: `FloatingTabBar(props: BottomTabBarProps): JSX.Element`. testIDs: `tab-pill` (the sliding wrapper), `tab-pill-glass` (the GlassSurface), `tab-<RouteName>` (each ordinary tab). An option's `tabBarButton` is rendered in that tab's slot instead of an ordinary tab.

- [ ] **Step 1: Write the failing test**

```tsx
import { Dimensions, Text, View } from 'react-native';
import { fireEvent, render, screen } from '@testing-library/react-native';
import { BottomTabBarHeightCallbackContext } from '@react-navigation/bottom-tabs';
import { FloatingTabBar } from './FloatingTabBar';
import { pillFrame } from './tabBarLayout';
import { light } from '../theme/palette';
import { useKeyboardVisible } from '../lib/useKeyboardVisible';
import { useReduceTransparency } from '../lib/useReduceTransparency';

// The global stand-in for @react-navigation/native (src/test/setup.ts) has no CommonActions.
jest.mock('@react-navigation/native', () => ({
  CommonActions: { navigate: (name: string, params?: object) => ({ type: 'NAVIGATE', payload: { name, params } }) },
}));
// The real package cannot load under that stand-in; the pill needs only the height callback
// context, which the test imports back from this mock to provide its own callback.
jest.mock('@react-navigation/bottom-tabs', () => ({
  BottomTabBarHeightCallbackContext: require('react').createContext(undefined),
}));
jest.mock('../lib/useKeyboardVisible', () => ({ useKeyboardVisible: jest.fn(() => false) }));
jest.mock('../lib/useReduceTransparency', () => ({ useReduceTransparency: jest.fn(() => false) }));

const ROUTES = ['Home', 'Transactions', 'Import', 'Insights', 'More'];

function setup({ index = 0, defaultPrevented = false, bottom = 34 } = {}) {
  const routes = ROUTES.map((name) => ({ key: `${name}-k`, name, params: undefined }));
  const descriptors = Object.fromEntries(routes.map((r) => [r.key, {
    options: {
      tabBarActiveTintColor: '#111111',
      tabBarInactiveTintColor: '#999999',
      tabBarIcon: ({ color }: { color: string }) => <Text>{`icon ${r.name} ${color}`}</Text>,
      ...(r.name === 'Import' ? { tabBarButton: () => <View accessible accessibilityLabel="Quick actions" accessibilityRole="button" /> } : {}),
    },
  }]));
  const navigation = { emit: jest.fn(() => ({ defaultPrevented })), dispatch: jest.fn() };
  const onHeight = jest.fn();
  const tree = () => (
    <BottomTabBarHeightCallbackContext.Provider value={onHeight}>
      <FloatingTabBar
        state={{ key: 'tabs-k', index, routes } as never}
        descriptors={descriptors as never}
        navigation={navigation as never}
        insets={{ top: 0, bottom, left: 0, right: 0 }}
      />
    </BottomTabBarHeightCallbackContext.Provider>
  );
  const view = render(tree());
  return { navigation, onHeight, rerenderSame: () => view.rerender(tree()) };
}

beforeEach(() => {
  (useKeyboardVisible as jest.Mock).mockReturnValue(false);
  (useReduceTransparency as jest.Mock).mockReturnValue(false);
});

describe('FloatingTabBar', () => {
  it('shows Home, Transactions, "+", Insights and More, in that order', () => {
    setup();
    const order = screen.getAllByRole('button').map((b) => b.props.accessibilityLabel);
    expect(order).toEqual(['Home', 'Transactions', 'Quick actions', 'Insights', 'More']);
  });

  it('marks the open tab selected and colours it with the active tint', () => {
    setup({ index: 1 });
    expect(screen.getByTestId('tab-Transactions')).toBeSelected();
    expect(screen.getByTestId('tab-Home')).not.toBeSelected();
    expect(screen.getByText('icon Transactions #111111')).toBeTruthy();
    expect(screen.getByText('icon Home #999999')).toBeTruthy();
  });

  it('a press on another tab sends tabPress and navigates to it', () => {
    const { navigation } = setup({ index: 0 });
    fireEvent.press(screen.getByTestId('tab-Insights'));
    expect(navigation.emit).toHaveBeenCalledWith({
      type: 'tabPress', target: 'Insights-k', canPreventDefault: true,
      data: { behavior: { scrollToTop: false, popToTop: false } },
    });
    expect(navigation.dispatch).toHaveBeenCalledWith({ type: 'NAVIGATE', payload: { name: 'Insights', params: undefined }, target: 'tabs-k' });
  });

  it('a press on the open tab sends popToTop and does not navigate', () => {
    const { navigation } = setup({ index: 4 });
    fireEvent.press(screen.getByTestId('tab-More'));
    expect(navigation.emit).toHaveBeenCalledWith(expect.objectContaining({
      target: 'More-k', data: { behavior: { scrollToTop: true, popToTop: true } },
    }));
    expect(navigation.dispatch).not.toHaveBeenCalled();
  });

  it('does not navigate when a listener prevents the press', () => {
    const { navigation } = setup({ index: 0, defaultPrevented: true });
    fireEvent.press(screen.getByTestId('tab-Insights'));
    expect(navigation.dispatch).not.toHaveBeenCalled();
  });

  it('a long press sends tabLongPress', () => {
    const { navigation } = setup();
    fireEvent(screen.getByTestId('tab-Insights'), 'longPress');
    expect(navigation.emit).toHaveBeenCalledWith({ type: 'tabLongPress', target: 'Insights-k' });
  });

  it('keeps labels on one line and caps their growth at 1.3 times', () => {
    setup();
    const label = screen.getByText('Transactions');
    expect(label.props.numberOfLines).toBe(1);
    expect(label.props.maxFontSizeMultiplier).toBe(1.3);
  });

  it('reports the height it covers to the navigator', () => {
    const { onHeight } = setup({ bottom: 34 });
    expect(onHeight).toHaveBeenLastCalledWith(92);
  });

  it('sits in its frame: 16 pt from each side on a phone, at most 480 pt wide', () => {
    setup({ bottom: 34 });
    const frame = pillFrame(Dimensions.get('window').width, 34);
    expect(screen.getByTestId('tab-pill')).toHaveStyle({ left: frame.left, width: frame.width, bottom: 28, height: 64 });
  });

  it('is pressable and readable while the keyboard is down', () => {
    setup();
    const pill = screen.getByTestId('tab-pill');
    expect(pill.props.pointerEvents).toBe('box-none');
    expect(pill.props.accessibilityElementsHidden).toBe(false);
  });

  it('cannot be pressed and is hidden from screen readers while the keyboard is up', () => {
    (useKeyboardVisible as jest.Mock).mockReturnValue(true);
    setup();
    const pill = screen.getByTestId('tab-pill', { includeHiddenElements: true });
    expect(pill.props.pointerEvents).toBe('none');
    expect(pill.props.accessibilityElementsHidden).toBe(true);
    expect(pill.props.importantForAccessibility).toBe('no-hide-descendants');
  });

  it('pill comes back when the keyboard closes', () => {
    (useKeyboardVisible as jest.Mock).mockReturnValue(true);
    const { rerenderSame } = setup();
    expect(screen.getByTestId('tab-pill', { includeHiddenElements: true }).props.pointerEvents).toBe('none');
    (useKeyboardVisible as jest.Mock).mockReturnValue(false);
    rerenderSame();
    expect(screen.getByTestId('tab-pill').props.pointerEvents).toBe('box-none');
    expect(screen.getByTestId('tab-pill').props.accessibilityElementsHidden).toBe(false);
  });

  it('uses the solid card colour when Reduce Transparency is on', () => {
    (useReduceTransparency as jest.Mock).mockReturnValue(true);
    setup();
    expect(screen.getByTestId('tab-pill-glass')).toHaveStyle({ backgroundColor: light.card });
  });
});
```

- [ ] **Step 2: Run it to see it fail**

Run: `NODE_OPTIONS=--experimental-vm-modules npx -y node@22 /Users/sid/Downloads/finora/.claude/worktrees/mobile-floating-tab-pill/mobile/node_modules/.bin/jest --rootDir /Users/sid/Downloads/finora/.claude/worktrees/mobile-floating-tab-pill/mobile src/navigation/FloatingTabBar.test.tsx`
Expected: FAIL, "Cannot find module './FloatingTabBar'".

- [ ] **Step 3: Write the component**

```tsx
import { useContext, useEffect, useLayoutEffect } from 'react';
import { Platform, Pressable, StyleSheet, Text, View, useWindowDimensions } from 'react-native';
import Animated, { useAnimatedStyle, useReducedMotion, useSharedValue, withTiming } from 'react-native-reanimated';
import { CommonActions } from '@react-navigation/native';
import { BottomTabBarHeightCallbackContext, type BottomTabBarButtonProps, type BottomTabBarProps } from '@react-navigation/bottom-tabs';
import { GlassSurface } from '../components/GlassSurface';
import { useKeyboardVisible } from '../lib/useKeyboardVisible';
import { shadows, typography, useTheme } from '../theme';
import { PILL_HEIGHT, TAB_LABEL_MAX_SCALE, pillCoverHeight, pillFrame, pillHideDuration } from './tabBarLayout';

/**
 * The bottom tab bar as a floating glass pill that lists scroll under. Passed to the navigator as
 * `tabBar` by AppTabs. Presses go through the same `tabPress` event and navigate action as the
 * library's own bar (BottomTabBar.tsx in @react-navigation/bottom-tabs 7.20), so tab listeners,
 * pop-to-top on a second press and Android back behave as they did. It slides away while the
 * keyboard is up. Design: docs/superpowers/specs/2026-10-15-mobile-floating-tab-pill-design.md.
 */
export function FloatingTabBar({ state, descriptors, navigation, insets }: BottomTabBarProps) {
  const c = useTheme();
  const { width: windowWidth } = useWindowDimensions();
  const frame = pillFrame(windowWidth, insets.bottom);
  const cover = pillCoverHeight(insets.bottom);

  const reportHeight = useContext(BottomTabBarHeightCallbackContext);
  useLayoutEffect(() => {
    reportHeight?.(cover);
  }, [reportHeight, cover]);

  const hidden = useKeyboardVisible();
  const reducedMotion = useReducedMotion();
  const offset = useSharedValue(0);
  useEffect(() => {
    // Far enough down that the shadow goes too.
    offset.set(withTiming(hidden ? cover + 16 : 0, { duration: pillHideDuration(reducedMotion) }));
  }, [hidden, cover, reducedMotion, offset]);
  const slide = useAnimatedStyle(() => ({ transform: [{ translateY: offset.get() }] }));

  return (
    <Animated.View
      testID="tab-pill"
      pointerEvents={hidden ? 'none' : 'box-none'}
      accessibilityElementsHidden={hidden}
      importantForAccessibility={hidden ? 'no-hide-descendants' : 'auto'}
      style={[
        styles.wrap,
        { left: frame.left, width: frame.width, bottom: frame.bottom, height: frame.height },
        Platform.OS === 'ios' && styles.shadowIos,
        slide,
      ]}
    >
      <GlassSurface testID="tab-pill-glass" style={[styles.pill, Platform.OS === 'android' && styles.android]}>
        {state.routes.map((route, index) => {
          const focused = index === state.index;
          const { options } = descriptors[route.key];

          if (options.tabBarButton) {
            return (
              <View key={route.key} style={styles.item}>
                {options.tabBarButton({ children: null } as unknown as BottomTabBarButtonProps)}
              </View>
            );
          }

          const label = typeof options.tabBarLabel === 'string' ? options.tabBarLabel : options.title ?? route.name;
          const color = focused ? options.tabBarActiveTintColor ?? c.primary : options.tabBarInactiveTintColor ?? c.muted;

          const onPress = () => {
            const event = navigation.emit({
              type: 'tabPress',
              target: route.key,
              canPreventDefault: true,
              data: {
                behavior: {
                  scrollToTop: focused && options.tabBarRepeatedPressBehavior?.scrollToTop !== false,
                  popToTop: focused && options.tabBarRepeatedPressBehavior?.popToTop !== false,
                },
              },
            });
            if (!focused && !event.defaultPrevented) {
              navigation.dispatch({ ...CommonActions.navigate(route.name, route.params), target: state.key });
            }
          };
          const onLongPress = () => navigation.emit({ type: 'tabLongPress', target: route.key });

          return (
            <Pressable
              key={route.key}
              testID={`tab-${route.name}`}
              onPress={onPress}
              onLongPress={onLongPress}
              style={styles.item}
              // Same as the library's own bar: iOS does not announce role "tab" as expected.
              accessibilityRole={Platform.OS === 'ios' ? 'button' : 'tab'}
              accessibilityState={{ selected: focused }}
              accessibilityLabel={options.tabBarAccessibilityLabel ?? label}
              accessibilityLargeContentTitle={label}
              accessibilityShowsLargeContentViewer
            >
              {options.tabBarIcon?.({ focused, color, size: 24 })}
              <Text
                numberOfLines={1}
                maxFontSizeMultiplier={TAB_LABEL_MAX_SCALE}
                style={[focused ? typography.labelS : typography.caption, styles.label, { color }]}
              >
                {label}
              </Text>
            </Pressable>
          );
        })}
      </GlassSurface>
    </Animated.View>
  );
}

const styles = StyleSheet.create({
  wrap: { position: 'absolute', borderRadius: PILL_HEIGHT / 2 },
  pill: {
    flex: 1,
    flexDirection: 'row',
    alignItems: 'center',
    borderRadius: PILL_HEIGHT / 2,
    paddingHorizontal: 4,
    paddingVertical: 6,
  },
  // Same shadow as the Transactions day card (LedgerDayCard): a shadow on the clipped native glass
  // never draws on iOS, so it goes on the unclipped wrapper; Android draws elevation on the fill.
  shadowIos: { boxShadow: shadows.card },
  android: { elevation: 2, overflow: 'hidden' },
  item: { flex: 1, minWidth: 44, height: '100%', alignItems: 'center', justifyContent: 'center' },
  label: { fontSize: 10, lineHeight: 13, marginTop: 2 },
});
```

If `tsc` (Task 10) rejects `accessibilityLargeContentTitle` or `accessibilityShowsLargeContentViewer` on `Pressable`, remove both and ledger it: the library passes them through its own pressable wrapper, and they are an iOS large-content-viewer nicety, not part of the spec.

- [ ] **Step 4: Run it to see it pass**

Run: the Step 2 command.
Expected: PASS, 13 tests.

- [ ] **Step 5: Commit**

```bash
git -C /Users/sid/Downloads/finora/.claude/worktrees/mobile-floating-tab-pill add mobile/src/navigation/FloatingTabBar.tsx mobile/src/navigation/FloatingTabBar.test.tsx
git -C /Users/sid/Downloads/finora/.claude/worktrees/mobile-floating-tab-pill commit -m "feat(mobile): floating glass tab pill"
```

---

### Task 5: AppTabs uses the pill

**Files:**
- Modify: `mobile/src/navigation/AppTabs.tsx` (imports; `ImportFabButton` styles; the `return` of `AppTabs`; `styles`)
- Modify: `mobile/src/navigation/AppTabs.test.tsx` (navigator mock renders `props.tabBar`; theme mock; glass test replaced)

**Interfaces:**
- Consumes: `FloatingTabBar` (Task 4), `TabBarSpaceContext` (Task 2), `pillCoverHeight`, `PLUS_SIZE` (Task 1).
- Produces: every screen inside `AppTabs` sees `TabBarSpaceContext` = `pillCoverHeight(insets.bottom)`.

- [ ] **Step 1: Change the test first.** In `AppTabs.test.tsx`:

Replace the `@react-navigation/native` mock with one that also has `CommonActions`:

```tsx
jest.mock('@react-navigation/native', () => ({
  useNavigation: () => ({ navigate: mockNavigate }),
  CommonActions: { navigate: (name: string, params?: object) => ({ type: 'NAVIGATE', payload: { name, params } }) },
}));
```

Replace the whole `@react-navigation/bottom-tabs` mock with one whose navigator renders `props.tabBar` the way the real one does, with each screen's options merged over `screenOptions`:

```tsx
jest.mock('@react-navigation/bottom-tabs', () => {
  const { Children, isValidElement, createContext } = require('react');
  return {
    BottomTabBarHeightCallbackContext: createContext(undefined),
    createBottomTabNavigator: () => ({
      // Builds the same state/descriptors the real navigator hands its `tabBar`, so the pill under
      // test renders each tab's real tabBarIcon (where tour registration lives) and the Import
      // tab's real tabBarButton (the "+"). Untyped on purpose: jest.mock's factory is hoisted
      // above imports.
      Navigator: (props: any) => {
        const screens = Children.toArray(props.children).filter((child: any) => isValidElement(child));
        const routes = screens.map((child: any) => ({ key: `${child.props.name}-k`, name: child.props.name, params: undefined }));
        const descriptors = Object.fromEntries(screens.map((child: any, i: number) => [routes[i].key, {
          options: { ...(props.screenOptions ? props.screenOptions({ route: routes[i] }) : {}), ...(child.props.options ?? {}) },
        }]));
        const navigation = { emit: jest.fn(() => ({ defaultPrevented: false })), dispatch: jest.fn() };
        return props.tabBar({ state: { key: 'tabs-k', index: 0, routes }, descriptors, navigation, insets: { top: 0, bottom: 34, left: 0, right: 0 } });
      },
      Screen: () => null,
    }),
  };
});
```

Add to the `../theme` mock (the pill reads them): `typography: { caption: {}, labelS: {} },` and `shadows: { card: [] },`.

Add after the theme mock: `jest.mock('../lib/useKeyboardVisible', () => ({ useKeyboardVisible: () => false }));`

Replace the `describe('AppTabs glass tab bar', ...)` block with:

```tsx
describe('AppTabs floating tab pill', () => {
  it('draws the tabs in the floating glass pill, "+" in the middle', () => {
    render(
      <TourTargetProvider>
        <AppTabs />
      </TourTargetProvider>
    );
    expect(screen.getByTestId('tab-pill-glass')).toBeTruthy();
    const order = screen.getAllByRole('button').map((b) => b.props.accessibilityLabel);
    expect(order).toEqual(['Home', 'Transactions', 'Quick actions', 'Insights', 'More']);
  });
});
```

- [ ] **Step 2: Run the file to see the new test fail**

Run: `NODE_OPTIONS=--experimental-vm-modules npx -y node@22 /Users/sid/Downloads/finora/.claude/worktrees/mobile-floating-tab-pill/mobile/node_modules/.bin/jest --rootDir /Users/sid/Downloads/finora/.claude/worktrees/mobile-floating-tab-pill/mobile src/navigation/AppTabs.test.tsx`
Expected: FAIL: `props.tabBar is not a function` (AppTabs passes no `tabBar` yet).

- [ ] **Step 3: Change `AppTabs.tsx`.**

Imports: remove `GlassSurface`; add

```tsx
import { useSafeAreaInsets } from 'react-native-safe-area-context';
import { FloatingTabBar } from './FloatingTabBar';
import { TabBarSpaceContext } from './useTabBarSpace';
import { PLUS_SIZE, pillCoverHeight } from './tabBarLayout';
```

In `AppTabs()`, after `const c = useTheme();` add `const insets = useSafeAreaInsets();`.

Replace the comment above `<GlassScreen style={styles.flexFill}>` with:

```tsx
    // GlassScreen, not a plain View: the mesh backdrop sits behind every tab. The tab bar is the
    // floating glass pill (FloatingTabBar); screens scroll under it and take their bottom room from
    // TabBarSpaceContext (useTabBarSpace), provided here because React context reaches screens
    // inside both navigators. Design: docs/superpowers/specs/2026-10-15-mobile-floating-tab-pill-design.md.
```

Wrap `<Tab.Navigator ...>...</Tab.Navigator>` in `<TabBarSpaceContext.Provider value={pillCoverHeight(insets.bottom)}>` ... `</TabBarSpaceContext.Provider>` (the `QuickActionSheet` stays outside it, inside `GlassScreen`).

Add `tabBar={(props) => <FloatingTabBar {...props} />}` to `<Tab.Navigator`.

In `screenOptions`, delete the two lines and their comment:

```tsx
          tabBarStyle: { backgroundColor: 'transparent', borderTopColor: c.glassEdge },
          tabBarBackground: () => <GlassSurface testID="tab-bar-glass" style={StyleSheet.absoluteFill} />,
```

Replace `fabWrap` and `fab` in `styles` with:

```tsx
  fabWrap: { flex: 1, alignItems: 'center', justifyContent: 'center' },
  // A filled circle inside the pill, no longer raised above the bar, so it needs no shadow of its own.
  fab: { width: PLUS_SIZE, height: PLUS_SIZE, borderRadius: PLUS_SIZE / 2, alignItems: 'center', justifyContent: 'center' },
```

and in `ImportFabButton` change `<Ionicons name="add" size={28}` to `size={24}`.

- [ ] **Step 4: Run the file to see it pass**

Run: the Step 2 command.
Expected: PASS, 4 tests (tour registration, missing provider, floating pill, "+" sheet).

- [ ] **Step 5: Commit**

```bash
git -C /Users/sid/Downloads/finora/.claude/worktrees/mobile-floating-tab-pill add mobile/src/navigation/AppTabs.tsx mobile/src/navigation/AppTabs.test.tsx
git -C /Users/sid/Downloads/finora/.claude/worktrees/mobile-floating-tab-pill commit -m "feat(mobile): tabs use the floating glass pill"
```

---

### Task 6: Guard that every tab-reachable screen leaves room

**Files:**
- Create: `mobile/src/navigation/tabBarSpace.guard.test.ts`

**Interfaces:**
- Consumes: the import lines of `AppTabs.tsx`.
- Produces: a failing test listing every screen still missing `useTabBarSpace(`; it turns green as Tasks 7 to 9 land.

- [ ] **Step 1: Write the guard**

```ts
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
```

- [ ] **Step 2: Run it to see it fail**

Run: `NODE_OPTIONS=--experimental-vm-modules npx -y node@22 /Users/sid/Downloads/finora/.claude/worktrees/mobile-floating-tab-pill/mobile/node_modules/.bin/jest --rootDir /Users/sid/Downloads/finora/.claude/worktrees/mobile-floating-tab-pill/mobile src/navigation/tabBarSpace.guard.test.ts`
Expected: test 1 PASS; test 2 FAIL listing 34 files (every reachable screen with a vertical scroll; Subscription and Verify Email Change have none of their own); test 3 FAIL on the `AuthScreenLayout` line. If test 1 reports a count other than 36, stop: read the import lines of `AppTabs.tsx` and fix the regex before going on.

- [ ] **Step 3: Commit the failing guard** (it is the RED half of Tasks 7 to 9).

```bash
git -C /Users/sid/Downloads/finora/.claude/worktrees/mobile-floating-tab-pill add mobile/src/navigation/tabBarSpace.guard.test.ts
git -C /Users/sid/Downloads/finora/.claude/worktrees/mobile-floating-tab-pill commit -m "test(mobile): guard that screens under the tab pill leave room for it"
```

---

### Task 7: Room on the tab screens and the More menu's screens

**Files (modify):** the 18 files in the table below.

**Interfaces:**
- Consumes: `padForTabBar`, `useTabBarSpace` (Task 2).

The same three edits in each file:

1. Import, with the path for the file's folder: `screens/*.tsx` use `'../navigation/useTabBarSpace'`; `screens/import/*.tsx` and `screens/settings/*.tsx` use `'../../navigation/useTabBarSpace'`.

```tsx
import { padForTabBar, useTabBarSpace } from '../navigation/useTabBarSpace';
```

2. In the screen component, on the line directly after its `const c = useTheme();` (before any early `return`, so the hook order never changes):

```tsx
  const tabBar = useTabBarSpace();
```

3. On each scroll root named in the table, wrap the existing `contentContainerStyle` value and add the indicator inset. For example `contentContainerStyle={styles.content}` becomes:

```tsx
      contentContainerStyle={padForTabBar(styles.content, tabBar)}
      scrollIndicatorInsets={{ bottom: tabBar }}
```

and `contentContainerStyle={[styles.content, { paddingTop: insets.top + spacing.md }]}` becomes:

```tsx
      contentContainerStyle={padForTabBar([styles.content, { paddingTop: insets.top + spacing.md }], tabBar)}
      scrollIndicatorInsets={{ bottom: tabBar }}
```

| File | Scroll roots to change (line on base 2f25e0248) | Note |
|---|---|---|
| `screens/DashboardScreen.tsx` | `<ScrollView` 408 | GoalsRow and HealthFactorsRow scroll sideways and are not touched |
| `screens/LedgerScreen.tsx` | `<FlatList` 504 (`styles.listContent`, 524) | |
| `screens/import/ImportScreen.tsx` | `<ScrollView` 935, `<FlatList` 1262 | two states of one screen; the review list's confirm button is in its `ListFooterComponent` |
| `screens/InsightsScreen.tsx` | `<ScrollView` 301 | the `horizontal` one at 323 is the period tabs, not touched |
| `screens/MoreScreen.tsx` | `<ScrollView` 127 | |
| `screens/AccountsScreen.tsx` | `<ScrollView` 140 | |
| `screens/CategoryReviewScreen.tsx` | `<ScrollView` 226 | |
| `screens/MoneyReviewScreen.tsx` | `<ScrollView` 83 | replace `paddingBottom: insets.bottom + spacing.lg` with `paddingBottom: spacing.lg` inside the wrapped array: the helper now supplies the room |
| `screens/GmailReviewScreen.tsx` | `<ScrollView` 136 | |
| `screens/StatementHistoryScreen.tsx` | `<ScrollView` 198 | the `<FlatList` at 580 is inside an `AppModal`, not touched |
| `screens/BudgetsScreen.tsx` | `<ScrollView` 106 | |
| `screens/PaywallScreen.tsx` | `<ScrollView` 90 | |
| `screens/MySubscriptionScreen.tsx` | `<ScrollView` 147 | |
| `screens/ReportsScreen.tsx` | `<ScrollView` 149, `<ScrollView` 181 | loading state and loaded state |
| `screens/AdvancedReportsScreen.tsx` | `<ScrollView` 153 | |
| `screens/GoalsScreen.tsx` | `<ScrollView` 168 | |
| `screens/InvestmentsScreen.tsx` | `<ScrollView` 211 | |
| `screens/FinancialMemoryScreen.tsx` | `<ScrollView` 88 | |

A file whose component has no `const c = useTheme();` line: put the hook directly after the component's first hook call instead, and ledger which file.

- [ ] **Step 1: Make the edits** to the 18 files.

- [ ] **Step 2: Run the guard and the 18 screens' own tests**

Run: `NODE_OPTIONS=--experimental-vm-modules npx -y node@22 /Users/sid/Downloads/finora/.claude/worktrees/mobile-floating-tab-pill/mobile/node_modules/.bin/jest --rootDir /Users/sid/Downloads/finora/.claude/worktrees/mobile-floating-tab-pill/mobile src/navigation/tabBarSpace.guard.test.ts src/screens`
Expected: the guard's missing list shrinks to 16 files, the 15 of Task 8 plus `screens/FynScreen.tsx` (and test 3 still fails); every screen test passes unchanged (outside a provider `padForTabBar` returns the original style).

- [ ] **Step 3: Commit**

```bash
git -C /Users/sid/Downloads/finora/.claude/worktrees/mobile-floating-tab-pill add mobile/src/screens
git -C /Users/sid/Downloads/finora/.claude/worktrees/mobile-floating-tab-pill commit -m "feat(mobile): tab and More screens leave room for the floating tab pill"
```

---

### Task 8: Room on the remaining screens and the shared sign-in layout

**Files (modify):** the 15 screen files in the table, `mobile/src/components/AuthScreenLayout.tsx`, and a new test `mobile/src/components/AuthScreenLayout.tabBar.test.tsx`.

**Interfaces:**
- Consumes: `padForTabBar`, `useTabBarSpace`, `TabBarSpaceContext` (Task 2).

The screen edits are the same three as Task 7 (import, `const tabBar = useTabBarSpace();` after `const c = useTheme();`, wrap `contentContainerStyle` and add `scrollIndicatorInsets={{ bottom: tabBar }}`).

| File | Scroll roots to change (line on base 2f25e0248) |
|---|---|
| `screens/JourneyScreen.tsx` | `<ScrollView` 47 |
| `screens/WrappedScreen.tsx` | `<ScrollView` 30 |
| `screens/ProfileScreen.tsx` | `<ScrollView` 111 |
| `screens/SettingsScreen.tsx` | `<ScrollView` 53 |
| `screens/SettingsGeneralScreen.tsx` | `<ScrollView` 137 |
| `screens/SettingsSecurityScreen.tsx` | `<ScrollView` 51 |
| `screens/SettingsCategorizationScreen.tsx` | `<ScrollView` 89 |
| `screens/SettingsDataScreen.tsx` | `<ScrollView` 46 |
| `screens/SettingsConnectedAppsScreen.tsx` | `<ScrollView` 10 |
| `screens/SettingsBankSyncScreen.tsx` | `<ScrollView` 94 |
| `screens/SettingsBankSyncConfirmScreen.tsx` | `<ScrollView` 52, `<ScrollView` 110 |
| `screens/SettingsAccountScreen.tsx` | `<ScrollView` 70 |
| `screens/ReferralsScreen.tsx` | `<ScrollView` 412 |
| `screens/SupportTicketsScreen.tsx` | `<FlatList` 54 |
| `screens/SupportTicketDetailScreen.tsx` | `<ScrollView` 90 |

- [ ] **Step 1: Write the failing test for `AuthScreenLayout`**

```tsx
import { Text } from 'react-native';
import { render, screen } from '@testing-library/react-native';
import { AuthScreenLayout } from './AuthScreenLayout';
import { TabBarSpaceContext } from '../navigation/useTabBarSpace';

// The sign-in screens use this layout outside the tabs; Verify Email Change uses it inside them.
function scrollStyle() {
  const scroll = screen.UNSAFE_getByType(require('react-native').ScrollView);
  return require('react-native').StyleSheet.flatten(scroll.props.contentContainerStyle);
}

it('outside the tabs keeps its own bottom padding', () => {
  render(<AuthScreenLayout title="Sign in"><Text>form</Text></AuthScreenLayout>);
  // Safe-area insets are 0 under the test setup; spacing.lg is 24.
  expect(scrollStyle().paddingBottom).toBe(24);
});

it('inside the tabs leaves room for the pill instead of the home indicator', () => {
  render(
    <TabBarSpaceContext.Provider value={92}>
      <AuthScreenLayout title="Verify your new email"><Text>form</Text></AuthScreenLayout>
    </TabBarSpaceContext.Provider>
  );
  // 92 cover + 12 clearance + 24 of the layout's own padding.
  expect(scrollStyle().paddingBottom).toBe(128);
});
```

- [ ] **Step 2: Run it to see the second test fail**

Run: `NODE_OPTIONS=--experimental-vm-modules npx -y node@22 /Users/sid/Downloads/finora/.claude/worktrees/mobile-floating-tab-pill/mobile/node_modules/.bin/jest --rootDir /Users/sid/Downloads/finora/.claude/worktrees/mobile-floating-tab-pill/mobile src/components/AuthScreenLayout.tabBar.test.tsx`
Expected: test 1 PASS, test 2 FAIL (received 24). If test 1 fails because the layout needs a provider it does not get here (fonts, theme), copy the wrapper the existing `AuthScreenLayout` or `LoginScreen` test uses and ledger it.

- [ ] **Step 3: Change `AuthScreenLayout.tsx`.** Add `import { useTabBarSpace } from '../navigation/useTabBarSpace';`, add `const tabBar = useTabBarSpace();` after `const insets = useSafeAreaInsets();`, and replace

```tsx
          { paddingTop: insets.top + spacing.lg, paddingBottom: insets.bottom + spacing.lg },
```

with

```tsx
          // Inside the tabs (Verify Email Change) the pill covers the home indicator, so its room
          // replaces the inset; outside them (the sign-in screens) nothing changes.
          { paddingTop: insets.top + spacing.lg, paddingBottom: (tabBar > 0 ? tabBar : insets.bottom) + spacing.lg },
```

and add `scrollIndicatorInsets={{ bottom: tabBar }}` to its `<ScrollView`.

- [ ] **Step 4: Make the 15 screen edits.**

- [ ] **Step 5: Run the guard, the layout test and the screens' tests**

Run: `NODE_OPTIONS=--experimental-vm-modules npx -y node@22 /Users/sid/Downloads/finora/.claude/worktrees/mobile-floating-tab-pill/mobile/node_modules/.bin/jest --rootDir /Users/sid/Downloads/finora/.claude/worktrees/mobile-floating-tab-pill/mobile src/navigation/tabBarSpace.guard.test.ts src/components/AuthScreenLayout.tabBar.test.tsx src/screens src/components`
Expected: the guard's missing list is `['screens/FynScreen.tsx']` only (Task 9); test 3 of the guard passes; the layout tests pass; every screen and component test passes.

- [ ] **Step 6: Commit**

```bash
git -C /Users/sid/Downloads/finora/.claude/worktrees/mobile-floating-tab-pill add mobile/src/screens mobile/src/components/AuthScreenLayout.tsx mobile/src/components/AuthScreenLayout.tabBar.test.tsx
git -C /Users/sid/Downloads/finora/.claude/worktrees/mobile-floating-tab-pill commit -m "feat(mobile): settings, support and account screens leave room for the floating tab pill"
```

---

### Task 9: Fyn's message box and the Toast

**Files:**
- Modify: `mobile/src/screens/FynScreen.tsx` (imports; component top; the input row at line 355)
- Modify: `mobile/src/screens/FynScreen.test.tsx` (two tests)
- Modify: `mobile/src/components/Toast.tsx`
- Create: `mobile/src/components/Toast.test.tsx`

**Interfaces:**
- Consumes: `useTabBarSpace`, `TabBarSpaceContext` (Task 2), `useKeyboardVisible` (Task 3), `pillCoverHeight` (Task 1).

- [ ] **Step 1: Write the failing Fyn tests.** In `FynScreen.test.tsx` add `import { TabBarSpaceContext } from '../navigation/useTabBarSpace';`, `import { useKeyboardVisible } from '../lib/useKeyboardVisible';`, the mock `jest.mock('../lib/useKeyboardVisible', () => ({ useKeyboardVisible: jest.fn(() => false) }));`, a `renderInTabs()` beside `renderScreen()` that wraps the same tree in `<TabBarSpaceContext.Provider value={92}>`, and:

```tsx
  it('keeps the message box above the tab pill while the keyboard is down', async () => {
    entitlements.mine.mockResolvedValue(granted());
    renderInTabs();
    await settle();
    // 92 cover + 12 clearance.
    expect(screen.getByTestId('fyn-composer')).toHaveStyle({ paddingBottom: 104 });
  });

  it('puts the message box right above the keyboard while typing, as before', async () => {
    (useKeyboardVisible as jest.Mock).mockReturnValue(true);
    entitlements.mine.mockResolvedValue(granted());
    renderInTabs();
    await settle();
    // Safe-area insets are 0 under the test setup; spacing.sm is 8.
    expect(screen.getByTestId('fyn-composer')).toHaveStyle({ paddingBottom: 8 });
  });
```

and add `(useKeyboardVisible as jest.Mock).mockReturnValue(false);` to the file's existing `beforeEach`, so a failed test cannot leave the keyboard "up" for the next one.

- [ ] **Step 2: Run them to see them fail**

Run: `NODE_OPTIONS=--experimental-vm-modules npx -y node@22 /Users/sid/Downloads/finora/.claude/worktrees/mobile-floating-tab-pill/mobile/node_modules/.bin/jest --rootDir /Users/sid/Downloads/finora/.claude/worktrees/mobile-floating-tab-pill/mobile src/screens/FynScreen.test.tsx`
Expected: the two new tests FAIL ("Unable to find an element with testID: fyn-composer"); the rest pass.

- [ ] **Step 3: Change `FynScreen.tsx`.** Add `import { useTabBarSpace } from '../navigation/useTabBarSpace';` and `import { useKeyboardVisible } from '../lib/useKeyboardVisible';`; after `const c = useTheme();` add

```tsx
  const tabBar = useTabBarSpace();
  const keyboardUp = useKeyboardVisible();
```

and change the input row's opening tag to

```tsx
      {/* With the keyboard down the floating tab pill covers the bottom, so the box sits above it;
          with the keyboard up the pill has slid away and the box sits right above the keyboard. */}
      <View
        testID="fyn-composer"
        style={[styles.inputRow, { borderTopColor: c.border, paddingBottom: keyboardUp ? insets.bottom + spacing.sm : Math.max(tabBar, insets.bottom + spacing.sm) }]}
      >
```

- [ ] **Step 4: Run Fyn's tests to see them pass.** Run the Step 2 command. Expected: PASS, all tests.

- [ ] **Step 5: Write the failing Toast test**

```tsx
import { render, screen } from '@testing-library/react-native';
import { Toast } from './Toast';

it('floats just above the tab pill', () => {
  render(<Toast title="Saved" />);
  // Safe-area insets are 0 under the test setup: pill cover 76 (64 + 12) plus spacing.sm (8).
  expect(screen.getByTestId('toast')).toHaveStyle({ bottom: 84 });
});
```

- [ ] **Step 6: Run it to see it fail**

Run: `NODE_OPTIONS=--experimental-vm-modules npx -y node@22 /Users/sid/Downloads/finora/.claude/worktrees/mobile-floating-tab-pill/mobile/node_modules/.bin/jest --rootDir /Users/sid/Downloads/finora/.claude/worktrees/mobile-floating-tab-pill/mobile src/components/Toast.test.tsx`
Expected: FAIL ("Unable to find an element with testID: toast").

- [ ] **Step 7: Change `Toast.tsx`.** Replace the `APPROX_TAB_BAR_HEIGHT` comment and constant with an import, `import { pillCoverHeight } from '../navigation/tabBarLayout';`, and change the wrapper to

```tsx
    // ToastProvider (App.tsx) mounts outside the tab navigator, so it cannot read the room the
    // pill takes from context; it uses the pill's own geometry instead and floats just above it.
    <View testID="toast" pointerEvents="none" style={[styles.wrap, { bottom: pillCoverHeight(insets.bottom) + spacing.sm }]}>
```

- [ ] **Step 8: Run the Toast test and the guard**

Run: `NODE_OPTIONS=--experimental-vm-modules npx -y node@22 /Users/sid/Downloads/finora/.claude/worktrees/mobile-floating-tab-pill/mobile/node_modules/.bin/jest --rootDir /Users/sid/Downloads/finora/.claude/worktrees/mobile-floating-tab-pill/mobile src/components/Toast.test.tsx src/navigation/tabBarSpace.guard.test.ts`
Expected: PASS, all 4 tests: the guard is green.

- [ ] **Step 9: Show the guard catches a forgotten screen.** Commit Step 10 first, so a checkout restores only this temporary edit. Then delete the `const tabBar = useTabBarSpace();` line from `screens/WrappedScreen.tsx` (the guard reads text, so the file need not compile for this), run the guard (Step 8 command), see test 2 FAIL with `['screens/WrappedScreen.tsx']`, and restore the file with `git -C /Users/sid/Downloads/finora/.claude/worktrees/mobile-floating-tab-pill checkout -- mobile/src/screens/WrappedScreen.tsx`. Confirm `git status --short` is empty. Record the failing output in the ledger.

- [ ] **Step 10: Commit**

```bash
git -C /Users/sid/Downloads/finora/.claude/worktrees/mobile-floating-tab-pill add mobile/src/screens/FynScreen.tsx mobile/src/screens/FynScreen.test.tsx mobile/src/components/Toast.tsx mobile/src/components/Toast.test.tsx
git -C /Users/sid/Downloads/finora/.claude/worktrees/mobile-floating-tab-pill commit -m "feat(mobile): Fyn's message box and the toast sit above the floating tab pill"
```

---

### Task 10: Whole mobile check

**Files:** none new; fixes go where the checks point.

- [ ] **Step 1: Jest, whole suite.** Run `NODE_OPTIONS=--experimental-vm-modules npx -y node@22 /Users/sid/Downloads/finora/.claude/worktrees/mobile-floating-tab-pill/mobile/node_modules/.bin/jest --rootDir /Users/sid/Downloads/finora/.claude/worktrees/mobile-floating-tab-pill/mobile` with output to `<scratchpad>/pill-jest.txt`, then read its tail. Expected: all suites pass.
- [ ] **Step 2: Types.** Run `npx -y node@22 /Users/sid/Downloads/finora/.claude/worktrees/mobile-floating-tab-pill/mobile/node_modules/.bin/tsc --noEmit -p /Users/sid/Downloads/finora/.claude/worktrees/mobile-floating-tab-pill/mobile`. Expected: no output.
- [ ] **Step 3: Lint.** Run `npx -y node@22 /Users/sid/Downloads/finora/.claude/worktrees/mobile-floating-tab-pill/mobile/node_modules/.bin/expo lint --max-warnings 0` with working directory `mobile`. Expected: clean.
- [ ] **Step 4: Android bundle.** Run `npx -y node@22 /Users/sid/Downloads/finora/.claude/worktrees/mobile-floating-tab-pill/mobile/node_modules/.bin/expo export --platform android --output-dir <scratchpad>/pill-export` with working directory `mobile`. Expected: "Exported".
- [ ] **Step 5: Repository guards** the Mobile and hygiene jobs run: `python3 scripts/check-nav-taxonomy-drift.py`, `python3 scripts/check-nav-tracking-coverage.py`, `python3 scripts/check-reporting-period-labels.py`, `python3 scripts/check-imports.py`, each from the worktree root. Expected: each passes.
- [ ] **Step 6: Self-review the diff** (`git -C <worktree> diff origin/main...HEAD -- mobile`): every comment true of the new layout, no leftover `tab-bar-glass`, `APPROX_TAB_BAR_HEIGHT` or `tabBarBackground`, no screen with the hook after an early return. Fix what it finds test-first, then re-run Steps 1 to 5.
- [ ] **Step 7: Commit any fixes** with messages that say what was fixed.

---

### Task 11: iPhone simulator pass and after-profile

Signed in to the owner's account against production, read only. Never press delete, save, confirm, send or import. Metro from this worktree (Task 0, Step 1); reload with `app_reload.sh` after each change.

- [ ] **Step 1: Bottom of every screen.** For each of the 35 screens listed in the spec ("Space at the bottom of every screen"), open it, scroll to the very bottom, take a screenshot (`action: "screenshot"`, kept in the scratchpad, never in the repo) and record in `<scratchpad>/pill-screens.md`: screen, last row fully above the pill (yes/no), anything else seen. A "no" is a bug: fix it test-first, commit, re-check.
- [ ] **Step 2: Light and dark.** Home and Transactions in each, using the app's own theme setting.
- [ ] **Step 3: Largest text.** `xcrun simctl ui booted content_size accessibility-large`, reload, check the labels stay on one line and the pill keeps its height; set back with `content_size large`.
- [ ] **Step 4: Keyboard.** Transactions search and Fyn: the pill slides away as the keyboard opens and returns when it closes; Fyn's box sits above the keyboard, then above the pill.
- [ ] **Step 5: Tabs and "+".** Each tab opens its screen; a second press on More after opening Settings returns to the More menu; "+" opens the quick-action menu (close it without choosing).
- [ ] **Step 6: Tour.** Not runnable on the owner's account without resetting onboarding, which writes to production. Record it as not run; the unit tests cover the tour target registration.
- [ ] **Step 7: iPad.** Boot an iPad simulator, install the same dev client build, check the pill is 480 pt wide and centred. If no dev client build for iPad is at hand, record it as not run.
- [ ] **Step 8: After-profile.** Repeat Task 0, Step 3 on this branch. Compare with `pill-baseline.md`. If the after runs sit above the before runs on either thread, stop and take the numbers to the owner before Task 12.

---

### Task 12: Records and pull request

**Files:**
- Modify: `docs/superpowers/specs/2026-10-15-mobile-floating-tab-pill-design.md` (status line; the `useTabBarSpace()` paragraph per the first ruling; the "Guard" paragraph per the second; a "## Build record (date)" with the profile table, the 35-screen results, what was not verified, and every ruling)
- Modify: this plan (append "## Execution record" with a row per task)

- [ ] **Step 1: Write both records.** Measured numbers only; "not established" where something was not checked.
- [ ] **Step 2: Commit**

```bash
git -C /Users/sid/Downloads/finora/.claude/worktrees/mobile-floating-tab-pill add docs/superpowers/specs/2026-10-15-mobile-floating-tab-pill-design.md docs/superpowers/plans/2026-10-15-mobile-floating-tab-pill.md
git -C /Users/sid/Downloads/finora/.claude/worktrees/mobile-floating-tab-pill commit -m "docs(mobile): floating tab pill build record"
```

- [ ] **Step 3: Check against main, push, open the PR.** `git fetch origin`; `git log --oneline origin/main..HEAD`; `gh repo view --json visibility` (public: open the PR without asking; private: label `full-ci` and push once more only when ready); `git push -u origin feature/mobile-floating-tab-pill`; `gh pr create` with a body in plain prose ending with the Claude Code line, no screenshots of real data, the not-verified list, and "ships in the next store build; must not be sent over the air to the 1.0.0 runtime".
- [ ] **Step 4: Send the spec and plan to the owner as files.**

## Execution record (2026-10-15)

Executed inline in one session, with no sub-agents. Measured results and what the build changed
against the design are in the design document's "Build record". This section records how the plan
itself went.

| Task | Result |
|---|---|
| 0 Baseline | Done after Task 10: the simulator app was signed out until the owner signed in. Taken from a separate checkout of the base commit, so it measures unchanged code. `prof_start.sh` needed one retry (xctrace "Cannot find process for provided pid" with the process running). |
| 1 Geometry | Done as written. |
| 2 Space helper | Done as written; the test helper was later typed for `tsc`. |
| 3 Keyboard hook | Done as written. |
| 4 Pill | Done as written, 13 tests; one more added in Task 11. |
| 5 AppTabs | Done. Its "+" menu test prints a jest teardown warning, the same on the base commit. |
| 6 Guard | Done; started red with the expected 34 screens. |
| 7 Tab and More screens | Done with a scratch script that asserted each edit; 20 scroll roots in 18 files. |
| 8 Other screens and the sign-in layout | Done; two Settings screens needed the hook placed differently (ruling in the design's build record). |
| 9 Fyn and Toast | Done. The Fyn tests wait for the chat with `findByTestId`; the red was then proven by restoring the old padding. The commit-message check rejected a subject starting with "Fyn's" (sentence case); reworded. The guard was shown to catch a screen with the hook removed. |
| 10 Whole check | `tsc` failed once on the space-helper test's typing; fixed. Jest 253 suites / 3,132 tests, lint, Android export and the four repository guards passed. |
| 11 Simulator | 29 of 35 screens checked, all clear; 6 not reachable on this account. One fault found and fixed test-first (labels cut off at the largest text size). iPad and the tour not run. |
| 12 Records | This record and the design's build record. |

The final review was a self-review by the author. No second reviewer looked at this change.
