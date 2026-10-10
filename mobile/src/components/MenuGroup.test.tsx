import { createRef } from 'react';
import { Dimensions, Platform, StyleSheet, type View } from 'react-native';
import { fireEvent, render, screen } from '@testing-library/react-native';
import { MenuGroup, MenuRow } from './MenuGroup';

const noop = () => {};

// Spying on Dimensions.get, as DashboardScreen.test.tsx does: useWindowDimensions reads through it,
// and mocking the react-native module wholesale breaks its lazy native getters under jest-expo.
const dimensionsGetSpy = jest.spyOn(Dimensions, 'get');
const atTextScale = (fontScale: number) =>
  dimensionsGetSpy.mockReturnValue({ width: 402, height: 874, scale: 3, fontScale });

// Back to the default size before every test, so a large scale never leaks into the next one.
beforeEach(() => { atTextScale(1); });

test('draws a divider between rows and none after the last', () => {
  render(
    <MenuGroup label="Money">
      <MenuRow icon="wallet-outline" label="Accounts" onPress={noop} />
      <MenuRow icon="flag-outline" label="Goals" onPress={noop} />
      <MenuRow icon="card-outline" label="Subscription" onPress={noop} />
    </MenuGroup>
  );
  expect(screen.getAllByTestId('menu-divider')).toHaveLength(2);
});

test('a single row gets no divider at all', () => {
  render(
    <MenuGroup label="App">
      <MenuRow icon="settings-outline" label="Settings" onPress={noop} />
    </MenuGroup>
  );
  expect(screen.queryByTestId('menu-divider')).toBeNull();
});

test('a row left out at render time takes its divider with it', () => {
  const paused = false as boolean;
  render(
    <MenuGroup>
      <MenuRow icon="wallet-outline" label="Accounts" onPress={noop} />
      {paused ? <MenuRow icon="link-outline" label="Connected Apps" onPress={noop} /> : null}
      <MenuRow icon="flag-outline" label="Goals" onPress={noop} />
    </MenuGroup>
  );
  expect(screen.getAllByTestId('menu-divider')).toHaveLength(1);
});

test('the label is announced as a heading, and a group without one renders no heading', () => {
  const { rerender } = render(
    <MenuGroup label="Insights">
      <MenuRow icon="bar-chart-outline" label="Reports" onPress={noop} />
    </MenuGroup>
  );
  expect(screen.getByRole('header', { name: 'Insights' })).toBeTruthy();

  rerender(
    <MenuGroup>
      <MenuRow icon="bar-chart-outline" label="Reports" onPress={noop} />
    </MenuGroup>
  );
  expect(screen.queryByRole('header')).toBeNull();
});

test('pressing a row runs its handler once', () => {
  const onPress = jest.fn();
  render(<MenuGroup><MenuRow icon="wallet-outline" label="Accounts" onPress={onPress} /></MenuGroup>);
  fireEvent.press(screen.getByText('Accounts'));
  expect(onPress).toHaveBeenCalledTimes(1);
});

test('shows the description and the current value when given, and neither when not', () => {
  const { rerender } = render(
    <MenuGroup>
      <MenuRow icon="options-outline" label="General" description="Preferences, timezone, theme" value="Dark" onPress={noop} />
    </MenuGroup>
  );
  expect(screen.getByText('Preferences, timezone, theme')).toBeTruthy();
  expect(screen.getByText('Dark')).toBeTruthy();

  rerender(<MenuGroup><MenuRow icon="options-outline" label="General" onPress={noop} /></MenuGroup>);
  expect(screen.queryByText('Preferences, timezone, theme')).toBeNull();
  expect(screen.queryByText('Dark')).toBeNull();
});

test('defaults to a button, and takes the role it is given', () => {
  render(
    <MenuGroup>
      <MenuRow icon="wallet-outline" label="Accounts" onPress={noop} />
      <MenuRow icon="lock-closed-outline" label="Privacy Policy" accessibilityRole="link" onPress={noop} />
    </MenuGroup>
  );
  expect(screen.getByRole('button', { name: 'Accounts' })).toBeTruthy();
  expect(screen.getByRole('link', { name: 'Privacy Policy' })).toBeTruthy();
});

