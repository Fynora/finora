/**
 * Phase 2 platform paths of GlassSurface: Liquid Glass (GlassView) on iOS 26+, BlurView on older
 * iOS, the Phase 1 tinted View on Android and for `row` surfaces, and the solid fallback under
 * Reduce Transparency everywhere. The two native modules are mocked with distinguishable host
 * components so the assertions are about WHICH path rendered and with what props. GlassSurface
 * reads Platform.OS and the availability checks at render time, so a scenario is just a flag
 * flip -- no module-registry resets, no second React copy.
 */
import { useEffect, type ReactNode } from 'react';
import { Platform } from 'react-native';
import { render, screen } from '@testing-library/react-native';
import { GlassSurface } from './GlassSurface';
import { ThemeProvider, useThemeSetting } from '../theme';

const mockGlass = { liquid: false, api: false };
const mockReduce = { value: false as boolean | null };
jest.mock('expo-glass-effect', () => {
  const { View } = require('react-native');
  return {
    GlassView: (props: any) => <View {...props} accessibilityHint="GlassView" />,
    GlassContainer: View,
    isLiquidGlassAvailable: () => mockGlass.liquid,
    isGlassEffectAPIAvailable: () => mockGlass.api,
  };
});
jest.mock('expo-blur', () => {
  const { View } = require('react-native');
  return { BlurView: (props: any) => <View {...props} accessibilityHint="BlurView" /> };
});
jest.mock('../lib/useReduceTransparency', () => ({ useReduceTransparency: () => mockReduce.value }));

function ForceDarkTheme({ children }: { children: ReactNode }) {
  const { setSetting } = useThemeSetting();
  useEffect(() => { setSetting('dark'); }, [setSetting]);
  return <>{children}</>;
}

const originalOS = Platform.OS;
function on(os: 'ios' | 'android', liquid = false, api = false) {
  (Platform as { OS: string }).OS = os;
  mockGlass.liquid = liquid; mockGlass.api = api;
}
beforeEach(() => { mockReduce.value = false; });
afterEach(() => { (Platform as { OS: string }).OS = originalOS; });

const hintOf = (testID: string) => screen.getByTestId(testID).props.accessibilityHint;

describe('panel paths', () => {
  it('iOS 26 with the API present renders GlassView, regular style, with the app colour scheme', () => {
    on('ios', true, true);
    render(<ThemeProvider><ForceDarkTheme><GlassSurface testID="s" /></ForceDarkTheme></ThemeProvider>);
    expect(hintOf('s')).toBe('GlassView');
    expect(screen.getByTestId('s').props.glassEffectStyle).toBe('regular');
    expect(screen.getByTestId('s').props.colorScheme).toBe('dark');
  });

  it('iOS without Liquid Glass renders BlurView with the thin material for the theme', () => {
    on('ios', false, false);
    render(<ThemeProvider><GlassSurface testID="s" /></ThemeProvider>);
    expect(hintOf('s')).toBe('BlurView');
    expect(screen.getByTestId('s').props.tint).toBe('systemThinMaterialLight');
    expect(screen.getByTestId('s').props.intensity).toBe(60);
  });

  it('iOS 26 whose Liquid Glass API is missing (the beta case) falls back to BlurView, never GlassView', () => {
    on('ios', true, false);
    render(<ThemeProvider><GlassSurface testID="s" /></ThemeProvider>);
    expect(hintOf('s')).toBe('BlurView');
  });

  it('native panels keep the Phase 1 tint layer under their children so contrast never drops below the measured floor', () => {
    on('ios', true, true);
    render(<ThemeProvider><GlassSurface testID="s" /></ThemeProvider>);
    expect(screen.getByTestId('glass-tint')).toHaveStyle({ backgroundColor: 'rgba(255,255,255,0.72)' });
  });

  it('Android panel is the Phase 1 tinted View', () => {
    on('android');
    render(<ThemeProvider><GlassSurface testID="s" /></ThemeProvider>);
    expect(hintOf('s')).toBeUndefined();
    expect(screen.getByTestId('s')).toHaveStyle({ backgroundColor: 'rgba(255,255,255,0.72)', borderColor: 'rgba(255,255,255,0.85)' });
  });
});

describe('rows and the solid fallback', () => {
  it('row never blurs, even on iOS 26', () => {
    on('ios', true, true);
    render(<ThemeProvider><GlassSurface testID="s" variant="row" /></ThemeProvider>);
    expect(hintOf('s')).toBeUndefined();
    expect(screen.getByTestId('s')).toHaveStyle({ backgroundColor: 'rgba(255,255,255,0.72)' });
  });

  it.each([true, null])('Reduce Transparency %s beats every native path', (v) => {
    on('ios', true, true);
    mockReduce.value = v;
    render(<ThemeProvider><GlassSurface testID="s" /></ThemeProvider>);
    expect(hintOf('s')).toBeUndefined();
    expect(screen.getByTestId('s')).toHaveStyle({ backgroundColor: '#ffffff', borderColor: '#E6EAF2' });
    expect(screen.queryByTestId('glass-tint')).toBeNull();
  });

  it('a caller tint still wins on the native panel path (warning banner over Liquid Glass)', () => {
    on('ios', true, true);
    render(<ThemeProvider><GlassSurface testID="s" style={{ backgroundColor: '#fef3c7' }} /></ThemeProvider>);
    // On a native panel the fill lives in the inner tint layer, so the caller's colour goes there.
    expect(screen.getByTestId('glass-tint')).toHaveStyle({ backgroundColor: '#fef3c7' });
  });
});
