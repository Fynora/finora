import { colorHexFor } from '../../lib/categoryIcons';
import type { HoldingGroupKey } from '../../lib/investmentSummary';

/** One colour per type of holding, from the app's category palette. */
export const GROUP_COLOR: Record<HoldingGroupKey, string> = {
  'mutual-funds': colorHexFor('blue'),
  deposits: colorHexFor('teal'),
  stocks: colorHexFor('orange'),
  retirement: colorHexFor('purple'),
  other: colorHexFor('gray'),
};
