import { render } from '@testing-library/react-native';
import { RefreshControl, Text } from 'react-native';
import { AppRefreshControl } from './AppRefreshControl';
import { expectHaptics } from '../test/haptics';
import { __setHapticsStateForTests } from '../lib/haptics';

// React Native calls the native control's onRefresh when a pull is committed; that is the handler
// this reaches, the inner RefreshControl's.
it('one impact when a pull is committed, then the refresh runs', () => {
  const onRefresh = jest.fn();
  const view = render(<AppRefreshControl refreshing={false} onRefresh={onRefresh} />);
  view.UNSAFE_getByType(RefreshControl).props.onRefresh();
  expect(onRefresh).toHaveBeenCalledTimes(1);
  expectHaptics({ impact: 1 });
});

// Android's ScrollView does not nest the control; it clones the refreshControl element with a style
// and the scroll view itself as its child (ScrollView.js, Platform.OS === 'android'). The wrapper
// must hand both straight to RefreshControl, or pull to refresh renders an empty Android screen.
it('passes a style and a child through, as Android ScrollView supplies them', () => {
  const view = render(
    <AppRefreshControl refreshing={false} onRefresh={jest.fn()} style={{ flex: 1 }}>
      <Text>the list</Text>
    </AppRefreshControl>
  );
  const inner = view.UNSAFE_getByType(RefreshControl);
  expect(inner.props.style).toEqual({ flex: 1 });
  // React Native's jest stand-in for the native control does not draw children, so the hand-off
  // is checked on the props the real control would render them from.
  expect(inner.props.children.props.children).toBe('the list');
});

it('the refresh still runs, without a haptic, when haptics are off', () => {
  __setHapticsStateForTests('off');
  const onRefresh = jest.fn();
  const view = render(<AppRefreshControl refreshing={false} onRefresh={onRefresh} />);
  view.UNSAFE_getByType(RefreshControl).props.onRefresh();
  expect(onRefresh).toHaveBeenCalledTimes(1);
  expectHaptics({});
});
