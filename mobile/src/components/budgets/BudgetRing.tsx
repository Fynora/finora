import type { ReactNode } from 'react';
import { StyleSheet, View } from 'react-native';
import Svg, { Circle } from 'react-native-svg';
import { useTheme } from '../../theme';
import type { BudgetGroup } from '../../lib/budgetPace';
import { groupFill } from './PaceBar';

/**
 * Thick outer arc = share of the total budget spent; thin inner arc = share of the month gone.
 * Same drawing as the web ring (frontend/src/components/budgets/BudgetRing.tsx).
 */
export function BudgetRing({ spentFrac, monthFrac, group, size = 168, children }: {
  spentFrac: number; monthFrac: number; group: BudgetGroup; size?: number; children?: ReactNode;
}) {
  const c = useTheme();
  const outerStroke = Math.round(size * 0.08);
  const outerR = (size - outerStroke) / 2;
  const innerStroke = 3;
  const innerR = outerR - outerStroke / 2 - 9;
  const mid = size / 2;
  const arc = (r: number, frac: number) => {
    const circ = 2 * Math.PI * r;
    return `${Math.max(0, Math.min(1, frac)) * circ} ${circ}`;
  };
  return (
    <View style={{ width: size, height: size }}>
      <Svg width={size} height={size} style={styles.rotated}>
        <Circle cx={mid} cy={mid} r={outerR} fill="none" stroke={c.border} strokeWidth={outerStroke} />
        {spentFrac > 0 ? (
          <Circle
            testID="spent-arc" cx={mid} cy={mid} r={outerR} fill="none" stroke={groupFill(c, group)}
            strokeWidth={outerStroke} strokeLinecap="round" strokeDasharray={arc(outerR, spentFrac)}
          />
        ) : null}
        <Circle cx={mid} cy={mid} r={innerR} fill="none" stroke={c.border} strokeWidth={innerStroke} />
        <Circle
          cx={mid} cy={mid} r={innerR} fill="none" stroke={c.muted}
          strokeWidth={innerStroke} strokeLinecap="round" strokeDasharray={arc(innerR, monthFrac)}
        />
      </Svg>
      <View style={styles.centre}>{children}</View>
    </View>
  );
}

const styles = StyleSheet.create({
  rotated: { transform: [{ rotate: '-90deg' }] },
  centre: { position: 'absolute', top: 0, right: 0, bottom: 0, left: 0, alignItems: 'center', justifyContent: 'center' },
});
