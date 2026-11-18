import { COLOR_HEX } from '../../lib/categoryIcons';
import type { HoldingGroupKey } from '../../lib/investmentSummary';

/** One colour per type of holding, from the app's category palette. */
export const GROUP_COLOR: Record<HoldingGroupKey, string> = {
  'mutual-funds': COLOR_HEX.blue,
  deposits: COLOR_HEX.teal,
  stocks: COLOR_HEX.orange,
  retirement: COLOR_HEX.purple,
  other: COLOR_HEX.gray,
};
