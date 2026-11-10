import { useEffect, useState } from 'react';
import { Pressable, Text, View, StyleSheet } from 'react-native';
import { AppModal } from '../../components/AppModal';
import { AppSwitch } from '../../components/AppSwitch';
import { Button } from '../../components/Button';
import { PinPad } from '../../components/PinPad';
import { PinSetup } from '../../components/PinSetup';
import { SkeletonGroup, SkeletonSettingsRow } from '../../components/skeletons/Skeletons';
import { useAuth } from '../../context/AuthContext';
import * as appLock from '../../lib/appLock';
import * as pinLock from '../../lib/pinLock';
import { spacing, useTheme } from '../../theme';

/**
 * Settings > Security: the app PIN and Face ID / fingerprint unlock (design 2026-11-10). The PIN
 * itself is required and cannot be turned off here, only changed; biometrics are an optional,
 * faster way past it.
 *
 * Turning biometrics ON requires a real successful scan before the setting takes effect -- a bare
 * switch flip would let someone turn it on and never discover their fingerprint is not enrolled
 * until the next time the lock screen tries it.
 */
export function AppLockSection() {
  const c = useTheme();
  const [loading, setLoading] = useState(true);
  const [supported, setSupported] = useState(false);
  const [enabled, setEnabledState] = useState(false);
  // Distinct from `supported`: whether the CURRENT on/off state could be read at all. A transient
  // SecureStore failure leaves this false and the switch hidden, rather than guessing a state the
  // user never chose.
  const [confirmed, setConfirmed] = useState(false);
  const [busy, setBusy] = useState(false);
  const [error, setError] = useState<string | null>(null);
  const [changingPin, setChangingPin] = useState(false);
  const [pinChanged, setPinChanged] = useState(false);

  useEffect(() => {
    let cancelled = false;
    void Promise.all([appLock.isSupported(), appLock.isEnabledConfirmed()]).then(([supp, { enabled: en, confirmed: conf }]) => {
      if (cancelled) return;
      setSupported(supp);
      setEnabledState(en);
      setConfirmed(conf);
      setLoading(false);
    });
    return () => {
      cancelled = true;
    };
  }, []);

  async function handleToggle(next: boolean) {
    setError(null);
    if (!next) {
      // Turning off needs no proof: the PIN still guards the app.
      setBusy(true);
      await appLock.setEnabled(false);
      setEnabledState(false);
      setConfirmed(true);
      setBusy(false);
      return;
    }
    setBusy(true);
    try {
      const didAuthenticate = await appLock.authenticate('Confirm to unlock Fynora with Face ID or fingerprint');
      if (didAuthenticate) {
        await appLock.setEnabled(true);
        setEnabledState(true);
        setConfirmed(true);
      } else {
        setError('Could not confirm — Face ID or fingerprint unlock was not turned on.');
      }
    } finally {
      setBusy(false);
    }
  }

  const changePinRow = (
    <View>
      <Pressable
        accessibilityRole="button"
        onPress={() => {
          setPinChanged(false);
          setChangingPin(true);
        }}
        style={[styles.row, { borderBottomColor: c.border }]}
      >
        <View style={styles.rowMain}>
          <Text style={[styles.rowTitle, { color: c.ink }]}>Change PIN</Text>
          <Text style={[styles.rowMeta, { color: c.mutedInk }]}>
            The 4-digit PIN you use to open Fynora on this phone.
          </Text>
        </View>
      </Pressable>
      {pinChanged ? <Text style={[styles.note, { color: c.mutedInk }]}>PIN changed.</Text> : null}
      {changingPin ? (
        <ChangePinSheet
          onClose={() => setChangingPin(false)}
          onChanged={() => {
            setChangingPin(false);
            setPinChanged(true);
          }}
        />
      ) : null}
    </View>
  );

  let biometricRow;
  if (loading) {
    biometricRow = (
      <SkeletonGroup>
        {/* The real row's description wraps to two lines on a phone. */}
        <SkeletonSettingsRow trailing="switch" metaLines={2} />
      </SkeletonGroup>
    );
  } else if (!supported) {
    biometricRow = (
      <View style={[styles.row, { borderBottomColor: c.border }]}>
        <View style={styles.rowMain}>
          <Text style={[styles.rowTitle, { color: c.ink }]}>Face ID or fingerprint</Text>
          <Text style={[styles.rowMeta, { color: c.mutedInk }]}>
            Set up Face ID or a fingerprint on this phone to unlock Fynora with it.
          </Text>
        </View>
      </View>
    );
  } else if (!confirmed) {
    biometricRow = (
      <View style={[styles.row, { borderBottomColor: c.border }]}>
        <View style={styles.rowMain}>
          <Text style={[styles.rowTitle, { color: c.ink }]}>Face ID or fingerprint</Text>
          <Text style={[styles.rowMeta, { color: c.dangerInk }]}>
            Couldn&apos;t check whether Face ID or fingerprint unlock is on. Try reopening Settings.
          </Text>
        </View>
      </View>
    );
  } else {
    biometricRow = (
      <View>
        <View style={[styles.row, { borderBottomColor: c.border }]}>
          <View style={styles.rowMain}>
            <Text style={[styles.rowTitle, { color: c.ink }]}>Face ID or fingerprint</Text>
            <Text style={[styles.rowMeta, { color: c.mutedInk }]}>
              Open Fynora with Face ID or your fingerprint instead of typing your PIN.
            </Text>
          </View>
          <AppSwitch
            value={enabled}
            onValueChange={(next) => void handleToggle(next)}
            disabled={busy}
            trackColor={{ true: c.primary, false: c.border }}
            // The native thumb is a plain white circle by default, which nearly disappears into dark
            // mode's light "on" track -- onPrimary keeps it visible against either track colour.
            thumbColor={enabled ? c.onPrimary : undefined}
            accessibilityLabel="Unlock with Face ID or fingerprint"
            accessibilityHint="Lets you open the app with Face ID or your fingerprint instead of your PIN"
          />
        </View>
        {error ? <Text style={[styles.error, { color: c.dangerInk }]}>{error}</Text> : null}
      </View>
    );
  }

  return (
    <View>
      {changePinRow}
      {biometricRow}
    </View>
  );
}

