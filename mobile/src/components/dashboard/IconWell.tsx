import type { ComponentProps } from 'react';
import { StyleSheet, View } from 'react-native';
import Ionicons from '@expo/vector-icons/Ionicons';
import { radius, useTheme } from '../../theme';
import { withAlpha } from '../../theme/glass';

type IoniconName = ComponentProps<typeof Ionicons>['name'];
type Tone = 'neutral' | 'success' | 'warning' | 'brass';

/** Alpha of the ink wash behind a neutral icon. primitives.test.tsx measures it on both themes. */
export const NEUTRAL_WASH = 0.08;

/**
 * A tinted holder for a row or tile icon. The neutral well is a wash of the theme's own ink, not
 * `primaryLight`: `primaryLight` measured 1.01:1 against a dark-theme glass card (the well
 * vanished) and 1.03:1 against a light one. Ink at 0.08 measured 1.25:1 dark and 1.17:1 light,
 * the same on every backdrop pixel because it is composited over whatever the card is. The
 * hairline edge (`border`, 1.46:1 dark) carries the rest of the shape.
 */
export function IconWell({
  name, tone = 'neutral', round = false, size = 40, testID,
}: { name: IoniconName; tone?: Tone; round?: boolean; size?: number; testID?: string }) {
  const c = useTheme();
  const fill = { neutral: withAlpha(c.ink, NEUTRAL_WASH), success: c.successBg, warning: c.warningBg, brass: c.brassBg }[tone];
  const ink = { neutral: c.ink, success: c.successInk, warning: c.warningInk, brass: c.brassInk }[tone];
  return (
    <View
      testID={testID}
      style={[
        styles.well,
        { width: size, height: size, borderRadius: round ? size / 2 : radius.lg, backgroundColor: fill, borderColor: c.border },
      ]}
      accessibilityElementsHidden
      importantForAccessibility="no-hide-descendants"
    >
      <Ionicons name={name} size={Math.round(size / 2)} color={ink} />
    </View>
  );
}

const styles = StyleSheet.create({
  well: { alignItems: 'center', justifyContent: 'center', borderWidth: StyleSheet.hairlineWidth },
});
