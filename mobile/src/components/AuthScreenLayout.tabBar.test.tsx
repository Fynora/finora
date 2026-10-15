import { ScrollView, StyleSheet, Text } from 'react-native';
import { render, screen } from '@testing-library/react-native';
import { AuthScreenLayout } from './AuthScreenLayout';
import { TabBarSpaceContext } from '../navigation/useTabBarSpace';

// The sign-in screens use this layout outside the tabs; Verify Email Change uses it inside them.
function scrollStyle() {
  return StyleSheet.flatten(screen.UNSAFE_getByType(ScrollView).props.contentContainerStyle);
}

it('outside the tabs keeps its own bottom padding', () => {
  render(<AuthScreenLayout title="Sign in"><Text>form</Text></AuthScreenLayout>);
  // Safe-area insets are 0 under the test setup; spacing.lg is 24.
  expect(scrollStyle().paddingBottom).toBe(24);
});

it('inside the tabs leaves room for the pill instead of the home indicator', () => {
  render(
    <TabBarSpaceContext.Provider value={92}>
      <AuthScreenLayout title="Verify your new email"><Text>form</Text></AuthScreenLayout>
    </TabBarSpaceContext.Provider>
  );
  // 92 cover + 12 clearance + 24 of the layout's own padding.
  expect(scrollStyle().paddingBottom).toBe(128);
});
