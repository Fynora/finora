import type { Palette } from './palette';

/**
 * `#RRGGBB` + alpha -> `rgba(r,g,b,a)`. Throws on anything else so a typo can't silently paint
 * an invalid colour (React Native renders an invalid colour string as transparent/black).
 */
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
