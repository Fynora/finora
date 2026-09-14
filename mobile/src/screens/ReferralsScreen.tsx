import { useEffect, useRef, useState } from 'react';
import {
  ActivityIndicator, Animated, Image, Linking, Platform, Pressable, ScrollView, Share, StyleSheet,
  Text, View,
} from 'react-native';
import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query';
import * as Clipboard from 'expo-clipboard';
import Ionicons from '@expo/vector-icons/Ionicons';
import { Card } from '../components/Card';
import { MetricTile } from '../components/AccountUI';
import { referralsApi, type MyReferralEntry } from '../api/endpoints';
import { useTransientFlag } from '../lib/useTransientFlag';
import { fmtCurrency, fmtDate } from '../lib/format';
import { safeStorage } from '../lib/safeStorage';
import { radius, spacing, useTheme } from '../theme';

const STEPS: { icon: keyof typeof Ionicons.glyphMap; label: string; caption: string }[] = [
  { icon: 'share-social-outline', label: 'Share your code', caption: 'Send it to a friend' },
  { icon: 'person-add-outline', label: 'They sign up', caption: 'Using your code' },
  { icon: 'people-outline', label: 'You see it here', caption: 'In your count below' },
];

// Phase 5: the code itself stays the primary, always-works instruction -- see this screen's own
// doc comment on why (no universal-link fallback exists, so the finora:// link below silently
// does nothing for anyone without the app already installed). The link is added AFTER the code,
// as a bonus for whoever already has the app: tapping it opens straight to Register with the code
// prefilled (useReferralDeepLink.ts), rather than typing it in by hand.
function shareMessage(code: string) {
  return `Join me on Fynora! Use my referral code ${code} when you sign up: finora://register?ref=${code}`;
}

const HERO_ILLUSTRATION = require('../../assets/illustrations/refer-earn-hero.png');
const HERO_ASPECT_RATIO = 1300 / 620;

/**
 * Deep-links straight into WhatsApp/SMS/Mail rather than the generic OS share sheet -- these are
 * fixed brand/system colors, not theme tokens, the same way a WhatsApp or Gmail icon stays its own
 * color in every app that shows one, light or dark.
 *
 * No `canOpenURL` pre-check for any of these, WhatsApp included. `canOpenURL` only answers
 * accurately for a scheme declared in iOS's `LSApplicationQueriesSchemes` (Info.plist) or
 * Android's manifest `<queries>` -- neither is declared for `whatsapp:` in app.config.ts, so
 * `canOpenURL('whatsapp://...')` returns false unconditionally, even with WhatsApp installed
 * (this was tried first and was a real, confirmed bug: the WhatsApp button silently always fell
 * through to the generic share sheet). `openURL` itself carries no such restriction on either
 * platform -- it just rejects if nothing can handle the URL -- so attempting it directly and
 * catching that rejection is both simpler and actually correct, with no native config to add or
 * rebuild to ship it.
 */
const CHANNELS: {
  key: string;
  label: string;
  icon: keyof typeof Ionicons.glyphMap;
  color: string;
  url: (code: string) => string;
}[] = [
  { key: 'whatsapp', label: 'WhatsApp', icon: 'logo-whatsapp', color: '#25D366',
    url: (code) => `whatsapp://send?text=${encodeURIComponent(shareMessage(code))}` },
  { key: 'sms', label: 'Messages', icon: 'chatbubble-outline', color: '#0A84FF',
    url: (code) => Platform.OS === 'ios'
      ? `sms:&body=${encodeURIComponent(shareMessage(code))}`
      : `sms:?body=${encodeURIComponent(shareMessage(code))}` },
  { key: 'email', label: 'Email', icon: 'mail-outline', color: '#4C8BF5',
    url: (code) => `mailto:?subject=${encodeURIComponent('Join me on Fynora')}&body=${encodeURIComponent(shareMessage(code))}` },
];

function statusLabel(status: string): { text: string; color: (c: ReturnType<typeof useTheme>) => string } {
  switch (status) {
    case 'REWARDED': return { text: 'Rewarded', color: (c) => c.success };
    case 'SUBSCRIBED': return { text: 'Subscribed', color: (c) => c.primary };
    default: return { text: 'Registered', color: (c) => c.muted };
  }
}

