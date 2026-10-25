import { useCallback, useEffect, useRef, useState } from 'react';
import { ActivityIndicator, Pressable, StyleSheet, Text, View } from 'react-native';
import { useSafeAreaInsets } from 'react-native-safe-area-context';
import { AppModal } from './AppModal';
import { Button } from './Button';
import { GlassSurface } from './GlassSurface';
import { sendCodesApi, type SendCodeState } from '../api/endpoints';
import { radius, spacing, useTheme } from '../theme';

/** How often the sheet asks whether the file has arrived (plan: every 3 s while open). */
export const POLL_MS = 3000;
/** How long "Received" stays on screen before the sheet closes itself. */
export const RECEIVED_DWELL_MS = 2000;

interface Props {
  onClose: () => void;
  /** The file arrived: the parent closes the sheet and refreshes Recent Imports. */
  onReceived: () => void;
}

type Phase = { kind: 'issuing' } | { kind: 'failed' } | { kind: 'code'; code: string; state: SendCodeState };

/**
 * Gate 2b spec §7: "My statement is on my computer". Asks the server for a one-time code, shows it
 * with the address to type it into, and watches until the file arrives or the code runs out. The code
 * lets a computer add one file and see nothing; nothing is saved until the user reviews it here.
 *
 * Polls only while mounted, and stops at RECEIVED or EXPIRED. A chained timeout rather than an
 * interval, so a slow answer never overlaps the next question.
 */
export function SendFromComputerSheet({ onClose, onReceived }: Props) {
  const c = useTheme();
  const insets = useSafeAreaInsets();
  const [phase, setPhase] = useState<Phase>({ kind: 'issuing' });
  const mounted = useRef(true);
  const pollTimer = useRef<ReturnType<typeof setTimeout> | null>(null);
  const closeTimer = useRef<ReturnType<typeof setTimeout> | null>(null);

  const stopPolling = () => {
    if (pollTimer.current) clearTimeout(pollTimer.current);
    pollTimer.current = null;
  };

  const poll = useCallback(() => {
    pollTimer.current = setTimeout(() => {
      sendCodesApi.current()
        .then(({ state }) => {
          if (!mounted.current) return;
          setPhase((p) => (p.kind === 'code' ? { ...p, state } : p));
          if (state === 'RECEIVED') {
            closeTimer.current = setTimeout(() => { if (mounted.current) onReceived(); }, RECEIVED_DWELL_MS);
          } else if (state === 'WAITING') {
            poll();
          }
          // EXPIRED (or NONE, a code cancelled elsewhere): stop and offer a new one.
        })
        // A failed check is not an answer: ask again on the next tick.
        .catch(() => { if (mounted.current) poll(); });
    }, POLL_MS);
  }, [onReceived]);

  const issue = useCallback(() => {
    stopPolling();
    setPhase({ kind: 'issuing' });
    sendCodesApi.issue()
      .then(({ code }) => {
        if (!mounted.current) return;
        setPhase({ kind: 'code', code, state: 'WAITING' });
        poll();
      })
      .catch(() => { if (mounted.current) setPhase({ kind: 'failed' }); });
  }, [poll]);

  useEffect(() => {
    mounted.current = true;
    issue();
    return () => {
      mounted.current = false;
      stopPolling();
      if (closeTimer.current) clearTimeout(closeTimer.current);
    };
    // Once per open: a new code each time the sheet is opened, never on a re-render.
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, []);

  const status = phase.kind === 'code' ? phase.state : null;

  return (
    <AppModal visible animationType="slide" transparent onRequestClose={onClose}>
      <View style={styles.flex}>
        <Pressable style={styles.backdrop} onPress={onClose} accessibilityLabel="Close" />
        <GlassSurface style={[styles.sheet, { paddingBottom: insets.bottom + spacing.md }]}>
          {phase.kind === 'issuing' ? (
            <ActivityIndicator style={styles.spinner} color={c.primary} accessibilityLabel="Getting a code" />
          ) : phase.kind === 'failed' ? (
            <View>
              <Text style={[styles.body, { color: c.dangerInk }]}>
                Couldn&apos;t get a code. Check your connection and try again.
              </Text>
              <Button label="Try again" onPress={issue} />
            </View>
          ) : (
            <View>
              <Text
                style={[styles.code, { color: c.ink, borderColor: c.border }]}
                accessibilityLabel={`Code ${phase.code.split('').join(' ')}`}
                selectable
              >
                {phase.code}
              </Text>
              <Text style={[styles.body, { color: c.ink }]}>
                Open app.fynora.net/send on your computer and type this code. It works once, for 10 minutes.
              </Text>
              {status === 'RECEIVED' ? (
                <Text style={[styles.status, { color: c.successInk }]} accessibilityLiveRegion="polite">
                  Received. It&apos;s in Recent Imports.
                </Text>
              ) : status === 'EXPIRED' || status === 'NONE' ? (
                <View>
                  <Text style={[styles.status, { color: c.muted }]} accessibilityLiveRegion="polite">
                    This code has expired.
                  </Text>
                  <Button label="Get a new code" onPress={issue} />
                </View>
              ) : (
                <Text style={[styles.status, { color: c.muted }]} accessibilityLiveRegion="polite">
                  Waiting for your file…
                </Text>
              )}
            </View>
          )}
          <Button label="Close" variant="link" onPress={onClose} />
        </GlassSurface>
      </View>
    </AppModal>
  );
}

const styles = StyleSheet.create({
  flex: { flex: 1 },
  backdrop: { flex: 1, backgroundColor: 'rgba(0,0,0,0.35)' },
  sheet: {
    borderTopLeftRadius: radius.xl,
    borderTopRightRadius: radius.xl,
    paddingHorizontal: spacing.md,
    paddingTop: spacing.lg,
    gap: spacing.sm,
  },
  spinner: { marginVertical: spacing.lg },
  code: {
    fontSize: 36,
    fontWeight: '700',
    letterSpacing: 8,
    textAlign: 'center',
    fontVariant: ['tabular-nums'],
    paddingVertical: spacing.md,
    borderWidth: 1,
    borderRadius: radius.md,
    marginBottom: spacing.sm,
  },
  body: { fontSize: 15, lineHeight: 21, marginBottom: spacing.sm },
  status: { fontSize: 14, marginBottom: spacing.sm },
});
