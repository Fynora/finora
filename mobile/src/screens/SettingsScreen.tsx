import { useState } from 'react';
import { ScrollView, StyleSheet } from 'react-native';
import { useNavigation } from '@react-navigation/native';
import type { NativeStackNavigationProp } from '@react-navigation/native-stack';
import { MenuGroup, MenuRow, type MenuIcon } from '../components/MenuGroup';
import { FeedbackSheet } from './support/FeedbackSheet';
import { spacing, THEME_LABEL, useThemeSetting } from '../theme';
import { openWebUrl } from '../lib/webUrl';
import { trackNavigation } from '../lib/trackNavigation';
import { GMAIL_SYNC_UI_ENABLED } from '../lib/features';
import type { MoreStackParamList } from '../navigation/types';
import { GlassScreen } from '../components/GlassScreen';

const CATEGORIES: { route: keyof MoreStackParamList; label: string; description: string; icon: MenuIcon }[] = [
  { route: 'SettingsGeneral', label: 'General', description: 'Preferences, timezone, theme', icon: 'options-outline' },
  { route: 'SettingsSecurity', label: 'Security', description: 'Password, verification, active sessions', icon: 'shield-checkmark-outline' },
  { route: 'SettingsCategorization', label: 'Categorization', description: 'How confident a suggestion must be to apply on its own', icon: 'pricetags-outline' },
  { route: 'SettingsData', label: 'Data', description: 'Your imported statements and transaction history', icon: 'server-outline' },
  { route: 'SettingsConnectedApps', label: 'Connected Apps', description: 'Link external accounts Fynora can read transactions from', icon: 'link-outline' },
  { route: 'SettingsBankSync', label: 'Bank Sync', description: 'Automatically sync transactions from your linked bank accounts', icon: 'sync-outline' },
  { route: 'SettingsAccount', label: 'Account', description: 'Deactivate or permanently delete your Fynora account', icon: 'person-circle-outline' },
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
 *
 * The categories carry no group label: the screen's own header already says "Settings", and a
 * second "Settings" a few points below it only repeated that.
 */
export function SettingsScreen() {
  const navigation = useNavigation<NativeStackNavigationProp<MoreStackParamList>>();
  const { setting: themeSetting } = useThemeSetting();
  const [feedbackOpen, setFeedbackOpen] = useState(false);
  // Connected Apps holds only Gmail sync, which is paused (lib/features.ts), so the whole row goes
  // with it. Filtered here at render time rather than edited out of CATEGORIES so switching the
  // feature back on restores the row in its old place with no other change.
  const categories = CATEGORIES.filter((cat) => cat.route !== 'SettingsConnectedApps' || GMAIL_SYNC_UI_ENABLED);
  // Only General has one current choice worth surfacing on its row. Every other category opens
  // onto several independent settings, and picking one of them to show would mislead.
  const valueByRoute: Partial<Record<keyof MoreStackParamList, string>> = {
    SettingsGeneral: THEME_LABEL[themeSetting],
  };

  return (
    <GlassScreen style={styles.glassRoot}>
    <ScrollView contentContainerStyle={styles.content}>
      <MenuGroup>
        {categories.map((cat) => (
          <MenuRow
            key={cat.route}
            icon={cat.icon}
            label={cat.label}
            description={cat.description}
            value={valueByRoute[cat.route]}
            onPress={() => navigation.navigate(cat.route as never)}
          />
        ))}
      </MenuGroup>

      <MenuGroup label="Help & Support">
        <MenuRow
          icon="help-buoy-outline"
          label="My Tickets"
          description="File a new one, or check on an existing one"
          onPress={() => { trackNavigation('support', 'group'); navigation.navigate('SupportTickets'); }}
        />
        <MenuRow
          icon="chatbox-ellipses-outline"
          label="Send Feedback"
          description="A bug, an idea, or anything else on your mind"
          onPress={() => setFeedbackOpen(true)}
        />
      </MenuGroup>

      {/* Written out row by row, not mapped from a table: appLinks.selfOpen.test.ts finds every
          page the app sends to a browser by reading the literal path out of each call, to prove
          none of them is a path Android would hand straight back to the app. A path passed as a
          variable is invisible to that scan, and the same test now fails on one. */}
      <MenuGroup label="Legal">
        <MenuRow icon="lock-closed-outline" label="Privacy Policy" accessibilityRole="link" onPress={() => openWebUrl('/privacy')} />
        <MenuRow icon="document-text-outline" label="Terms of Service" accessibilityRole="link" onPress={() => openWebUrl('/terms')} />
        <MenuRow icon="shield-outline" label="Trust & Security" accessibilityRole="link" onPress={() => openWebUrl('/trust')} />
        <MenuRow icon="download-outline" label="Data Portability Promise" accessibilityRole="link" onPress={() => openWebUrl('/your-data')} />
      </MenuGroup>

      {feedbackOpen ? <FeedbackSheet onClose={() => setFeedbackOpen(false)} /> : null}
    </ScrollView>
    </GlassScreen>
  );
}

const styles = StyleSheet.create({
  glassRoot: { flex: 1 },
  // MenuGroup brings its own top margin, so the scroll content starts with none of its own.
  content: { paddingHorizontal: spacing.md, paddingBottom: spacing.xl },
});
