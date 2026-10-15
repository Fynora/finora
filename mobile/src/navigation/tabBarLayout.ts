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
