import { useEffect, useSyncExternalStore } from 'react';
import { BackHandler, Pressable, StyleSheet, Text, View, type AlertButton } from 'react-native';
import {
  ROOT_ALERT_CONTAINER,
  dismissCurrentAppAlert,
  dismissCurrentAppAlertByUser,
  getCurrentAppAlert,
  getTopAlertContainer,
  handleAlertBack,
  subscribeAppAlerts,
} from '../lib/appAlert';
import { radius, spacing, useTheme } from '../theme';

/**
 * Draws the alert AppAlert.alert() queued -- but only in the ONE container that should show it (the
 * topmost open AppModal, else the root); see lib/appAlert.ts for why alerts are drawn here rather
 * than by the OS. Absolutely positioned, so it goes at the end of whatever it overlays.
 *
 * Follows Alert.alert's contract: the alert is closed BEFORE a button's onPress runs (so an
 * onPress that raises the next alert -- the foreground-push queue does -- shows it straight away),
 * and a dismissal that is not a button press (backdrop tap, Android back) calls options.onDismiss,
 * unless the caller made the alert non-cancelable.
 */
export function AppAlertOverlay({ containerId }: { containerId: string }) {
  const entry = useSyncExternalStore(subscribeAppAlerts, getCurrentAppAlert, getCurrentAppAlert);
  const topContainer = useSyncExternalStore(subscribeAppAlerts, getTopAlertContainer, getTopAlertContainer);
  const c = useTheme();
  const showing = entry !== undefined && topContainer === containerId;

  // Inside a native Modal, Android's back arrives as the Modal's onRequestClose (AppModal routes it
  // to handleAlertBack). At the root it arrives here.
  useEffect(() => {
    if (!showing || containerId !== ROOT_ALERT_CONTAINER) return;
    const subscription = BackHandler.addEventListener('hardwareBackPress', () => handleAlertBack(containerId));
    return () => subscription.remove();
  }, [showing, containerId]);

  if (!entry || !showing) return null;

  function pressButton(button: AlertButton) {
    dismissCurrentAppAlert();
    button.onPress?.();
  }

  const stacked = entry.buttons.length > 2;

  return (
    <View style={styles.backdrop}>
      {/* Sits behind the card, so a tap outside it dismisses and a tap on it does not. */}
      <Pressable
        style={StyleSheet.absoluteFill}
        accessible={false}
        onPress={dismissCurrentAppAlertByUser}
        testID="app-alert-backdrop"
      />
      <View
        accessibilityViewIsModal
        accessibilityRole="alert"
        style={[styles.card, { backgroundColor: c.card, borderColor: c.border }]}
      >
        <Text style={[styles.title, { color: c.ink }]}>{entry.title}</Text>
        {entry.message ? <Text style={[styles.message, { color: c.muted }]}>{entry.message}</Text> : null}
        <View style={stacked ? styles.buttonsStacked : styles.buttonsRow}>
          {entry.buttons.map((button, i) => (
            <Pressable
              key={`${button.text ?? ''}-${i}`}
              accessibilityRole="button"
              onPress={() => pressButton(button)}
              style={styles.button}
            >
              <Text
                style={[
                  styles.buttonText,
                  {
                    color: button.style === 'destructive' ? c.danger : button.style === 'cancel' ? c.ink : c.primary,
                    fontWeight: button.style === 'cancel' ? '400' : '600',
                  },
                ]}
              >
                {button.text}
              </Text>
            </Pressable>
          ))}
        </View>
      </View>
    </View>
  );
}

const styles = StyleSheet.create({
  backdrop: {
    position: 'absolute',
    top: 0,
    left: 0,
    right: 0,
    bottom: 0,
    alignItems: 'center',
    justifyContent: 'center',
    padding: spacing.lg,
    backgroundColor: 'rgba(0,0,0,0.45)',
  },
  card: {
    width: '100%',
    maxWidth: 340,
    borderWidth: 1,
    borderRadius: radius.lg,
    padding: spacing.lg,
  },
  title: { fontSize: 17, fontWeight: '700' },
  message: { fontSize: 14, marginTop: spacing.sm },
  buttonsRow: { flexDirection: 'row', justifyContent: 'flex-end', flexWrap: 'wrap', marginTop: spacing.md },
  buttonsStacked: { marginTop: spacing.md },
  button: { paddingVertical: spacing.sm, paddingHorizontal: spacing.md, minHeight: 44, justifyContent: 'center' },
  buttonText: { fontSize: 15, textAlign: 'center' },
});
