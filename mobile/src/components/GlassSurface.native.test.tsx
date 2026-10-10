/**
 * Phase 2 platform paths of GlassSurface: Liquid Glass (GlassView) on iOS 26+, BlurView on older
 * iOS, the Phase 1 tinted View on Android and for `row` surfaces, and the solid fallback under
 * Reduce Transparency everywhere. The two native modules are mocked with distinguishable host
 * components so the assertions are about WHICH path rendered and with what props. GlassSurface
 * reads Platform.OS and the availability checks at render time, so a scenario is just a flag
 * flip -- no module-registry resets, no second React copy.
 */
import { useEffect, type ReactNode } from 'react';
import { Platform, StyleSheet, Text } from 'react-native';
import { render, screen } from '@testing-library/react-native';
import { GlassSurface } from './GlassSurface';
import { DashboardCard } from './dashboard/DashboardCard';
import { shadows, ThemeProvider, useThemeSetting } from '../theme';

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

// A native effect view has to clip itself to its corner radius, and on iOS a view that clips
// also clips its own shadow: the dashboard cards declared one for months and none was ever drawn
// (measured on an iOS 26 screenshot: the page directly under a card was the bare page colour).
// So a surface that asks for a shadow is built the other way round: a plain, unclipped view
// carries the caller's whole style, and the glass is a clipped layer behind the children.
describe('a surface that casts a shadow', () => {
  const SHADOW = [{ offsetX: 0, offsetY: 10, blurRadius: 28, spreadDistance: -6, color: 'rgba(15,23,41,0.07)' }];
  const style = { borderRadius: 20, padding: 20, marginTop: 16, boxShadow: SHADOW };

  it.each([
    ['iOS 26', true, 'GlassView'],
    ['older iOS', false, 'BlurView'],
  ] as const)('%s: the shadow is on an unclipped outer view and the glass is a clipped layer behind the children', (_name, liquid, effect) => {
    on('ios', liquid, liquid);
    render(<ThemeProvider><GlassSurface testID="s" style={style}><Text>child</Text></GlassSurface></ThemeProvider>);

    const outer = screen.getByTestId('s');
    expect(outer.props.accessibilityHint).toBeUndefined(); // a plain View, not the effect view
    expect(outer).toHaveStyle({ boxShadow: SHADOW, borderRadius: 20, padding: 20, marginTop: 16, borderColor: 'rgba(255,255,255,0.85)' });
    expect(StyleSheet.flatten(outer.props.style).overflow).toBeUndefined();

    const layer = screen.getByTestId('glass-layer', { includeHiddenElements: true });
    expect(layer.props.accessibilityHint).toBe(effect);
    expect(layer).toHaveStyle({ position: 'absolute', top: 0, right: 0, bottom: 0, left: 0, overflow: 'hidden', borderRadius: 20 });
    expect(layer.props.pointerEvents).toBe('none');
    // The effect view carries no shadow of its own: it would be clipped, and drawn twice if not.
    expect(StyleSheet.flatten(layer.props.style).boxShadow).toBeUndefined();

    expect(screen.getByTestId('glass-tint', { includeHiddenElements: true })).toHaveStyle({ backgroundColor: 'rgba(255,255,255,0.72)' });
    expect(screen.getByText('child')).toBeOnTheScreen();
  });

  it('the glass layer follows per-corner radii and a caller tint still lands on the tint layer', () => {
    on('ios', true, true);
    render(
      <ThemeProvider>
        <GlassSurface testID="s" style={{ borderTopLeftRadius: 12, borderTopRightRadius: 12, backgroundColor: '#fef3c7', boxShadow: SHADOW }} />
      </ThemeProvider>,
    );
    expect(screen.getByTestId('glass-layer', { includeHiddenElements: true })).toHaveStyle({ borderTopLeftRadius: 12, borderTopRightRadius: 12 });
    expect(screen.getByTestId('glass-tint', { includeHiddenElements: true })).toHaveStyle({ backgroundColor: '#fef3c7' });
    // The fill is the tint layer's job; on the outer view it would hide the glass behind it.
    expect(StyleSheet.flatten(screen.getByTestId('s').props.style).backgroundColor).toBeUndefined();
  });

  it('a surface without a shadow is still the effect view itself', () => {
    on('ios', true, true);
    render(<ThemeProvider><GlassSurface testID="s" style={{ borderRadius: 20 }} /></ThemeProvider>);
    expect(hintOf('s')).toBe('GlassView');
    expect(screen.queryByTestId('glass-layer', { includeHiddenElements: true })).toBeNull();
  });

  it.each([true, null])('Reduce Transparency %s: one solid view that keeps the shadow', (v) => {
    on('ios', true, true);
    mockReduce.value = v;
    render(<ThemeProvider><GlassSurface testID="s" style={style} /></ThemeProvider>);
    expect(screen.getByTestId('s')).toHaveStyle({ backgroundColor: '#ffffff', boxShadow: SHADOW });
    expect(screen.queryByTestId('glass-layer', { includeHiddenElements: true })).toBeNull();
  });
});

describe('DashboardCard', () => {
  it('iOS: casts the card shadow, so it renders as a shadow surface with the glass behind', () => {
    on('ios', true, true);
    render(<ThemeProvider><DashboardCard testID="d">{null}</DashboardCard></ThemeProvider>);
    expect(screen.getByTestId('d')).toHaveStyle({ boxShadow: shadows.card, borderRadius: 20, padding: 20 });
    expect(screen.getByTestId('glass-layer', { includeHiddenElements: true })).toHaveStyle({ borderRadius: 20 });
  });

  it('Android: keeps its elevation and declares no box shadow', () => {
    on('android');
    render(<ThemeProvider><DashboardCard testID="d">{null}</DashboardCard></ThemeProvider>);
    const style = StyleSheet.flatten(screen.getByTestId('d').props.style);
    expect(style.elevation).toBe(2);
    expect(style.boxShadow).toBeUndefined();
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
