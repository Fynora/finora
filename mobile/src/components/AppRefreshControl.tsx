import { RefreshControl, type RefreshControlProps } from 'react-native';
import { hapticImpact } from '../lib/haptics';

/**
 * React Native's RefreshControl with the spec's pull-to-refresh haptic: one impact when the pull is
 * committed. onRefresh is the only event React Native gives -- there is no earlier "threshold
 * reached" -- so that is where it fires. Every pull to refresh goes through here
 * (haptics.guard.test.ts).
 *
 * A plain props pass-through, so Android's ScrollView, which clones its refreshControl element and
 * hands it the list as a child, works through this wrapper unchanged.
 */
export function AppRefreshControl({ onRefresh, ...rest }: RefreshControlProps) {
  return (
    <RefreshControl
      {...rest}
      onRefresh={() => {
        hapticImpact();
        onRefresh?.();
      }}
    />
  );
}
