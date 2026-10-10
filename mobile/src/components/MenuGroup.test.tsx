import { createRef } from 'react';
import type { View } from 'react-native';
import { fireEvent, render, screen } from '@testing-library/react-native';
import { MenuGroup, MenuRow } from './MenuGroup';

const noop = () => {};

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
