import { useEffect, type ReactNode } from 'react';
import { Image, Text } from 'react-native';
import { render, screen } from '@testing-library/react-native';
import { GlassScreen } from './GlassScreen';
import { ThemeProvider, useThemeSetting } from '../theme';

jest.mock('../lib/useReduceTransparency', () => ({ useReduceTransparency: jest.fn(() => false) }));
const { useReduceTransparency } = jest.requireMock('../lib/useReduceTransparency');
afterEach(() => useReduceTransparency.mockReturnValue(false));

// Same approach as AccountsCard.test.tsx: flip dark on via the real public setter rather than
// mocking react-native's useColorScheme, so the actual resolution path is what's exercised.
function ForceDarkTheme({ children }: { children: ReactNode }) {
  const { setSetting } = useThemeSetting();
  useEffect(() => { setSetting('dark'); }, [setSetting]);
  return <>{children}</>;
}

it('paints opaque bg and the mesh behind children', () => {
  render(<ThemeProvider><GlassScreen testID="root"><Text>hi</Text></GlassScreen></ThemeProvider>);
  const root = screen.getByTestId('root');
  expect(root).toHaveStyle({ backgroundColor: '#F8FAFC' });
  expect(screen.getByTestId('glass-mesh', { includeHiddenElements: true })).toBeTruthy();
  expect(screen.getByText('hi')).toBeTruthy();
});

it('mesh is decorative: no touches, hidden from screen readers', () => {
  render(<ThemeProvider><GlassScreen /></ThemeProvider>);
  const mesh = screen.getByTestId('glass-mesh', { includeHiddenElements: true });
  expect(mesh.props.pointerEvents).toBe('none');
  expect(mesh.props.accessibilityElementsHidden).toBe(true);
  expect(mesh.props.importantForAccessibility).toBe('no-hide-descendants');
});

it('picks the dark mesh and dark bg when the theme resolves dark', () => {
  render(
    <ThemeProvider>
      <ForceDarkTheme><GlassScreen testID="root" /></ForceDarkTheme>
    </ThemeProvider>,
  );
  expect(screen.getByTestId('root')).toHaveStyle({ backgroundColor: '#15171C' });
  expect(screen.UNSAFE_getByType(Image).props.source).toBe(require('../../assets/glass/mesh-dark.png'));
});

it('picks the light mesh when the theme resolves light', () => {
  render(<ThemeProvider><GlassScreen /></ThemeProvider>);
  expect(screen.UNSAFE_getByType(Image).props.source).toBe(require('../../assets/glass/mesh-light.png'));
});

it('works with no ThemeProvider at all, like every other screen test in this repo', () => {
  render(<GlassScreen testID="root" />);
  expect(screen.getByTestId('root')).toHaveStyle({ backgroundColor: '#F8FAFC' });
});

it.each([true, null])('renders no mesh when Reduce Transparency is %s (on, or not yet known)', (v) => {
  useReduceTransparency.mockReturnValue(v);
  render(<ThemeProvider><GlassScreen /></ThemeProvider>);
  expect(screen.queryByTestId('glass-mesh', { includeHiddenElements: true })).toBeNull();
});

it('caller style wins for layout but cannot make the root transparent by omission', () => {
  render(<ThemeProvider><GlassScreen testID="root" style={{ paddingTop: 20 }} /></ThemeProvider>);
  expect(screen.getByTestId('root')).toHaveStyle({ paddingTop: 20, backgroundColor: '#F8FAFC' });
});