// A grant activates asynchronously via the backend's nightly sweep (design spec section 6.4:
// "fires ... at the moment a grant activates ... not at redemption"), so there's no synchronous
// "just redeemed" moment to hang the animation off of -- this screen has to notice an ACTIVE grant
// it hasn't shown the celebration for yet, whenever it happens to load. Same key/shape as web
// Referrals.tsx's own copy of this mechanism, just backed by SecureStore instead of localStorage.
const SEEN_ACTIVE_GRANTS_KEY = 'finora_seen_active_referral_grants';

/** Small reusable row -- either a progress readout (below threshold) or a redeem card (at/above
 *  threshold). Both tiers render independently and simultaneously: reaching one threshold never
 *  hides or replaces the other's row (design spec section 6.1, revised after product review --
 *  progress is persistent, nothing is ever forfeited). Mirrors web's own MilestoneRow. */
function MilestoneRow({
  c, label, counter, threshold, onRedeem, redeeming,
}: {
  c: ReturnType<typeof useTheme>; label: string; counter: number; threshold: number;
  onRedeem: () => void; redeeming: boolean;
}) {
  if (counter >= threshold) {
    return (
      <Card style={styles.codeCard}>
        <Text style={[styles.cardLabel, { color: c.ink }]}>You&apos;ve unlocked a reward!</Text>
        <Text style={[styles.emptyDesc, { color: c.muted }]}>Redeem 1 month of {label}, free.</Text>
        <Pressable
          onPress={onRedeem}
          disabled={redeeming}
          style={[styles.shareButton, { backgroundColor: c.primary, opacity: redeeming ? 0.5 : 1 }]}
          accessibilityRole="button"
        >
          <Text style={[styles.shareButtonText, { color: c.onPrimary }]}>Redeem {label}</Text>
        </Pressable>
      </Card>
    );
  }
  return (
    <Card style={styles.codeCard}>
      <Text style={[styles.cardLabel, { color: c.ink }]}>{counter} / {threshold} toward {label}</Text>
    </Card>
  );
}

/**
 * Mobile equivalent of web's UpgradeCelebration (design spec section 6.4) -- same three beats
 * (Premium: pop/shine + confetti dots; Plus: pop + one shine pass only), built on RN's Animated
 * API since there is no CSS/Web Animations equivalent here. Rendered once, whenever this screen
 * notices a newly-ACTIVE grant -- this is the only mobile call site.
 */
function MobileUpgradeCelebration({ tier }: { tier: 'PLUS' | 'PREMIUM' }) {
  const scale = useRef(new Animated.Value(0.3)).current;
  const opacity = useRef(new Animated.Value(0)).current;
  const shineOpacity = useRef(new Animated.Value(0)).current;
  const confettiDots = useRef(
    tier === 'PREMIUM'
      ? Array.from({ length: 16 }, () => ({
          x: new Animated.Value(0), y: new Animated.Value(0), o: new Animated.Value(1),
        }))
      : [],
  ).current;

  useEffect(() => {
    Animated.sequence([
      Animated.timing(opacity, { toValue: 1, duration: 150, useNativeDriver: true }),
      Animated.spring(scale, { toValue: 1, friction: 4, tension: 120, useNativeDriver: true }),
    ]).start();

    Animated.sequence([
      Animated.timing(shineOpacity, { toValue: 1, duration: 200, useNativeDriver: true }),
      Animated.timing(shineOpacity, { toValue: 0, duration: 400, delay: 300, useNativeDriver: true }),
    ]).start();

    if (tier === 'PREMIUM') {
      confettiDots.forEach((dot) => {
        const angle = Math.random() * Math.PI * 2;
        const dist = 40 + Math.random() * 60;
        Animated.parallel([
          Animated.timing(dot.x, { toValue: Math.cos(angle) * dist, duration: 900, useNativeDriver: true }),
          Animated.timing(dot.y, { toValue: Math.sin(angle) * dist + 60, duration: 900, useNativeDriver: true }),
          Animated.timing(dot.o, { toValue: 0, duration: 900, useNativeDriver: true }),
        ]).start();
      });
    }
    // eslint-disable-next-line react-hooks/exhaustive-deps -- refs are stable, deliberately fires once
  }, [tier]);

  const badgeStyle =
    tier === 'PREMIUM'
      ? { backgroundColor: '#E3EEE9', borderColor: 'transparent' }
      : { backgroundColor: '#2E2D2A', borderColor: '#D9D5CB' };
  const textColor = tier === 'PREMIUM' ? '#0F4C3F' : '#F4F1EC';

  return (
    <View style={styles.celebrationStage} testID="upgrade-celebration">
      {confettiDots.map((dot, i) => (
        <Animated.View
          key={i}
          style={[
            styles.confettiDot,
            { backgroundColor: i % 2 === 0 ? '#34A788' : '#98968F', opacity: dot.o },
            { transform: [{ translateX: dot.x }, { translateY: dot.y }] },
          ]}
        />
      ))}
      <Animated.View style={[styles.celebrationBadge, badgeStyle, { opacity, transform: [{ scale }] }]}>
        <Animated.View pointerEvents="none" style={[styles.celebrationShine, { opacity: shineOpacity }]} />
        <Text style={{ fontSize: 13, fontWeight: '700', color: textColor }}>✦ {tier}</Text>
      </Animated.View>
    </View>
  );
}

