import { StyleSheet, View } from 'react-native';
import { useTheme } from '../../theme';

/**
 * Decorative: the figure a bar stands for is always written out next to it, so the bar is hidden
 * from screen readers. The fill is clamped to its track; the caller keeps the real, uncapped
 * figure for its label (a budget at 150% still says 150%).
 */
export function ProgressBar({
  percent, color, trackColor, height = 6, testID,
}: { percent: number; color: string; trackColor?: string; height?: number; testID?: string }) {
  const c = useTheme();
  const pct = Number.isFinite(percent) ? Math.max(0, Math.min(100, percent)) : 0;
  return (
    <View
      testID={testID}
      style={[styles.track, { height, borderRadius: height / 2, backgroundColor: trackColor ?? c.border }]}
      accessibilityElementsHidden
      importantForAccessibility="no-hide-descendants"
    >
      <View
        testID={testID ? `${testID}-fill` : undefined}
        style={{ width: `${pct}%`, height, borderRadius: height / 2, backgroundColor: color }}
      />
    </View>
  );
}

const styles = StyleSheet.create({
  track: { overflow: 'hidden', alignSelf: 'stretch' },
});
