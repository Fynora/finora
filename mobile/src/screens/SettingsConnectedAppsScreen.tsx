import { ScrollView, StyleSheet } from 'react-native';
import { SectionCard } from '../components/AccountUI';
import { GmailConnectionSection } from './settings/GmailConnectionSection';
import { spacing } from '../theme';
import { GlassScreen } from '../components/GlassScreen';

export function SettingsConnectedAppsScreen() {
  return (
    <GlassScreen style={styles.glassRoot}>
    <ScrollView contentContainerStyle={styles.content}>
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
