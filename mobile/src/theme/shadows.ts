import type { BoxShadowValue } from 'react-native';

/**
 * The dashboard's two shadows, as drawn in the approved card redesign (2026-10-10). They are
 * box shadows rather than shadowColor/shadowOpacity/shadowRadius because a box shadow is cut
 * from the view's own rounded box: it needs no opaque background to take its shape from, which
 * a glass surface does not have, and it supports the negative spread that keeps these tight
 * under the card instead of haloing around it.
 *
 * The same values serve both themes. On the dark backdrop they are close to invisible, which is
 * right: dark cards separate by their lighter fill and edge, not by a shadow.
 */
export const shadows: { card: BoxShadowValue[]; hero: BoxShadowValue[] } = {
  // A wide, faint lift plus a one point contact line that stops the edge dissolving into the page.
  card: [
    { offsetX: 0, offsetY: 10, blurRadius: 28, spreadDistance: -6, color: 'rgba(15,23,41,0.07)' },
    { offsetX: 0, offsetY: 1, blurRadius: 2, spreadDistance: 0, color: 'rgba(15,23,41,0.04)' },
  ],
  // The hero is the one dark slab on a light page, so it sits further off it.
  hero: [{ offsetX: 0, offsetY: 18, blurRadius: 36, spreadDistance: -12, color: 'rgba(20,23,28,0.28)' }],
};

/**
 * How far the card shadow reaches past a card's top and bottom edge: blur plus spread, moved by
 * the offset (28 - 6 - 10 above, 28 - 6 + 10 below). A scroll view clips to its bounds, so a
 * row of cards inside one pads its content by this much and takes the same amount back in
 * margin; otherwise the shadow stops dead at the scroll view's edge.
 */
export const cardShadowRoom = { top: 12, bottom: 32 };
