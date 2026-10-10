import { Children, Fragment, type ComponentProps, type ReactNode, type Ref } from 'react';
import { Pressable, StyleSheet, Text, useWindowDimensions, View, type AccessibilityRole } from 'react-native';
import Ionicons from '@expo/vector-icons/Ionicons';
import { Card } from './Card';
import { spacing, useTheme } from '../theme';

export type MenuIcon = ComponentProps<typeof Ionicons>['name'];

/**
 * From the first accessibility text size up (iOS reports 1.64 there; Android's two largest
 * settings are 1.8 and 2.0), a row gives its whole width to words. Measured on a 402pt-wide
 * phone at the largest size (3.12): with the icon and a value beside it, the label column is
 * narrower than one word, and "General" and "Subscription" broke mid-letter where the flat list
 * this replaced kept them whole. Deliberately higher than useLargeFontScale's 1.3: that one decides
 * when truncating costs information, and at 1.3 these rows still have room for everything.
 */
const ACCESSIBILITY_TEXT_SCALE = 1.6;

/**
 * One short card of menu rows under a small label -- the grouped list More and Settings share, so
 * the two screens cannot drift apart the way two private copies of the same row style did.
 *
 * The label sits OUTSIDE the card, directly on the mesh backdrop; `muted` is measured there
 * (glassContrast.test.ts, "text that may sit directly on the backdrop"). It is optional because a
 * group whose screen title already names it (Settings' own first group) would only repeat it.
 *
 * Dividers are drawn between rows here rather than as each row's bottom border, so the last row
 * of a card never carries a stray hairline and a row left out at render time (a paused feature)
 * takes its divider with it.
 */
export function MenuGroup({ label, children }: { label?: string; children: ReactNode }) {
  const c = useTheme();
  return (
    <View style={styles.group}>
      {label ? (
        <Text accessibilityRole="header" style={[styles.groupLabel, { color: c.muted }]}>{label}</Text>
      ) : null}
      <Card style={styles.card}>
        {Children.toArray(children).map((child, i) => (
          // Index keys are safe: toArray has already given each child a stable key of its own, and
          // this Fragment only pairs a child with the divider above it.
          <Fragment key={i}>
            {i > 0 ? <View testID="menu-divider" style={[styles.divider, { backgroundColor: c.border }]} /> : null}
            {child}
          </Fragment>
        ))}
      </Card>
    </View>
  );
}

/**
 * A row inside a MenuGroup: icon, label, an optional line explaining it, the current value when
 * the destination holds a single setting worth showing, and a chevron.
 *
 * Icon and chevron are decorative -- the row already announces itself by its text and role, and a
 * screen reader reading out a glyph name beside it would be noise.
 */
export function MenuRow({
  icon, label, description, value, onPress, accessibilityRole = 'button', accessibilityLabel, ref,
}: {
  icon: MenuIcon;
  label: string;
  description?: string;
  value?: string;
  onPress: () => void;
  accessibilityRole?: AccessibilityRole;
  accessibilityLabel?: string;
  ref?: Ref<View>;
}) {
  const c = useTheme();
  // Reactive, so the row re-lays-out if the system text size changes while the app is open.
  const wordsOnly = useWindowDimensions().fontScale >= ACCESSIBILITY_TEXT_SCALE;
  return (
    <Pressable
      ref={ref}
      onPress={onPress}
      style={styles.row}
      android_ripple={{ color: c.border }}
      accessibilityRole={accessibilityRole}
      accessibilityLabel={accessibilityLabel}
    >
      {wordsOnly ? null : (
        <Ionicons
          name={icon}
          size={20}
          color={c.mutedInk}
          style={styles.icon}
          accessibilityElementsHidden
          importantForAccessibility="no"
        />
      )}
      <View style={styles.rowMain}>
        <Text style={[styles.rowLabel, { color: c.ink }]}>{label}</Text>
        {description ? <Text style={[styles.rowDescription, { color: c.mutedInk }]}>{description}</Text> : null}
        {/* Under the label at accessibility sizes: beside it, the value takes its width first and
            leaves the label whatever remains. */}
        {value && wordsOnly ? <Text style={[styles.rowValueStacked, { color: c.muted }]}>{value}</Text> : null}
      </View>
      {value && !wordsOnly ? <Text style={[styles.rowValue, { color: c.muted }]} numberOfLines={1}>{value}</Text> : null}
      <Ionicons
        name="chevron-forward"
        size={18}
        color={c.muted}
        accessibilityElementsHidden
        importantForAccessibility="no"
      />
    </Pressable>
  );
}

const styles = StyleSheet.create({
  group: { marginTop: spacing.md },
  groupLabel: { fontSize: 13, fontWeight: '600', marginLeft: spacing.xs, marginBottom: 6 },
  card: { paddingVertical: 0 },
  divider: { height: StyleSheet.hairlineWidth },
  row: { flexDirection: 'row', alignItems: 'center', paddingVertical: 12, minHeight: 48 },
  icon: { marginRight: 12 },
  rowMain: { flex: 1, marginRight: spacing.sm },
  rowLabel: { fontSize: 15 },
  rowDescription: { fontSize: 12, marginTop: 2 },
  rowValue: { fontSize: 13, marginRight: spacing.xs, flexShrink: 1 },
  rowValueStacked: { fontSize: 13, marginTop: 2 },
});
