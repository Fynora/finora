import { Switch, type SwitchProps } from 'react-native';
import { hapticSelection } from '../lib/haptics';

/**
 * React Native's Switch with the one haptic the spec gives a switch: a selection tick when the
 * person changes its value, after the change has been handled. Every switch in the app goes through
 * here (haptics.guard.test.ts), so new switches inherit it.
 *
 * `silent` is for the one switch that owns its own haptic: Settings › General › Haptics, where
 * turning haptics on fires its confirming tick inside setHapticsEnabled and turning them off must
 * fire nothing.
 */
export function AppSwitch({ onValueChange, silent = false, ...rest }: SwitchProps & { silent?: boolean }) {
  return (
    <Switch
      {...rest}
      onValueChange={onValueChange ? (next) => {
        void onValueChange(next);
        if (!silent) hapticSelection();
      } : undefined}
    />
  );
}
