import { useEffect, useRef, useState } from 'react';
import { StyleSheet, Text } from 'react-native';
import { Button } from './Button';
import { Card, SectionHeading } from './Card';
import { POLL_MS } from './SendFromComputerSheet';
import { sendCodesApi } from '../api/endpoints';
import { spacing, useTheme } from '../theme';

interface Props {
  /** The code to show again, when this screen still knows it (the sheet was closed on it). Unknown
   *  after the app restarts: the server keeps only the code's hash. */
  code?: string;
  /** Which code this card is waiting on. A status about any other code means this one is gone. */
  codeId?: string;
  /** The file arrived as this import job: follow it to the review. */
  onReceived: (importJobId: string) => void;
  /** The code expired, was replaced, or the user stopped waiting. */
  onGone: () => void;
}

/**
 * Gate 2b spec §7, after the code sheet is closed. The code stays valid for its 10 minutes whether
 * or not the sheet is open, so the computer can still send with it; this card keeps listening for
 * that file and opens the review when it arrives. Before it, closing the sheet (or a stray tap that
 * did) left the phone silent while the computer said "Sent", and the statement waited unnoticed
 * (a customer report, 2026-10-30).
 *
 * Polls like the sheet: every POLL_MS, one check at a time, only while mounted, stopping at the
 * first answer that is not WAITING.
 */
export function WaitingForComputerFile({ code, codeId, onReceived, onGone }: Props) {
  const c = useTheme();
  const [tick, setTick] = useState(0);
  const onReceivedRef = useRef(onReceived);
  const onGoneRef = useRef(onGone);
  useEffect(() => {
    onReceivedRef.current = onReceived;
    onGoneRef.current = onGone;
  });

  useEffect(() => {
    let cancelled = false;
    const timer = setTimeout(() => {
      sendCodesApi.current()
        .then(({ state, importJobId, codeId: about }) => {
          if (cancelled) return;
          if (codeId && about && about !== codeId) onGoneRef.current();
          else if (state === 'WAITING') setTick((t) => t + 1);
          else if (state === 'RECEIVED' && importJobId) onReceivedRef.current(importJobId);
          else onGoneRef.current();
        })
        // A failed check is not an answer: ask again on the next tick.
        .catch(() => { if (!cancelled) setTick((t) => t + 1); });
    }, POLL_MS);
    return () => { cancelled = true; clearTimeout(timer); };
  }, [codeId, tick]);

  return (
    <Card testID="waiting-for-computer-file">
      <SectionHeading title="Waiting for your file" />
      <Text style={[styles.body, { color: c.ink }]} accessibilityLiveRegion="polite">
        {code
          ? `On your computer, open app.fynora.net/send and type ${code}. The review opens here when your file arrives.`
          : 'Send your statement from app.fynora.net/send on your computer. The review opens here when it arrives.'}
      </Text>
      <Button label="Stop waiting" variant="link" onPress={onGone} />
    </Card>
  );
}

const styles = StyleSheet.create({
  body: { fontSize: 15, lineHeight: 21, marginBottom: spacing.sm },
});
