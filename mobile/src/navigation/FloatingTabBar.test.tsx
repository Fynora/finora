import { Dimensions, Platform, StyleSheet, Text, View } from 'react-native';
import { fireEvent, render, screen } from '@testing-library/react-native';
import { BottomTabBarHeightCallbackContext } from '@react-navigation/bottom-tabs';
import { FloatingTabBar } from './FloatingTabBar';
import { pillFrame } from './tabBarLayout';
import { light } from '../theme/palette';
import { shadows } from '../theme';
import { useKeyboardVisible } from '../lib/useKeyboardVisible';
import { useReduceTransparency } from '../lib/useReduceTransparency';
import { __setHapticsStateForTests } from '../lib/haptics';
import { expectHaptics } from '../test/haptics';

// The global stand-in for @react-navigation/native (src/test/setup.ts) has no CommonActions.
jest.mock('@react-navigation/native', () => ({
  CommonActions: { navigate: (name: string, params?: object) => ({ type: 'NAVIGATE', payload: { name, params } }) },
}));
// The real package cannot load under that stand-in; the pill needs only the height callback
// context, which the test imports back from this mock to provide its own callback.
jest.mock('@react-navigation/bottom-tabs', () => ({
  BottomTabBarHeightCallbackContext: require('react').createContext(undefined),
}));
jest.mock('../lib/useKeyboardVisible', () => ({ useKeyboardVisible: jest.fn(() => false) }));
jest.mock('../lib/useReduceTransparency', () => ({ useReduceTransparency: jest.fn(() => false) }));

const ROUTES = ['Home', 'Transactions', 'Import', 'Insights', 'More'];

function setup({ index = 0, defaultPrevented = false, bottom = 34 } = {}) {
  const routes = ROUTES.map((name) => ({ key: `${name}-k`, name, params: undefined }));
  const descriptors = Object.fromEntries(routes.map((r) => [r.key, {
    options: {
      tabBarActiveTintColor: '#111111',
      tabBarInactiveTintColor: '#999999',
      tabBarIcon: ({ color }: { color: string }) => <Text>{`icon ${r.name} ${color}`}</Text>,
      ...(r.name === 'Import' ? { tabBarButton: () => <View accessible accessibilityLabel="Quick actions" accessibilityRole="button" /> } : {}),
    },
  }]));
  const navigation = { emit: jest.fn(() => ({ defaultPrevented })), dispatch: jest.fn() };
  const onHeight = jest.fn();
  const tree = () => (
    <BottomTabBarHeightCallbackContext.Provider value={onHeight}>
      <FloatingTabBar
        state={{ key: 'tabs-k', index, routes } as never}
        descriptors={descriptors as never}
        navigation={navigation as never}
        insets={{ top: 0, bottom, left: 0, right: 0 }}
      />
    </BottomTabBarHeightCallbackContext.Provider>
  );
  const view = render(tree());
  return { navigation, onHeight, rerenderSame: () => view.rerender(tree()) };
}

beforeEach(() => {
  (useKeyboardVisible as jest.Mock).mockReturnValue(false);
  (useReduceTransparency as jest.Mock).mockReturnValue(false);
});