/**
 * Current PIN first, then a new one (enter + confirm). A wrong current PIN counts toward the same
 * 5-try limit as the lock screen, so this cannot be used to guess the PIN; the fifth signs out.
 */
function ChangePinSheet({ onClose, onChanged }: { onClose: () => void; onChanged: () => void }) {
  const c = useTheme();
  const { logout } = useAuth();
  const [step, setStep] = useState<'current' | 'new'>('current');
  const [error, setError] = useState<string | null>(null);
  const [busy, setBusy] = useState(false);

  async function checkCurrent(pin: string) {
    setBusy(true);
    try {
      const result = await pinLock.checkPin(pin);
      if (result.ok) {
        setError(null);
        setStep('new');
        return;
      }
      if (result.triesLeft === 0) {
        onClose();
        logout();
        return;
      }
      setError(result.triesLeft > 2
        ? 'Wrong PIN. Try again.'
        : `Wrong PIN. ${result.triesLeft} ${result.triesLeft === 1 ? 'try' : 'tries'} left.`);
    } finally {
      setBusy(false);
    }
  }

  const cancel = <Button label="Cancel" onPress={onClose} variant="link" />;

  return (
    <AppModal visible animationType="slide" onRequestClose={onClose}>
      <View style={[styles.sheet, { backgroundColor: c.bg } /* glass-exempt: PIN entry must be opaque */]}>
        {step === 'current' ? (
          <PinPad
            title="Enter your current PIN"
            error={error}
            busy={busy}
            onComplete={(pin) => void checkCurrent(pin)}
            footer={cancel}
          />
        ) : (
          <>
            <PinSetup offerBiometrics={false} onDone={onChanged} />
            <View style={styles.sheetFooter}>{cancel}</View>
          </>
        )}
      </View>
    </AppModal>
  );
}

const styles = StyleSheet.create({
  row: {
    flexDirection: 'row',
    alignItems: 'center',
    justifyContent: 'space-between',
    paddingVertical: 10,
    borderBottomWidth: StyleSheet.hairlineWidth,
  },
  rowMain: { flex: 1, marginRight: spacing.sm },
  rowTitle: { fontSize: 14, fontWeight: '600' },
  rowMeta: { fontSize: 12, marginTop: 2 },
  note: { fontSize: 12, marginTop: spacing.xs },
  error: { fontSize: 12, marginTop: spacing.xs, marginBottom: spacing.sm },
  sheet: { flex: 1 },
  sheetFooter: { alignItems: 'center', paddingBottom: spacing.xl },
});