/**
 * Refer & Earn (mobile) -- started as an MVP port of frontend/src/pages/Referrals.tsx (a code,
 * copy/share, and a count), then given a hero illustration and reward-forward copy per a design
 * reference Sid provided. Now mirrors the web page's real data: a wallet balance, a pending count,
 * and the per-referral list below it (name, status, date, and reward once one is credited).
 *
 * Phase 5: shareMessage() now also includes a `finora://register?ref=CODE` deep link
 * (useReferralDeepLink.ts prefills Register's optional code field from it), but the code itself
 * stays the primary, always-copyable/shareable thing -- unlike web's `https://` share link, this
 * custom scheme has no universal-link fallback (see RootNavigator.tsx's own doc comment on why:
 * no Associated Domains / App Links hosting exists), so it silently does nothing for a friend who
 * doesn't have the app installed yet, which is the common case for a first invite. The link is a
 * bonus for someone who already has it, not a replacement for the code a friend can always type
 * into their own Register screen's "Referral code (optional)" field.
 */
export function ReferralsScreen() {
  const c = useTheme();
  const [copied, triggerCopied] = useTransientFlag();

  const { data, isLoading, isError, refetch } = useQuery({
    queryKey: ['referrals-mine'],
    queryFn: () => referralsApi.mine(),
  });

  const queryClient = useQueryClient();
  const redeemMutation = useMutation({
    mutationFn: (tier: 'PLUS' | 'PREMIUM') => referralsApi.redeem(tier),
    onSuccess: () => queryClient.invalidateQueries({ queryKey: ['referrals-mine'] }),
  });

  const [celebratingTier, setCelebratingTier] = useState<'PLUS' | 'PREMIUM' | null>(null);
  useEffect(() => {
    if (!data) return;
    let cancelled = false;
    let dismissTimer: ReturnType<typeof setTimeout> | undefined;
    (async () => {
      let seen: string[] = [];
      try {
        seen = JSON.parse((await safeStorage.getItem(SEEN_ACTIVE_GRANTS_KEY)) ?? '[]');
      } catch {
        seen = [];
      }
      const newlyActive = data.grants.find((g) => g.status === 'ACTIVE' && !seen.includes(g.id));
      if (!newlyActive || cancelled) return;
      setCelebratingTier(newlyActive.tier);
      await safeStorage.setItem(SEEN_ACTIVE_GRANTS_KEY, JSON.stringify([...seen, newlyActive.id]));
      dismissTimer = setTimeout(() => setCelebratingTier(null), 3000);
    })();
    return () => {
      cancelled = true;
      if (dismissTimer) clearTimeout(dismissTimer);
    };
  }, [data]);

  async function handleCopy() {
    if (!data?.code) return;
    await Clipboard.setStringAsync(data.code);
    triggerCopied();
  }

  async function handleShare() {
    if (!data?.code) return;
    try {
      await Share.share({ message: shareMessage(data.code) });
    } catch {
      // User dismissed the share sheet, or the OS rejected it -- nothing more useful to do than
      // leave the code visible in the field above for a manual copy.
    }
  }

  async function handleChannel(channel: (typeof CHANNELS)[number]) {
    if (!data?.code) return;
    try {
      await Linking.openURL(channel.url(data.code));
    } catch {
      // No app installed to handle this URL (most commonly WhatsApp), or the OS rejected it --
      // same fallback handleShare() itself already falls back to: leave the user with the OS
      // share sheet rather than a dead tap.
      await handleShare();
    }
  }

  if (isLoading) {
    return (
      <View style={[styles.centered, { backgroundColor: c.bg }]}>
        <ActivityIndicator size="large" color={c.primary} />
      </View>
    );
  }

  if (isError || !data) {
    return (
      <View style={[styles.centered, { backgroundColor: c.bg }]}>
        <Text style={[styles.message, { color: c.muted }]}>Couldn&apos;t load your referral code.</Text>
        <Pressable onPress={() => void refetch()} accessibilityRole="button">
          <Text style={[styles.retry, { color: c.primary }]}>Try again</Text>
        </Pressable>
      </View>
    );
  }

  return (
    <ScrollView style={{ backgroundColor: c.bg }} contentContainerStyle={styles.content}>
      {/* Header hidden (AppTabs.tsx) -- this large title is the screen's own, matching the
          design reference's hero weight rather than the small native-header title used
          elsewhere. */}
      <Text style={[styles.screenTitle, { color: c.ink }]}>Refer &amp; Earn</Text>
      <Text style={[styles.screenSubtitle, { color: c.muted }]}>
        Help your friends take control of their finances — and get rewarded together.
      </Text>

      {celebratingTier && <MobileUpgradeCelebration tier={celebratingTier} />}

      <Image
        source={HERO_ILLUSTRATION}
        style={{ width: '100%', aspectRatio: HERO_ASPECT_RATIO }}
        resizeMode="contain"
        accessibilityIgnoresInvertColors
        accessible
        accessibilityLabel="Two friends checking Fynora on their phones"
      />

      <Card>
        <View style={styles.stepsRow}>
          {STEPS.map((step, i) => (
            <View key={step.label} style={styles.stepGroup}>
              <View style={styles.step}>
                <View style={[styles.stepIcon, { backgroundColor: c.bg, borderColor: c.border }]}>
                  <Ionicons name={step.icon} size={16} color={c.primary} />
                </View>
                <Text style={[styles.stepLabel, { color: c.ink }]}>{step.label}</Text>
                <Text style={[styles.stepCaption, { color: c.muted }]}>{step.caption}</Text>
              </View>
              {i < STEPS.length - 1 ? (
                <Ionicons name="chevron-forward" size={14} color={c.border} style={styles.stepArrow} />
              ) : null}
            </View>
          ))}
        </View>
      </Card>

      <Card style={styles.codeCard}>
        <Text style={[styles.cardLabel, { color: c.ink }]}>Your referral code</Text>

        <View style={[styles.codeRow, { backgroundColor: c.bg, borderColor: c.border }]}>
          <Text style={[styles.code, { color: c.ink }]} selectable accessibilityLabel={`Referral code ${data.code}`}>
            {data.code}
          </Text>
          <Pressable
            onPress={() => void handleCopy()}
            style={[styles.iconButton, { backgroundColor: c.card, borderColor: c.border }]}
            accessibilityRole="button"
            accessibilityLabel={copied ? 'Copied' : 'Copy referral code'}
          >
            <Ionicons name={copied ? 'checkmark' : 'copy-outline'} size={16} color={copied ? c.success : c.ink} />
          </Pressable>
        </View>

        <Pressable
          onPress={() => void handleShare()}
          style={[styles.shareButton, { backgroundColor: c.primary }]}
          accessibilityRole="button"
          accessibilityLabel="Share referral code"
        >
          <Ionicons name="share-social-outline" size={16} color={c.onPrimary} />
          <Text style={[styles.shareButtonText, { color: c.onPrimary }]}>Share Invite</Text>
        </Pressable>

        <View style={styles.channelRow}>
          {CHANNELS.map((channel) => (
            <Pressable
              key={channel.key}
              onPress={() => void handleChannel(channel)}
              style={styles.channel}
              accessibilityRole="button"
              accessibilityLabel={`Share via ${channel.label}`}
            >
              <View style={[styles.channelIcon, { backgroundColor: channel.color }]}>
                <Ionicons name={channel.icon} size={20} color="#FFFFFF" />
              </View>
              <Text style={[styles.channelLabel, { color: c.muted }]}>{channel.label}</Text>
            </Pressable>
          ))}
          <Pressable
            onPress={() => void handleShare()}
            style={styles.channel}
            accessibilityRole="button"
            accessibilityLabel="More share options"
          >
            <View style={[styles.channelIcon, { backgroundColor: c.bg, borderWidth: 1, borderColor: c.border }]}>
              <Ionicons name="ellipsis-horizontal" size={20} color={c.ink} />
            </View>
            <Text style={[styles.channelLabel, { color: c.muted }]}>More</Text>
          </Pressable>
        </View>
      </Card>

      <View style={styles.statsRow}>
        <MetricTile label="Friends Referred" value={String(data.referrals.length)} />
        <MetricTile label="Pending" value={String(data.referrals.filter((r) => r.status === 'SUBSCRIBED').length)} />
        <MetricTile label="Earned" value={fmtCurrency(data.walletBalance)} />
      </View>

      <MilestoneRow
        c={c} label="Plus" counter={data.plusMilestoneCounter} threshold={3}
        onRedeem={() => redeemMutation.mutate('PLUS')} redeeming={redeemMutation.isPending}
      />
      <MilestoneRow
        c={c} label="Premium" counter={data.premiumMilestoneCounter} threshold={7}
        onRedeem={() => redeemMutation.mutate('PREMIUM')} redeeming={redeemMutation.isPending}
      />

      {data.grants.some((g) => g.status === 'ACTIVE' || g.status === 'PENDING') && (
        <Card style={styles.codeCard}>
          <Text style={[styles.cardLabel, { color: c.ink }]}>Your rewards</Text>
          {data.grants.filter((g) => g.status === 'ACTIVE').map((g) => (
            <View key={g.id} style={styles.rewardRow}>
              <Text style={[styles.rewardLabel, { color: c.ink }]}>{g.tier === 'PREMIUM' ? 'Premium' : 'Plus'} active</Text>
              {g.expiresAt && <Text style={[styles.rewardMeta, { color: c.muted }]}>until {fmtDate(g.expiresAt)}</Text>}
            </View>
          ))}
          {/* Oldest-first among PENDING grants -- data.grants comes back newest-first, but the
              sweep activates queued grants FIFO (oldest first), so this order matches which one
              actually activates next. */}
          {[...data.grants].filter((g) => g.status === 'PENDING').reverse().map((g) => (
            <View key={g.id} style={styles.rewardRow}>
              <Text style={[styles.rewardLabel, { color: c.ink }]}>{g.tier === 'PREMIUM' ? 'Premium' : 'Plus'} queued</Text>
              <Text style={[styles.rewardMeta, { color: c.muted }]}>activates automatically</Text>
            </View>
          ))}
        </Card>
      )}

      {data.referrals.length === 0 ? (
        <Card>
          <Text style={[styles.emptyTitle, { color: c.ink }]}>No referrals yet</Text>
          <Text style={[styles.emptyDesc, { color: c.muted }]}>
            Share your code above — when a friend signs up with it, they&apos;ll show up here.
          </Text>
        </Card>
      ) : (
        <Card style={styles.referralListCard}>
          {data.referrals.map((r: MyReferralEntry, i: number) => {
            const status = statusLabel(r.status);
            return (
              <View
                key={r.referralId}
                style={[styles.referralRow, i > 0 && { borderTopWidth: 1, borderTopColor: c.border }]}
              >
                <View style={styles.referralInfo}>
                  <Text style={[styles.referralName, { color: c.ink }]} numberOfLines={1}>
                    {r.referredUserFullName ?? 'A new user'}
                  </Text>
                  <Text style={[styles.referralMeta, { color: c.muted }]}>
                    Joined {fmtDate(r.createdAt)}{r.reward != null ? ` · Earned ${fmtCurrency(r.reward)}` : ''}
                  </Text>
                </View>
                <Text style={[styles.referralStatus, { color: status.color(c) }]}>{status.text}</Text>
              </View>
            );
          })}
        </Card>
      )}
    </ScrollView>
  );
}

