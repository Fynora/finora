import { useState } from 'react';
import { StyleSheet, Text, View } from 'react-native';
import Ionicons from '@expo/vector-icons/Ionicons';
import { Button } from './Button';
import { PinPad } from './PinPad';
import * as appLock from '../lib/appLock';
import * as pinLock from '../lib/pinLock';
import { reportHandledError } from '../lib/monitoring';
import { spacing, useTheme } from '../theme';

type Step = { kind: 'enter'; error: string | null } | { kind: 'confirm'; first: string } | { kind: 'offer' };

/**
 * Required PIN setup: shown before the app on any signed-in phone that has no PIN yet (right after
 * signing in, or the first open after updating to the PIN-lock build). Enter, then confirm; then,
 * on a phone with Face ID / fingerprint set up, an offer to unlock with it. There is no skip: a
 * phone session lasts up to 90 days, and the PIN is what guards it (design 2026-11-10).
 *
 * Someone who already had the biometric App Lock on keeps it, without being asked again.
 * `offerBiometrics={false}` skips the offer: Change PIN in Settings, where the switch sits beside it.
 */
export function PinSetup({ onDone, offerBiometrics = true }: { onDone: () => void; offerBiometrics?: boolean }) {
  const c = useTheme();
  const [step, setStep] = useState<Step>({ kind: 'enter', error: null });
  const [busy, setBusy] = useState(false);

  async function save(pin: string) {
    setBusy(true);
    try {
      await pinLock.setPin(pin);
    } catch (error) {
      reportHandledError(error, 'pin-setup-save');
      setStep({ kind: 'enter', error: "Couldn't save your PIN. Try again." });
      return;
    } finally {
      setBusy(false);
    }
    if (!offerBiometrics) {
      onDone();
      return;
    }
    // The PIN is saved: a failed check here only skips the offer (Settings has the switch).
    const [supported, alreadyOn] = await Promise.all([appLock.isSupported(), appLock.isEnabled()]).catch(
      (error: unknown) => {
        reportHandledError(error, 'pin-setup-biometric-check');
        return [false, false];
      }
    );
    if (supported && !alreadyOn) {
      setStep({ kind: 'offer' });
      return;
    }
    onDone();
  }

  async function turnOnBiometrics() {
    setBusy(true);
    try {
      if (await appLock.authenticate('Confirm to unlock Fynora with Face ID or fingerprint')) {
        await appLock.setEnabled(true);
      }
    } finally {
      setBusy(false);
    }
    onDone();
  }

  if (step.kind === 'offer') {
    return (
      <View style={styles.offer}>
        <Ionicons name="finger-print" size={48} color={c.primary} />
        <Text style={[styles.title, { color: c.ink }]} accessibilityRole="header">
          Unlock with Face ID or fingerprint?
        </Text>
        <Text style={[styles.subtitle, { color: c.muted }]}>
          You can still use your PIN any time, and change this in Settings.
        </Text>
        <View style={styles.actions}>
          <Button label="Turn on" onPress={() => void turnOnBiometrics()} loading={busy} />
          <Button label="Not now" onPress={onDone} variant="link" disabled={busy} />
        </View>
      </View>
    );
  }

  if (step.kind === 'confirm') {
    return (
      <PinPad
        key="confirm"
        title="Enter it again"
        subtitle="Type the same 4 digits to confirm."
        busy={busy}
        onComplete={(pin) => {
          if (pin !== step.first) {
            setStep({ kind: 'enter', error: "The PINs didn't match. Try again." });
            return;
          }
          void save(pin);
        }}
      />
    );
  }

  return (
    <PinPad
      key="enter"
      title="Set a 4-digit PIN"
      subtitle="You'll use it to open Fynora on this phone."
      error={step.error}
      busy={busy}
      onComplete={(pin) => setStep({ kind: 'confirm', first: pin })}
    />
  );
}

const styles = StyleSheet.create({
  offer: { flex: 1, alignItems: 'center', justifyContent: 'center', padding: spacing.xl, gap: spacing.sm },
  title: { fontSize: 20, fontWeight: '700', marginTop: spacing.md, textAlign: 'center' },
  subtitle: { fontSize: 14, textAlign: 'center', marginBottom: spacing.lg },
  actions: { width: '100%', gap: spacing.sm, marginTop: spacing.md },
});
