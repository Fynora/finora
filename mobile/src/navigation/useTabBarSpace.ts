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
