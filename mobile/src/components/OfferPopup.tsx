import { useEffect, useState } from 'react';
import { Pressable, StyleSheet, Text, View } from 'react-native';
import { useQuery } from '@tanstack/react-query';
import { useIsFocused, useNavigation } from '@react-navigation/native';
import type { BottomTabNavigationProp } from '@react-navigation/bottom-tabs';
import { useSafeAreaInsets } from 'react-native-safe-area-context';
import Ionicons from '@expo/vector-icons/Ionicons';
import { AppModal } from './AppModal';
import { GlassSurface } from './GlassSurface';
import { billingApi } from '../api/endpoints';
import { useAuth } from '../context/AuthContext';
import { usePlusOffer } from '../lib/planOffer';
import { safeStorage } from '../lib/safeStorage';
import { trackNavigation } from '../lib/trackNavigation';
import { radius, spacing, useTheme } from '../theme';
import type { AppTabParamList } from '../navigation/types';

/** Per phone and per campaign: closing the Diwali pop-up keeps it closed, but a later campaign (a
 *  new code) shows once again. SecureStore keys allow letters, digits, ".", "-" and "_" only. */
export function offerPopupDismissedKey(code: string): string {
  return `fynora.offerPopupDismissed.${code.replace(/[^A-Za-z0-9._-]/g, '_')}`;
}

/**
 * Announces a live Plus offer once, on the dashboard, to a Free customer who can take it. Mirrors
 * the web app's OfferPopup (frontend/src/components/OfferPopup.tsx).
 *
 * Shown only when Google Play itself would charge the offer price to this customer (usePlusOffer),
 * and only while the backend's campaign is live -- the campaign gives the name and the last day.
 * So: Android only, never to someone who has had Plus before, and never after the campaign ends.
 *
 * Never on top of another sheet: not while the referral-code prompt is up, nor while the dashboard
 * has a sheet of its own open (`suppressed`), since two native modals at once can leave touches dead.
 * Closing it (the cross, the backdrop, the back button or "Get Plus") remembers that for this offer.
 */
export function OfferPopup({ suppressed }: { suppressed: boolean }) {
  const offer = usePlusOffer();
  const focused = useIsFocused();
  const { onboardingCompleted, referralPromptPending } = useAuth();
  const { data: subscription } = useQuery({
    queryKey: ['my-subscription'],
    queryFn: () => billingApi.mySubscription(),
  });
  const code = offer?.code ?? null;
  // What SecureStore says for one campaign code. Kept with its code so a different campaign is
  // "not known yet" (nothing shows) until its own read lands, rather than inheriting the last answer.
  const [dismissal, setDismissal] = useState<{ code: string; dismissed: boolean } | null>(null);
  const dismissed = code != null && dismissal?.code === code ? dismissal.dismissed : undefined;

  useEffect(() => {
    if (!code) return;
    let cancelled = false;
    void safeStorage.getItem(offerPopupDismissedKey(code)).then((stored) => {
      if (!cancelled) setDismissal({ code, dismissed: stored !== null });
    });
    return () => {
      cancelled = true;
    };
  }, [code]);

  const eligible = subscription != null && subscription.planCode === 'FREE' && !subscription.hasBillingSubscription;
  if (!offer || !code || !offer.label || !eligible) return null;
  if (!onboardingCompleted || referralPromptPending || suppressed || !focused || dismissed !== false) return null;

  const close = () => {
    setDismissal({ code, dismissed: true });
    void safeStorage.setItem(offerPopupDismissedKey(code), new Date().toISOString());
  };

  return (
    <OfferSheet
      label={offer.label}
      text={`Plus at ${offer.offerPrice} for your first month (then ${offer.regularPrice}/month).`
        + (offer.lastDay ? ` Ends ${offer.lastDay}.` : '')}
      onClose={close}
    />
  );
}

function OfferSheet({ label, text, onClose }: { label: string; text: string; onClose: () => void }) {
  const c = useTheme();
  const insets = useSafeAreaInsets();
  const navigation = useNavigation<BottomTabNavigationProp<AppTabParamList>>();

  function getPlus() {
    onClose();
    trackNavigation('subscription', 'contextual');
    navigation.navigate('More', { screen: 'Subscription' });
  }

  return (
    <AppModal visible animationType="slide" transparent onRequestClose={onClose}>
      <View style={styles.flex}>
        <Pressable style={styles.backdrop} onPress={onClose} accessibilityLabel="Close offer" />
        <GlassSurface testID="offer-popup" style={[styles.sheet, { paddingBottom: insets.bottom + spacing.md }]}>
          <View style={styles.header}>
            <View style={[styles.icon, { backgroundColor: c.warningBg }]}>
              <Ionicons name="sparkles" size={18} color={c.warning} />
            </View>
            <Pressable onPress={onClose} hitSlop={12} accessibilityRole="button" accessibilityLabel="Close">
              <Ionicons name="close" size={22} color={c.muted} />
            </Pressable>
          </View>
          <Text style={[styles.title, { color: c.ink }]} accessibilityRole="header">{label}</Text>
          <Text style={[styles.text, { color: c.muted }]}>{text}</Text>
          <Pressable
            onPress={getPlus}
            style={[styles.button, { backgroundColor: c.primary }]}
            accessibilityRole="button"
          >
            <Text style={[styles.buttonText, { color: c.onPrimary }]}>Get Plus</Text>
          </Pressable>
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
    paddingTop: spacing.md,
  },
  header: { flexDirection: 'row', justifyContent: 'space-between', alignItems: 'flex-start' },
  icon: { width: 40, height: 40, borderRadius: 20, alignItems: 'center', justifyContent: 'center' },
  title: { fontSize: 17, fontWeight: '700', marginTop: spacing.sm },
  text: { fontSize: 14, marginTop: 4, lineHeight: 20 },
  button: { marginTop: spacing.md, minHeight: 48, borderRadius: radius.md, alignItems: 'center', justifyContent: 'center' },
  buttonText: { fontSize: 15, fontWeight: '700' },
});
