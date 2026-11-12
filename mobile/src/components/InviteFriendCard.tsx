import { useEffect, useRef, useState } from 'react';
import { Share, StyleSheet, Text, View, type ViewStyle } from 'react-native';
import { invitePromptApi, referralsApi, type InvitePromptEvent } from '../api/endpoints';
import { withShareSuppression } from '../lib/appLock';
import { inviteMessage } from '../lib/inviteLink';
import { spacing, typography, useTheme } from '../theme';
import { Button } from './Button';
import { Card } from './Card';

/** Mirrors ReferralService.MILESTONE_REFERRALS on the backend (ReferralsScreen's own copy). */
const REFERRAL_MILESTONE = 7;

function report(event: InvitePromptEvent) {
  try {
    void invitePromptApi.event(event).catch(() => {});
  } catch {
    // Measurement must never break the screen.
  }
}

/**
 * "Know someone who'd want this?" on the import done screen, after a person's first successful
 * import. ImportScreen asks the server whether to show it (InvitePromptService: the first import
 * that saved transactions, once more at least 180 days after "Not now", never after Share) and
 * renders this only when the answer is yes; a held or refused statement never reaches the done
 * screen. Renders nothing until the person's referral code is read, and nothing if it cannot be --
 * the import itself succeeded either way.
 *
 * Inline in the done screen's scroll, not a sheet or modal: it never covers the import's warnings,
 * and there is no native modal to stack under the store-rating sheet (a nested native Modal can
 * freeze iOS touches). ImportScreen does not ask for a rating on an import that shows this card.
 *
 * Share opens the OS share sheet with the person's referral message (lib/inviteLink.ts).
 */
export function InviteFriendCard({ style }: { style?: ViewStyle }) {
  const c = useTheme();
  const [code, setCode] = useState<string | null>(null);
  const [state, setState] = useState<'asking' | 'shared' | 'dismissed'>('asking');
  const reported = useRef(false);

  useEffect(() => {
    let cancelled = false;
    referralsApi.myCode()
      .then((mine) => {
        if (cancelled || !mine?.code) return;
        setCode(mine.code);
        if (!reported.current) {
          reported.current = true;
          report('SHOWN');
        }
      })
      .catch(() => {});
    return () => { cancelled = true; };
  }, []);

  if (!code || state === 'dismissed') return null;

  if (state === 'shared') {
    return (
      <Card testID="invite-friend-card" style={style}>
        <Text accessibilityLiveRegion="polite" style={[typography.labelM, { color: c.ink }]}>
          Thanks for sharing Fynora.
        </Text>
      </Card>
    );
  }

  async function share() {
    report('SHARE_TAPPED');
    try {
      // Counted as a share in progress, so the app-lock screen does not treat the trip to the
      // share sheet as leaving the app, and no store-rating sheet lands on top of it.
      const result = await withShareSuppression(() => Share.share({ message: inviteMessage(code!) }));
      // iOS says when the sheet was closed without sharing: leave the card, so they can try again.
      // Android's chooser always reports sharedAction, even when backed out of (seen on the
      // emulator), so there the thanks line follows any trip to the chooser.
      if (result.action === Share.dismissedAction) return;
      setState('shared');
    } catch {
      // The OS refused to open the sheet: leave the card as it was.
    }
  }

  function notNow() {
    report('DISMISSED');
    setState('dismissed');
  }

  return (
    <Card testID="invite-friend-card" style={{ ...styles.card, ...style }}>
      <View style={styles.head}>
        <Text accessibilityRole="header" style={[typography.labelM, { color: c.ink }]}>
          Fynora sorted your statement.
        </Text>
        <Text style={[typography.bodyS, { color: c.ink }]}>Know someone who&apos;d want this?</Text>
        <Text style={[typography.caption, { color: c.mutedInk }]}>
          {`When ${REFERRAL_MILESTONE} friends you invite start paying for Plus, you earn a month of Plus free.`}
        </Text>
      </View>
      <View style={styles.actions}>
        <Button label="Share" variant="secondary" onPress={() => void share()} testID="invite-friend-share" />
        <Button label="Not now" variant="link" onPress={notNow} testID="invite-friend-not-now" />
      </View>
    </Card>
  );
}

const styles = StyleSheet.create({
  card: { gap: spacing.sm },
  head: { gap: 2 },
  actions: { gap: spacing.xs },
});
