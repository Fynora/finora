import { ScrollView, StyleSheet } from 'react-native';
import { SectionCard } from '../components/AccountUI';
import { GmailConnectionSection } from './settings/GmailConnectionSection';
import { spacing } from '../theme';
import { GlassScreen } from '../components/GlassScreen';
import { padForTabBar, useTabBarSpace } from '../navigation/useTabBarSpace';

export function SettingsConnectedAppsScreen() {
  const tabBar = useTabBarSpace();
  return (
    <GlassScreen style={styles.glassRoot}>
    <ScrollView contentContainerStyle={padForTabBar(styles.content, tabBar)} scrollIndicatorInsets={{ bottom: tabBar }}>
      <SectionCard title="Connected Apps" subtitle="Link external accounts Fynora can read transactions from">
        <GmailConnectionSection />
      </SectionCard>
    </ScrollView>
    </GlassScreen>
  );
}

const styles = StyleSheet.create({
  glassRoot: { flex: 1 },
  content: { padding: spacing.md, paddingBottom: spacing.xl },
});
