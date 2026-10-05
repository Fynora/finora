import { useContext, useEffect, useRef, useState, useSyncExternalStore } from 'react';
import { AccessibilityInfo, Animated, Pressable, StyleSheet, Text, View, type GestureResponderEvent } from 'react-native';
import { SafeAreaInsetsContext } from 'react-native-safe-area-context';
import { getTopAlertContainer, subscribeAppAlerts } from '../lib/appAlert';
import { AppBanner, getCurrentAppBanner, subscribeAppBanner, type AppBannerEntry } from '../lib/appBanner';
import { radius, spacing, useTheme } from '../theme';

/** How far up (in points) a finger has to slide the banner for it to be dismissed. */
export const SWIPE_DISMISS_DISTANCE = 24;

/**
 * Draws the banner AppBanner.show() raised: a small card pinned under the status bar, over whatever
 * screen is open. It does not dim or block the screen -- only the card itself takes touches, so
 * someone mid-task can keep working. There is no button: slide it up or tap it to dismiss, or leave
 * it and it clears by itself.
 *
 * Like AppAlert it is drawn in ONE place: the topmost open AppModal, else the root (see
 * lib/appAlert's getTopAlertContainer). A native <Modal> sits above everything in the tree, so a
 * banner drawn only at the root would be invisible behind an open sheet and the message silently
 * lost; and drawn in both places it would show twice.
 *
 * `hidden` is for the root while the app is locked: nothing is drawn and nothing can reach it, so a
 * push's text never appears over the lock screen. (AppBanner's own timer keeps running, so by the
 * time the app unlocks it is usually gone.)
 *
 * This outer part is mounted inside EVERY AppModal, so it does nothing but decide whether to show.
 * Anything that could throw in an unusual tree (the safe-area lookup) lives in BannerCard, which
 * exists only while a banner is actually on screen.
 */
export function AppBannerOverlay({ containerId, hidden = false }: { containerId: string; hidden?: boolean }) {
  const entry = useSyncExternalStore(subscribeAppBanner, getCurrentAppBanner, getCurrentAppBanner);
  const topContainer = useSyncExternalStore(subscribeAppAlerts, getTopAlertContainer, getTopAlertContainer);

  if (!entry || hidden || topContainer !== containerId) return null;
  // Keyed by the banner's id so a newer banner starts at rest, not wherever the last one was dragged.
  return <BannerCard key={entry.id} entry={entry} />;
}

function BannerCard({ entry }: { entry: AppBannerEntry }) {
  // The context, not useSafeAreaInsets(): that hook THROWS without a SafeAreaProvider, and a banner
  // must never take down a screen because of where it happens to be mounted.
  const topInset = useContext(SafeAreaInsetsContext)?.top ?? 0;
  const c = useTheme();
  const [dragY] = useState(() => new Animated.Value(0));
  const startY = useRef<number | null>(null);
  const travelled = useRef(0);

  // A screen reader user cannot see a banner appear, so say it, once per banner.
  useEffect(() => {
    AccessibilityInfo.announceForAccessibility(`${entry.title}. ${entry.message}`);
  }, [entry]);

  function onTouchStart(event: GestureResponderEvent) {
    startY.current = event.nativeEvent.pageY;
    travelled.current = 0;
  }

  function onTouchMove(event: GestureResponderEvent) {
    if (startY.current === null) return;
    travelled.current = event.nativeEvent.pageY - startY.current;
    // Follows the finger upward only; pulling down does not drag it.
    dragY.setValue(Math.min(0, travelled.current));
  }

  function onTouchEnd() {
    const distance = travelled.current;
    startY.current = null;
    travelled.current = 0;
    if (distance <= -SWIPE_DISMISS_DISTANCE) {
      AppBanner.dismiss();
    } else {
      dragY.setValue(0);
    }
  }

  return (
    // box-none: the full-width strip passes touches through to the screen underneath; only the card catches them.
    <View style={[styles.strip, { top: topInset + spacing.sm }]} pointerEvents="box-none">
      <Animated.View
        testID="app-banner-swipe"
        style={[styles.slot, { transform: [{ translateY: dragY }] }]}
        onTouchStart={onTouchStart}
        onTouchMove={onTouchMove}
        onTouchEnd={onTouchEnd}
        onTouchCancel={onTouchEnd}
      >
        <Pressable
          testID="app-banner"
          accessibilityRole="alert"
          accessibilityHint="Double tap to dismiss"
          accessibilityActions={[{ name: 'dismiss', label: 'Dismiss' }]}
          onAccessibilityAction={() => AppBanner.dismiss()}
          onPress={() => AppBanner.dismiss()}
          style={[styles.card, { backgroundColor: c.card, borderColor: c.border }]}
        >
          <Text style={[styles.title, { color: c.ink }]} numberOfLines={1}>
            {entry.title}
          </Text>
          <Text style={[styles.message, { color: c.muted }]} numberOfLines={3}>
            {entry.message}
          </Text>
        </Pressable>
      </Animated.View>
    </View>
  );
}

const styles = StyleSheet.create({
  strip: { position: 'absolute', left: 0, right: 0, alignItems: 'center', paddingHorizontal: spacing.md },
  slot: { width: '100%', maxWidth: 420 },
  card: {
    borderWidth: 1,
    borderRadius: radius.lg,
    paddingVertical: spacing.md,
    paddingHorizontal: spacing.lg,
    // A soft shadow so the card reads as sitting above the screen without a dimmed backdrop.
    shadowColor: '#000',
    shadowOpacity: 0.12,
    shadowRadius: 12,
    shadowOffset: { width: 0, height: 4 },
    elevation: 6,
  },
  title: { fontSize: 15, fontWeight: '700' },
  message: { fontSize: 14, marginTop: 2 },
});
