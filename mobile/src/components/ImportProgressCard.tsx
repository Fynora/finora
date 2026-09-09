import { useEffect, useRef, useState } from 'react';
import { ActivityIndicator, Pressable, StyleSheet, Text, View } from 'react-native';
import Ionicons from '@expo/vector-icons/Ionicons';
import { importJobsApi, type ImportJobProgress } from '../api/endpoints';
import { importFailureMessage } from '../api/importFailureMessages';
import { detail, isCancellable, isHeld, isSettled, label, percent } from '../lib/importJob';
import { Card } from './Card';
import { radius, spacing, useTheme } from '../theme';

/**
 * Phase 4 (Medium-Tier Parity). Mobile counterpart to frontend/src/components/ImportProgress.tsx
 * -- see that file's own doc comment for why a queued import is watched by polling rather than
 * holding the upload request open for the whole parse (measured there at 79s for a 5,000-row
 * statement, during which closing the app would lose the work).
 *
 * Folds in a reduced version of web's separate ImportTimeline component rather than porting it:
 * once the job actually FAILS, this fetches the job's /timeline ONCE (not polled) and shows
 * importFailureMessage(timeline.failureCode) as the one curated reason line -- covering the "read
 * why" need ImportTimeline exists for, without porting its full per-stage history/timestamp list,
 * which is presentation depth this app's mobile cut doesn't need for a first version.
 */

export const POLL_SCHEDULE_MS = [100, 200, 400, 800, 1500] as const;