test('hands its ref to the pressable row, so a caller can measure it', () => {
  const ref = createRef<View>();
  render(<MenuGroup><MenuRow ref={ref} icon="wallet-outline" label="Accounts" onPress={noop} /></MenuGroup>);
  expect(ref.current).not.toBeNull();
});

describe('at the system text sizes', () => {
  // jest-expo's stand-in for an icon renders the glyph's name as text, which is what these look for.
  const row = (
    <MenuGroup>
      <MenuRow icon="options-outline" label="General" description="Preferences, timezone, theme" value="System" onPress={noop} />
    </MenuGroup>
  );

  // 1.35 is iOS's largest non-accessibility size and 1.5 Android's; both sit under the line.
  test.each([1, 1.35, 1.5])('at %s the row keeps its icon and shows the value beside the label, on one line', (scale) => {
    atTextScale(scale);
    render(row);
    expect(screen.getByText('options-outline', { includeHiddenElements: true })).toBeTruthy();
    expect(screen.getByText('System').props.numberOfLines).toBe(1);
  });

  // 1.64 is iOS's first accessibility size, 3.12 its largest, 2 Android's largest.
  test.each([1.6, 1.64, 2, 3.12])('at %s the row drops its icon and lets the value wrap under the label', (scale) => {
    atTextScale(scale);
    render(row);
    expect(screen.queryByText('options-outline', { includeHiddenElements: true })).toBeNull();
    expect(screen.getByText('System').props.numberOfLines).toBeUndefined();
    // Nothing is lost, only moved: the screen reader still gets label, description and value.
    expect(screen.getByText('General')).toBeTruthy();
    expect(screen.getByText('Preferences, timezone, theme')).toBeTruthy();
  });

  test('a row with no value still drops its icon at accessibility sizes', () => {
    atTextScale(3.12);
    render(<MenuGroup><MenuRow icon="card-outline" label="Subscription" onPress={noop} /></MenuGroup>);
    expect(screen.queryByText('card-outline', { includeHiddenElements: true })).toBeNull();
    expect(screen.getByRole('button', { name: 'Subscription' })).toBeTruthy();
  });
});

describe('the trailing icon says where the row leads', () => {
  test('a row that opens another screen wears the chevron', () => {
    render(<MenuGroup><MenuRow icon="wallet-outline" label="Accounts" onPress={noop} /></MenuGroup>);
    expect(screen.getByText('chevron-forward', { includeHiddenElements: true })).toBeTruthy();
    expect(screen.queryByText('open-outline', { includeHiddenElements: true })).toBeNull();
  });

  test('a link row, which leaves the app for the browser, wears the open-in-browser glyph instead', () => {
    render(<MenuGroup><MenuRow icon="lock-closed-outline" label="Privacy Policy" accessibilityRole="link" onPress={noop} /></MenuGroup>);
    expect(screen.getByText('open-outline', { includeHiddenElements: true })).toBeTruthy();
    expect(screen.queryByText('chevron-forward', { includeHiddenElements: true })).toBeNull();
  });
});

describe('press feedback', () => {
  // fireEvent(el, 'pressIn') does not flip Pressable's `pressed` flag -- that lives in the responder
  // system -- so the press is started the way a finger starts it.
  const touchDown = { persist() {}, nativeEvent: { touches: [], changedTouches: [], pageX: 1, pageY: 1, locationX: 1, locationY: 1, timestamp: Date.now(), identifier: 1, target: 1 } };
  const rowBackground = () =>
    StyleSheet.flatten(screen.getByRole('button', { name: 'Accounts' }).props.style)?.backgroundColor;
  const originalOS = Platform.OS;
  afterEach(() => { Platform.OS = originalOS; });

  test('on iOS the row tints while it is held, and is clear before', () => {
    Platform.OS = 'ios';
    render(<MenuGroup><MenuRow icon="wallet-outline" label="Accounts" onPress={noop} /></MenuGroup>);
    expect(rowBackground()).toBeUndefined();
    fireEvent(screen.getByRole('button', { name: 'Accounts' }), 'responderGrant', touchDown);
    expect(rowBackground()).toEqual(expect.any(String));
  });

  // No Android twin of the test above: with Platform.OS set to 'android', holding a Pressable
  // drives the native ripple, which jest-expo does not provide (it throws on hotspotUpdate before
  // any style can be read). That throw is itself the ripple path engaging; the tint is gated on
  // Platform.OS === 'ios' in the component, one condition, read there.
});