const styles = StyleSheet.create({
  centered: { flex: 1, alignItems: 'center', justifyContent: 'center', padding: spacing.lg, gap: spacing.sm },
  message: { fontSize: 14, textAlign: 'center' },
  retry: { fontSize: 13, fontWeight: '600' },
  content: { padding: spacing.md, paddingBottom: spacing.xl, gap: spacing.md },

  screenTitle: { fontSize: 28, fontWeight: '800', letterSpacing: -0.3 },
  screenSubtitle: { fontSize: 14, marginTop: -4, lineHeight: 19 },

  stepsRow: { flexDirection: 'row', alignItems: 'flex-start', justifyContent: 'space-between' },
  stepGroup: { flexDirection: 'row', alignItems: 'flex-start', flex: 1 },
  step: { flex: 1, alignItems: 'center', gap: 4 },
  stepIcon: { width: 32, height: 32, borderRadius: 16, borderWidth: 1, alignItems: 'center', justifyContent: 'center' },
  stepLabel: { fontSize: 11, fontWeight: '600', textAlign: 'center' },
  stepCaption: { fontSize: 9.5, textAlign: 'center' },
  stepArrow: { marginTop: 8 },

  codeCard: { gap: spacing.sm },
  cardLabel: { fontSize: 13, fontWeight: '600' },
  codeRow: {
    flexDirection: 'row', alignItems: 'center', justifyContent: 'space-between',
    borderWidth: 1, borderRadius: radius.md, paddingLeft: spacing.md, paddingRight: spacing.xs, minHeight: 52,
  },
  code: { fontSize: 18, fontWeight: '700', fontFamily: 'monospace', letterSpacing: 1 },
  iconButton: {
    width: 40, height: 40, borderRadius: radius.md, borderWidth: 1,
    alignItems: 'center', justifyContent: 'center',
  },
  shareButton: {
    flexDirection: 'row', alignItems: 'center', justifyContent: 'center', gap: 8,
    borderRadius: radius.md, minHeight: 48,
  },
  shareButtonText: { fontSize: 14, fontWeight: '600' },

  statsRow: { flexDirection: 'row', gap: spacing.sm },

  emptyTitle: { fontSize: 14, fontWeight: '600', marginBottom: 2 },
  emptyDesc: { fontSize: 12.5, lineHeight: 17 },

  referralListCard: { padding: 0 },
  referralRow: {
    flexDirection: 'row', alignItems: 'center', justifyContent: 'space-between', gap: spacing.sm,
    paddingHorizontal: spacing.md, paddingVertical: spacing.sm,
  },
  referralInfo: { flex: 1, minWidth: 0 },
  referralName: { fontSize: 13.5, fontWeight: '600' },
  referralMeta: { fontSize: 11.5, marginTop: 2 },
  referralStatus: { fontSize: 10.5, fontWeight: '700', textTransform: 'uppercase' },

  channelRow: { flexDirection: 'row', justifyContent: 'space-between', paddingTop: spacing.xs },
  channel: { alignItems: 'center', gap: 6 },
  channelIcon: { width: 44, height: 44, borderRadius: 22, alignItems: 'center', justifyContent: 'center' },
  channelLabel: { fontSize: 10.5 },

  rewardRow: { flexDirection: 'row', alignItems: 'center', justifyContent: 'space-between', paddingVertical: 2 },
  rewardLabel: { fontSize: 13, fontWeight: '600' },
  rewardMeta: { fontSize: 11.5 },

  celebrationStage: { alignItems: 'center', justifyContent: 'center', paddingVertical: 12, position: 'relative' },
  confettiDot: { position: 'absolute', width: 5, height: 5, borderRadius: 2.5 },
  celebrationBadge: {
    borderRadius: 999, borderWidth: 1, paddingHorizontal: 14, paddingVertical: 6, position: 'relative',
  },
  celebrationShine: {
    position: 'absolute', top: 0, left: 0, right: 0, bottom: 0,
    backgroundColor: 'rgba(255,255,255,0.25)', borderRadius: 999,
  },
});