export function ImportProgressCard({
  jobId,
  onReady,
  onGaveUp,
  onDismiss,
}: {
  jobId: string;
  /** The job finished with rows to review. Carries the session the review step loads. */
  onReady: (sessionId: string) => void;
  /** The job ended with nothing to review -- failed, or cancelled by this user. */
  onGaveUp: (job: ImportJobProgress) => void;
  /** Offered once the job has FAILED, so reading the curated reason doesn't strand the user on
   *  a dead screen -- the caller is expected to keep this card mounted through a FAILED outcome
   *  (see onGaveUp's own doc comment) and only clear it once this fires. */
  onDismiss: () => void;
}) {
  const c = useTheme();
  const [job, setJob] = useState<ImportJobProgress | null>(null);
  const [cancelling, setCancelling] = useState(false);
  const [pollError, setPollError] = useState<string | null>(null);
  const [failureReason, setFailureReason] = useState<string | null>(null);
  const settled = useRef(false);

  useEffect(() => {
    settled.current = false;
    let timer: ReturnType<typeof setTimeout> | null = null;
    let poll = 0;
    // Stops the POLLING SCHEDULE once the job settles -- deliberately distinct from `unmounted`
    // below. `stop()` flips this the instant a terminal status arrives, before the one-shot
    // timeline() fetch for a FAILED job even starts; reusing it as that fetch's own "is this still
    // relevant" guard would make setFailureReason's callback see it already true on every FAILED
    // job, unconditionally dropping the curated reason it exists to show.
    let stopped = false;
    let unmounted = false;

    const stop = () => {
      stopped = true;
      if (timer !== null) {
        clearTimeout(timer);
        timer = null;
      }
    };

    const schedule = () => {
      if (stopped) return;
      const delay = POLL_SCHEDULE_MS[Math.min(poll, POLL_SCHEDULE_MS.length - 1)];
      poll += 1;
      timer = setTimeout(() => void tick(), delay);
    };

    const tick = async () => {
      try {
        const next = await importJobsApi.progress(jobId);
        if (stopped) return;
        setJob(next);
        setPollError(null);
        if (!isSettled(next) || settled.current) {
          schedule();
          return;
        }
        settled.current = true;
        stop();
        if (next.status === 'FAILED') {
          importJobsApi.timeline(jobId)
            .then((t) => { if (!unmounted) setFailureReason(importFailureMessage(t.failureCode) ?? null); })
            .catch(() => {});
        }
        if (next.status === 'COMPLETED' && next.importSessionId) onReady(next.importSessionId);
        else onGaveUp(next);
      } catch {
        if (stopped) return;
        setPollError('Lost contact with the server — still trying. Your import is safe.');
        schedule();
      }
    };

    schedule();
    return () => {
      unmounted = true;
      stop();
    };
    // onReady/onGaveUp are deliberately not dependencies, same as web's ImportProgress -- a parent
    // re-render would otherwise tear down and restart the polling interval every time.
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [jobId]);

  async function cancel() {
    setCancelling(true);
    try {
      const cancelled = await importJobsApi.cancel(jobId);
      setJob(cancelled);
      if (isSettled(cancelled) && !settled.current) {
        settled.current = true;
        onGaveUp(cancelled);
      }
    } catch (e) {
      setPollError(
        (e as { response?: { data?: { message?: string } } })?.response?.data?.message
          ?? 'Could not cancel this import.'
      );
    } finally {
      setCancelling(false);
    }
  }

  const pct = job ? percent(job) : null;
  const failed = job?.status === 'FAILED';
  const held = job ? isHeld(job) : false;
  const iconName = failed ? 'alert-circle' : held ? 'time-outline'
    : job && isSettled(job) ? 'close-circle-outline' : null;
  const iconColor = failed ? c.warning : held ? c.primary : c.muted;

  return (
    <Card style={styles.card}>
      <View style={styles.row}>
        {iconName ? (
          <Ionicons name={iconName} size={20} color={iconColor} />
        ) : (
          <ActivityIndicator size="small" color={c.primary} />
        )}
        <View style={styles.textBlock}>
          <Text style={[styles.label, { color: c.ink }]}>{job ? label(job) : 'Uploading'}</Text>
          {job && detail(job) ? (
            <Text style={[styles.detail, { color: c.muted }]}>{detail(job)}</Text>
          ) : null}
          {failed && failureReason ? (
            <Text style={[styles.detail, { color: c.muted }]}>{failureReason}</Text>
          ) : null}
        </View>

        {job && isCancellable(job) ? (
          <Pressable
            onPress={() => void cancel()}
            disabled={cancelling}
            hitSlop={8}
            accessibilityRole="button"
            accessibilityLabel="Cancel import"
          >
            <Text style={[styles.cancelText, { color: c.muted }]}>
              {cancelling ? 'Cancelling…' : 'Cancel'}
            </Text>
          </Pressable>
        ) : null}
      </View>

      {!failed && job && !isSettled(job) ? (
        <View style={[styles.track, { backgroundColor: c.border }]}>
          {pct === null ? (
            <View style={[styles.fillIndeterminate, { backgroundColor: c.primary }]} />
          ) : (
            <View
              style={[styles.fill, { backgroundColor: c.primary, width: `${pct}%` }]}
              accessibilityRole="progressbar"
              accessibilityValue={{ min: 0, max: 100, now: pct }}
            />
          )}
        </View>
      ) : null}

      {job && !isSettled(job) ? (
        <Text style={[styles.hint, { color: c.muted }]}>
          You can close the app — the import keeps going, and it&apos;ll be waiting for you.
        </Text>
      ) : null}

      {pollError ? <Text style={[styles.pollError, { color: c.warning }]}>{pollError}</Text> : null}

      {failed ? (
        <Pressable onPress={onDismiss} hitSlop={8} accessibilityRole="button">
          <Text style={[styles.dismiss, { color: c.primary }]}>Choose a different file</Text>
        </Pressable>
      ) : null}
    </Card>
  );
}

const styles = StyleSheet.create({
  card: { gap: spacing.sm },
  row: { flexDirection: 'row', alignItems: 'center', gap: spacing.sm },
  textBlock: { flex: 1 },
  label: { fontSize: 14, fontWeight: '600' },
  detail: { fontSize: 12, marginTop: 2 },
  cancelText: { fontSize: 12, fontWeight: '600' },
  track: { height: 6, borderRadius: radius.md, overflow: 'hidden' },
  fill: { height: 6, borderRadius: radius.md },
  fillIndeterminate: { height: 6, borderRadius: radius.md, width: '33%', opacity: 0.6 },
  hint: { fontSize: 11 },
  pollError: { fontSize: 12 },
  dismiss: { fontSize: 13, fontWeight: '600' },
});
