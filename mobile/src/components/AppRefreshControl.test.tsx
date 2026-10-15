import { render } from '@testing-library/react-native';
import { RefreshControl } from 'react-native';
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

it('the refresh still runs, without a haptic, when haptics are off', () => {
  __setHapticsStateForTests('off');
  const onRefresh = jest.fn();
  const view = render(<AppRefreshControl refreshing={false} onRefresh={onRefresh} />);
  view.UNSAFE_getByType(RefreshControl).props.onRefresh();
  expect(onRefresh).toHaveBeenCalledTimes(1);
  expectHaptics({});
});
