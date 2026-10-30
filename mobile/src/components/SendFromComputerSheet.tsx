import { useEffect, useRef, useState } from 'react';
import { ActivityIndicator, StyleSheet, Text, View } from 'react-native';
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

/** The code the sheet was still waiting on when it was closed: the import screen keeps watching for
 *  its file (WaitingForComputerFile). Closing the sheet never cancels the code -- the computer can
 *  still send with it -- so the phone must not stop listening just because the sheet went away. */
export interface StillWaiting {
  code: string;
  id?: string;
}

interface Props {
  /** Closed with no file. `stillWaiting` is set when the code shown was still unused. */
  onClose: (stillWaiting?: StillWaiting) => void;
  /** The file arrived as this import job: the parent closes the sheet and follows the job to its
   *  review, as it does for a file picked on the phone (Sid, 2026-10-25). */
  onReceived: (importJobId: string) => void;
}

type Phase =
  | { kind: 'issuing' }
  | { kind: 'failed'; tooMany?: boolean }
  | { kind: 'code'; code: string; id?: string; state: SendCodeState; importJobId?: string };

/**
 * Gate 2b spec §7: "My statement is on my computer". Asks the server for a one-time code, shows it
 * with the address to type it into, and watches until the file arrives or the code runs out. On
 * arrival it says "Received." and hands the import job back, so the import screen follows it to the
 * review exactly as for a file picked on the phone (Sid, 2026-10-25: a file that reads fine never
 * appears in Recent imports, so the plan's "It's in Recent Imports." pointed to the wrong place). The code
 * lets a computer add one file and see nothing; nothing is saved until the user reviews it here.
 *
 * Polls only while mounted, and stops at RECEIVED or EXPIRED. One timeout per check, scheduled only
 * after the previous answer, so a slow answer never overlaps the next question.
 */
export function SendFromComputerSheet({ onClose, onReceived }: Props) {
  const c = useTheme();
  const insets = useSafeAreaInsets();
  const [phase, setPhase] = useState<Phase>({ kind: 'issuing' });
  // Bumped to ask for a new code ("Get a new code", "Try again"); the first ask is on open.
  const [attempt, setAttempt] = useState(0);
  // Bumped after each WAITING answer, to schedule the next check.
  const [tick, setTick] = useState(0);
  const onReceivedRef = useRef(onReceived);
  useEffect(() => {
    onReceivedRef.current = onReceived;
  });

  useEffect(() => {
    let cancelled = false;
    sendCodesApi.issue()
      .then(({ code, id }) => { if (!cancelled) setPhase({ kind: 'code', code, id, state: 'WAITING' }); })
      // 429: the server's cap on codes per user (StatementSendService.MAX_CODES_PER_WINDOW).
      .catch((e) => { if (!cancelled) setPhase({ kind: 'failed', tooMany: e?.response?.status === 429 }); });
    return () => { cancelled = true; };
  }, [attempt]);

  const newCode = () => {
    setPhase({ kind: 'issuing' });
    setAttempt((a) => a + 1);
  };

  const waitingCode = phase.kind === 'code' && phase.state === 'WAITING' ? phase.code : null;
  const waitingId = phase.kind === 'code' && phase.state === 'WAITING' ? phase.id : undefined;
  useEffect(() => {
    if (!waitingCode) return;
    let cancelled = false;
    const timer = setTimeout(() => {
      sendCodesApi.current()
        .then(({ state: latest, importJobId, codeId }) => {
          if (cancelled) return;
          // The status is about a different code (one asked for after this, e.g. by a sheet closed
          // and reopened mid-request): this code is gone, so say so and offer a new one.
          const state: SendCodeState = waitingId && codeId && codeId !== waitingId ? 'NONE' : latest;
          if (state === 'WAITING') setTick((t) => t + 1);
          // RECEIVED, or EXPIRED (NONE: a code cancelled elsewhere): polling stops here.
          else setPhase((p) => (p.kind === 'code' && p.code === waitingCode
            ? { ...p, state, importJobId: importJobId ?? undefined } : p));
        })
        // A failed check is not an answer: ask again on the next tick.
        .catch(() => { if (!cancelled) setTick((t) => t + 1); });
    }, POLL_MS);
    return () => { cancelled = true; clearTimeout(timer); };
  }, [waitingCode, waitingId, tick]);

  const receivedJobId = phase.kind === 'code' && phase.state === 'RECEIVED' ? phase.importJobId ?? null : null;
  useEffect(() => {
    if (!receivedJobId) return;
    const timer = setTimeout(() => onReceivedRef.current(receivedJobId), RECEIVED_DWELL_MS);
    return () => clearTimeout(timer);
  }, [receivedJobId]);

  const status = phase.kind === 'code' ? phase.state : null;
  // Closing during the "Received." pause still follows the file: closing then must not strand it.
  // Closing while the code is still waiting hands the code back, so the screen keeps listening.
  const close = () => {
    if (receivedJobId) onReceived(receivedJobId);
    else if (waitingCode) onClose({ code: waitingCode, id: waitingId });
    else onClose();
  };

  return (
    <AppModal visible animationType="slide" transparent onRequestClose={close}>
      <View style={styles.flex}>
        {/* Not a close target. A user reads the code off the phone while typing on the computer,
            and on Android the tap that wakes a dimmed screen is also delivered to the app: a
            backdrop that closed the sheet stopped the phone listening the moment the file was
            sent (a customer report, 2026-10-30). Close and the back button still close it. */}
        <View style={styles.backdrop} />
        <GlassSurface style={[styles.sheet, { paddingBottom: insets.bottom + spacing.md }]}>
          {phase.kind === 'issuing' ? (
            <ActivityIndicator style={styles.spinner} color={c.primary} accessibilityLabel="Getting a code" />
          ) : phase.kind === 'failed' ? (
            <View>
              <Text style={[styles.body, { color: c.dangerInk }]}>
                {phase.tooMany
                  ? "You've asked for a lot of codes. Wait a few minutes, then try again."
                  : "Couldn't get a code. Check your connection and try again."}
              </Text>
              <Button label="Try again" onPress={newCode} />
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
                  Received.
                </Text>
              ) : status === 'EXPIRED' || status === 'NONE' ? (
                <View>
                  <Text style={[styles.status, { color: c.muted }]} accessibilityLiveRegion="polite">
                    This code has expired.
                  </Text>
                  <Button label="Get a new code" onPress={newCode} />
                </View>
              ) : (
                <Text style={[styles.status, { color: c.muted }]} accessibilityLiveRegion="polite">
                  Waiting for your file…
                </Text>
              )}
            </View>
          )}
          <Button label="Close" variant="link" onPress={close} />
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
