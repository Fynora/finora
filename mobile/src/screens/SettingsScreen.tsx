import { useState } from 'react';
import { Linking, Pressable, ScrollView, StyleSheet, Text, View } from 'react-native';
import { useNavigation } from '@react-navigation/native';
import type { NativeStackNavigationProp } from '@react-navigation/native-stack';
import { SectionCard } from '../components/AccountUI';
import { FeedbackSheet } from './support/FeedbackSheet';
import { spacing, useTheme } from '../theme';
import { webUrl } from '../lib/webUrl';
import type { MoreStackParamList } from '../navigation/types';

const CATEGORIES: { route: keyof MoreStackParamList; label: string; description: string }[] = [
  { route: 'SettingsGeneral', label: 'General', description: 'Preferences, timezone, theme' },
  { route: 'SettingsSecurity', label: 'Security', description: 'Password, verification, active sessions' },
  { route: 'SettingsCategorization', label: 'Categorization', description: 'How confident a suggestion must be to apply on its own' },
  { route: 'SettingsData', label: 'Data', description: 'Your imported statements and transaction history' },
  { route: 'SettingsConnectedApps', label: 'Connected Apps', description: 'Link external accounts Fynora can read transactions from' },
  { route: 'SettingsBankSync', label: 'Bank Sync', description: 'Automatically sync transactions from your linked bank accounts' },
  { route: 'SettingsAccount', label: 'Account', description: 'Deactivate or permanently delete your Fynora account' },
];

/**
 * Root of the Settings redesign: a grouped list (matching iOS/Android's own Settings app
 * pattern) that pushes each category to its own screen, instead of the single 679-line
 * ScrollView this screen used to be. See docs/superpowers/plans/
 * 2026-09-14-settings-redesign-mobile.md.
 *
 * Help & Support and Legal stay exactly as they were -- standalone rows, not folded into a
 * category. They're static links/tickets, not settings that get changed; a one-item category
 * pane for either would be worse than a direct row.
 */
export function SettingsScreen() {
  const c = useTheme();
  const navigation = useNavigation<NativeStackNavigationProp<MoreStackParamList>>();
  const [feedbackOpen, setFeedbackOpen] = useState(false);

  return (
    <ScrollView style={{ backgroundColor: c.bg }} contentContainerStyle={styles.content}>
      <SectionCard title="Settings" subtitle="Manage your preferences, security, and account data">
        {CATEGORIES.map((cat) => (
          <Pressable
            key={cat.route}
            onPress={() => navigation.navigate(cat.route as never)}
            style={[styles.row, { borderBottomColor: c.border }]}
            accessibilityRole="button"
          >
            <View style={styles.rowMain}>
              <Text style={[styles.rowTitle, { color: c.ink }]}>{cat.label}</Text>
              <Text style={[styles.rowMeta, { color: c.mutedInk }]}>{cat.description}</Text>
            </View>
            <Text style={[styles.chevron, { color: c.muted }]} accessibilityElementsHidden importantForAccessibility="no">›</Text>
          </Pressable>
        ))}
      </SectionCard>

      <SectionCard title="Help & Support" subtitle="File a ticket, check on one, or tell us what's on your mind">
        <Pressable
          onPress={() => navigation.navigate('SupportTickets')}
          style={[styles.row, { borderBottomColor: c.border }]}
          accessibilityRole="button"
        >
          <View style={styles.rowMain}>
            <Text style={[styles.rowTitle, { color: c.ink }]}>My Tickets</Text>
            <Text style={[styles.rowMeta, { color: c.mutedInk }]}>File a new one, or check on an existing one</Text>
          </View>
          <Text style={[styles.chevron, { color: c.muted }]} accessibilityElementsHidden importantForAccessibility="no">›</Text>
        </Pressable>
        <Pressable
          onPress={() => setFeedbackOpen(true)}
          style={[styles.row, { borderBottomColor: c.border }]}
          accessibilityRole="button"
        >
          <View style={styles.rowMain}>
            <Text style={[styles.rowTitle, { color: c.ink }]}>Send Feedback</Text>
            <Text style={[styles.rowMeta, { color: c.mutedInk }]}>A bug, an idea, or anything else on your mind</Text>
          </View>
          <Text style={[styles.chevron, { color: c.muted }]} accessibilityElementsHidden importantForAccessibility="no">›</Text>
        </Pressable>
      </SectionCard>

      <SectionCard title="Legal" subtitle="How Fynora handles your data">
        <Pressable
          onPress={() => Linking.openURL(webUrl('/privacy'))}
          style={[styles.row, { borderBottomColor: c.border }]}
          accessibilityRole="link"
        >
          <View style={styles.rowMain}><Text style={[styles.rowTitle, { color: c.ink }]}>Privacy Policy</Text></View>
          <Text style={[styles.chevron, { color: c.muted }]} accessibilityElementsHidden importantForAccessibility="no">›</Text>
        </Pressable>
        <Pressable
          onPress={() => Linking.openURL(webUrl('/terms'))}
          style={[styles.row, { borderBottomColor: c.border }]}
          accessibilityRole="link"
        >
          <View style={styles.rowMain}>
            <Text style={[styles.rowTitle, { color: c.ink }]}>Terms of Service</Text>
          </View>
          <Text style={[styles.chevron, { color: c.muted }]} accessibilityElementsHidden importantForAccessibility="no">›</Text>
        </Pressable>
        <Pressable
          onPress={() => Linking.openURL(webUrl('/trust'))}
          style={[styles.row, { borderBottomColor: c.border }]}
          accessibilityRole="link"
        >
          <View style={styles.rowMain}>
            <Text style={[styles.rowTitle, { color: c.ink }]}>Trust & Security</Text>
          </View>
          <Text style={[styles.chevron, { color: c.muted }]} accessibilityElementsHidden importantForAccessibility="no">›</Text>
        </Pressable>
        <Pressable
          onPress={() => Linking.openURL(webUrl('/your-data'))}
          style={styles.row}
          accessibilityRole="link"
        >
          <View style={styles.rowMain}>
            <Text style={[styles.rowTitle, { color: c.ink }]}>Data Portability Promise</Text>
          </View>
          <Text style={[styles.chevron, { color: c.muted }]} accessibilityElementsHidden importantForAccessibility="no">›</Text>
        </Pressable>
      </SectionCard>

      {feedbackOpen ? <FeedbackSheet onClose={() => setFeedbackOpen(false)} /> : null}
    </ScrollView>
  );
}

const styles = StyleSheet.create({
  content: { padding: spacing.md, paddingBottom: spacing.xl },
  row: {
    flexDirection: 'row', alignItems: 'center', justifyContent: 'space-between',
    paddingVertical: 12, borderBottomWidth: StyleSheet.hairlineWidth, minHeight: 44,
  },
  rowMain: { flex: 1, marginRight: spacing.sm },
  rowTitle: { fontSize: 15, fontWeight: '600' },
  rowMeta: { fontSize: 12, marginTop: 2 },
  chevron: { fontSize: 20, lineHeight: 20 },
});
