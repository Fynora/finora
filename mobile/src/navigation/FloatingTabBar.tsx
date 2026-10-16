import { useContext, useEffect, useLayoutEffect } from 'react';
import { Platform, Pressable, StyleSheet, Text, View, useWindowDimensions } from 'react-native';
import Animated, { useAnimatedStyle, useReducedMotion, useSharedValue, withTiming } from 'react-native-reanimated';
import { CommonActions } from '@react-navigation/native';
import {
  BottomTabBarHeightCallbackContext,
  type BottomTabBarButtonProps,
  type BottomTabBarProps,
} from '@react-navigation/bottom-tabs';
import { GlassSurface } from '../components/GlassSurface';
import { hapticSelection } from '../lib/haptics';
import { useKeyboardVisible } from '../lib/useKeyboardVisible';
import { shadows, typography, useTheme } from '../theme';
import { PILL_HEIGHT, TAB_LABEL_MAX_SCALE, pillCoverHeight, pillFrame, pillHideDuration } from './tabBarLayout';

/**
 * The bottom tab bar as a floating glass pill that lists scroll under. Passed to the navigator as
 * `tabBar` by AppTabs. Presses go through the same `tabPress` event and navigate action as the
 * library's own bar (BottomTabBar.tsx in @react-navigation/bottom-tabs 7.20), so tab listeners,
 * pop-to-top on a second press and Android back behave as they did. It slides away while the
 * keyboard is up. Design: docs/superpowers/specs/2026-10-15-mobile-floating-tab-pill-design.md.
 */
export function FloatingTabBar({ state, descriptors, navigation, insets }: BottomTabBarProps) {
  const c = useTheme();
  const { width: windowWidth } = useWindowDimensions();
  const frame = pillFrame(windowWidth, insets.bottom);
  const cover = pillCoverHeight(insets.bottom);

  const reportHeight = useContext(BottomTabBarHeightCallbackContext);
  useLayoutEffect(() => {
    reportHeight?.(cover);
  }, [reportHeight, cover]);

  const hidden = useKeyboardVisible();
  const reducedMotion = useReducedMotion();
  const offset = useSharedValue(0);
  useEffect(() => {
    // Far enough down that the shadow goes too.
    offset.set(withTiming(hidden ? cover + 16 : 0, { duration: pillHideDuration(reducedMotion) }));
  }, [hidden, cover, reducedMotion, offset]);
  const slide = useAnimatedStyle(() => ({ transform: [{ translateY: offset.get() }] }));

  return (
    <Animated.View
      testID="tab-pill"
      pointerEvents={hidden ? 'none' : 'box-none'}
      accessibilityElementsHidden={hidden}
      importantForAccessibility={hidden ? 'no-hide-descendants' : 'auto'}
      style={[
        styles.wrap,
        { left: frame.left, width: frame.width, bottom: frame.bottom, height: frame.height },
        styles.shadow,
        slide,
      ]}
    >
      {/* "tablist", as on the library's own bar: it is what makes VoiceOver and TalkBack read the
          items as tabs with their position ("tab, 2 of 5"). */}
      <GlassSurface
        testID="tab-pill-glass"
        accessibilityRole="tablist"
        // Solid on Android, which has no blur: see-through, the page read through the labels.
        style={[styles.pill, Platform.OS === 'android' && [styles.android, { backgroundColor: c.card /* glass-exempt: Android has no blur; see-through, the page read through the tab labels */ }]]}
      >
        {state.routes.map((route, index) => {
          const focused = index === state.index;
          const { options } = descriptors[route.key];

          if (options.tabBarButton) {
            return (
              <View key={route.key} style={styles.item}>
                {options.tabBarButton({ children: null } as unknown as BottomTabBarButtonProps)}
              </View>
            );
          }

          const label = typeof options.tabBarLabel === 'string' ? options.tabBarLabel : options.title ?? route.name;
          const color = focused ? options.tabBarActiveTintColor ?? c.primary : options.tabBarInactiveTintColor ?? c.muted;

          const onPress = () => {
            // The one navigation control with a haptic. A press on the open tab is the same tap,
            // so it ticks too.
            hapticSelection();
            const event = navigation.emit({
              type: 'tabPress',
              target: route.key,
              canPreventDefault: true,
              data: {
                behavior: {
                  scrollToTop: focused && options.tabBarRepeatedPressBehavior?.scrollToTop !== false,
                  popToTop: focused && options.tabBarRepeatedPressBehavior?.popToTop !== false,
                },
              },
            });
            if (!focused && !event.defaultPrevented) {
              navigation.dispatch({ ...CommonActions.navigate(route.name, route.params), target: state.key });
            }
          };
          const onLongPress = () => navigation.emit({ type: 'tabLongPress', target: route.key });

          return (
            <Pressable
              key={route.key}
              testID={`tab-${route.name}`}
              onPress={onPress}
              onLongPress={onLongPress}
              style={styles.item}
              // Same as the library's own bar: iOS does not announce role "tab" as expected.
              accessibilityRole={Platform.OS === 'ios' ? 'button' : 'tab'}
              accessibilityState={{ selected: focused }}
              accessibilityLabel={options.tabBarAccessibilityLabel ?? label}
              accessibilityLargeContentTitle={label}
              accessibilityShowsLargeContentViewer
            >
              {options.tabBarIcon?.({ focused, color, size: 24 })}
              {/* Shrinks rather than cuts off: at 1.3 times "Transactions" is wider than its slot,
                  and a 320 pt phone gives each slot about 56 pt even at the default size. */}
              <Text
                numberOfLines={1}
                maxFontSizeMultiplier={TAB_LABEL_MAX_SCALE}
                adjustsFontSizeToFit
                minimumFontScale={0.7}
                style={[focused ? typography.labelS : typography.caption, styles.label, { color }]}
              >
                {label}
              </Text>
            </Pressable>
          );
        })}
      </GlassSurface>
    </Animated.View>
  );
}

const styles = StyleSheet.create({
  wrap: { position: 'absolute', borderRadius: PILL_HEIGHT / 2 },
  pill: {
    flex: 1,
    flexDirection: 'row',
    alignItems: 'center',
    borderRadius: PILL_HEIGHT / 2,
    paddingHorizontal: 4,
    paddingVertical: 6,
  },
  // Same shadow as the Transactions day card (LedgerDayCard): a shadow on the clipped native glass
  // never draws on iOS, so it goes on the unclipped wrapper. Android casts the same one there: its
  // elevation shadow on the fill showed through as a grey rim round a white box (2026-10-16).
  shadow: { boxShadow: shadows.card },
  android: { overflow: 'hidden' },
  item: { flex: 1, minWidth: 44, height: '100%', alignItems: 'center', justifyContent: 'center' },
  label: { fontSize: 10, lineHeight: 13, marginTop: 2 },
});