describe('FloatingTabBar', () => {
  it('shows Home, Transactions, "+", Insights and More, in that order', () => {
    setup();
    const order = screen.getAllByRole('button').map((b) => b.props.accessibilityLabel);
    expect(order).toEqual(['Home', 'Transactions', 'Quick actions', 'Insights', 'More']);
  });

  it('marks the row as a tab list, so screen readers announce each tab and its position', () => {
    // Same as the library's own bar (BottomTabBar.tsx: role="tablist" on the row). Without it
    // VoiceOver and TalkBack read the tabs as unrelated buttons, with no "tab, 2 of 5".
    setup();
    expect(screen.getByTestId('tab-pill-glass').props.accessibilityRole).toBe('tablist');
  });

  it('marks the open tab selected and colours it with the active tint', () => {
    setup({ index: 1 });
    expect(screen.getByTestId('tab-Transactions')).toBeSelected();
    expect(screen.getByTestId('tab-Home')).not.toBeSelected();
    expect(screen.getByText('icon Transactions #111111')).toBeTruthy();
    expect(screen.getByText('icon Home #999999')).toBeTruthy();
  });

  it('a press on another tab sends tabPress and navigates to it', () => {
    const { navigation } = setup({ index: 0 });
    fireEvent.press(screen.getByTestId('tab-Insights'));
    expect(navigation.emit).toHaveBeenCalledWith({
      type: 'tabPress', target: 'Insights-k', canPreventDefault: true,
      data: { behavior: { scrollToTop: false, popToTop: false } },
    });
    expect(navigation.dispatch).toHaveBeenCalledWith({ type: 'NAVIGATE', payload: { name: 'Insights', params: undefined }, target: 'tabs-k' });
  });

  it('a press on the open tab sends popToTop and does not navigate', () => {
    const { navigation } = setup({ index: 4 });
    fireEvent.press(screen.getByTestId('tab-More'));
    expect(navigation.emit).toHaveBeenCalledWith(expect.objectContaining({
      target: 'More-k', data: { behavior: { scrollToTop: true, popToTop: true } },
    }));
    expect(navigation.dispatch).not.toHaveBeenCalled();
  });

  it('one selection per tab press, also on the tab already open', () => {
    setup({ index: 0 });
    fireEvent.press(screen.getByTestId('tab-Transactions'));
    expectHaptics({ selection: 1 });
    fireEvent.press(screen.getByTestId('tab-Home'));
    expectHaptics({ selection: 2 });
  });

  it('no selection when haptics are off', () => {
    __setHapticsStateForTests('off');
    setup({ index: 0 });
    fireEvent.press(screen.getByTestId('tab-Transactions'));
    expectHaptics({});
  });

  it('no selection on a long press, which only sends tabLongPress', () => {
    setup({ index: 0 });
    fireEvent(screen.getByTestId('tab-Insights'), 'longPress');
    expectHaptics({});
  });

  it('does not navigate when a listener prevents the press', () => {
    const { navigation } = setup({ index: 0, defaultPrevented: true });
    fireEvent.press(screen.getByTestId('tab-Insights'));
    expect(navigation.dispatch).not.toHaveBeenCalled();
  });

  it('a long press sends tabLongPress', () => {
    const { navigation } = setup();
    fireEvent(screen.getByTestId('tab-Insights'), 'longPress');
    expect(navigation.emit).toHaveBeenCalledWith({ type: 'tabLongPress', target: 'Insights-k' });
  });

  it('keeps labels on one line and caps their growth at 1.3 times', () => {
    setup();
    const label = screen.getByText('Transactions');
    expect(label.props.numberOfLines).toBe(1);
    expect(label.props.maxFontSizeMultiplier).toBe(1.3);
  });

  it('shrinks a label that does not fit its slot instead of cutting it off', () => {
    // Seen on the simulator at the largest text size: "Transactions" at 1.3 times showed as
    // "Transacti..."; a 320 pt phone gives each slot about 56 pt even at the default size.
    setup();
    const label = screen.getByText('Transactions');
    expect(label.props.adjustsFontSizeToFit).toBe(true);
    expect(label.props.minimumFontScale).toBe(0.7);
  });

  it('reports the height it covers to the navigator', () => {
    const { onHeight } = setup({ bottom: 34 });
    expect(onHeight).toHaveBeenLastCalledWith(92);
  });

  it('sits in its frame: 16 pt from each side on a phone, at most 480 pt wide', () => {
    setup({ bottom: 34 });
    const frame = pillFrame(Dimensions.get('window').width, 34);
    expect(screen.getByTestId('tab-pill')).toHaveStyle({ left: frame.left, width: frame.width, bottom: 28, height: 64 });
  });

  it('is pressable and readable while the keyboard is down', () => {
    setup();
    const pill = screen.getByTestId('tab-pill');
    expect(pill.props.pointerEvents).toBe('box-none');
    expect(pill.props.accessibilityElementsHidden).toBe(false);
  });

  it('cannot be pressed and is hidden from screen readers while the keyboard is up', () => {
    (useKeyboardVisible as jest.Mock).mockReturnValue(true);
    setup();
    const pill = screen.getByTestId('tab-pill', { includeHiddenElements: true });
    expect(pill.props.pointerEvents).toBe('none');
    expect(pill.props.accessibilityElementsHidden).toBe(true);
    expect(pill.props.importantForAccessibility).toBe('no-hide-descendants');
  });

  it('pill comes back when the keyboard closes', () => {
    (useKeyboardVisible as jest.Mock).mockReturnValue(true);
    const { rerenderSame } = setup();
    expect(screen.getByTestId('tab-pill', { includeHiddenElements: true }).props.pointerEvents).toBe('none');
    (useKeyboardVisible as jest.Mock).mockReturnValue(false);
    rerenderSame();
    expect(screen.getByTestId('tab-pill').props.pointerEvents).toBe('box-none');
    expect(screen.getByTestId('tab-pill').props.accessibilityElementsHidden).toBe(false);
  });

  it('uses the solid card colour when Reduce Transparency is on', () => {
    (useReduceTransparency as jest.Mock).mockReturnValue(true);
    setup();
    expect(screen.getByTestId('tab-pill-glass')).toHaveStyle({ backgroundColor: light.card });
  });
});

// Android's elevation shadow shows through the see-through pill as a grey rim round a white box
// (Pixel 10 emulator, 2026-10-16). The box shadow sits on the unclipped wrapper on both platforms.
describe.each(['ios', 'android'])('the pill shadow on %s', (os) => {
  const original = Platform.OS;
  beforeEach(() => { (Platform as { OS: string }).OS = os; });
  afterEach(() => { (Platform as { OS: string }).OS = original; });

  it('is the card box shadow on the wrapper, with no elevation anywhere', () => {
    setup();
    const wrap = StyleSheet.flatten(screen.getByTestId('tab-pill').props.style);
    const glass = StyleSheet.flatten(screen.getByTestId('tab-pill-glass').props.style);
    expect(wrap.boxShadow).toEqual(shadows.card);
    expect(wrap.elevation).toBeUndefined();
    expect(glass.elevation).toBeUndefined();
  });
});

// Android has no blur, so a see-through pill let the page show through its labels: a card's
// figures and bank logos read through "Insights" (Pixel 10 emulator, 2026-10-16). The elevation it
// had before painted the pill's middle white by accident; it now has the solid card fill instead.
it('fills the pill solid on Android, where nothing blurs what is behind it', () => {
  const original = Platform.OS;
  (Platform as { OS: string }).OS = 'android';
  try {
    setup();
    expect(StyleSheet.flatten(screen.getByTestId('tab-pill-glass').props.style).backgroundColor).toBe(light.card);
  } finally {
    (Platform as { OS: string }).OS = original;
  }
});
