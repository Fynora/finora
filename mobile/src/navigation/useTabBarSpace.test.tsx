import type { ReactNode } from 'react';
import { StyleSheet, type StyleProp, type ViewStyle } from 'react-native';
import { renderHook } from '@testing-library/react-native';
import { TabBarSpaceContext, padForTabBar, useTabBarSpace } from './useTabBarSpace';

const inside = (cover: number) => function Wrapper({ children }: { children: ReactNode }) {
  return <TabBarSpaceContext.Provider value={cover}>{children}</TabBarSpaceContext.Provider>;
};

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
  const flat = (s: StyleProp<ViewStyle>): ViewStyle => StyleSheet.flatten(s) ?? {};

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
