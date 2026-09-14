import { ScrollView, StyleSheet } from 'react-native';
import { SectionCard } from '../components/AccountUI';
import { GmailConnectionSection } from './settings/GmailConnectionSection';
import { spacing, useTheme } from '../theme';

export function SettingsConnectedAppsScreen() {
  const c = useTheme();
  return (
    <ScrollView style={{ backgroundColor: c.bg }} contentContainerStyle={styles.content}>
      <SectionCard title="Connected Apps" subtitle="Link external accounts Fynora can read transactions from">
        <GmailConnectionSection />
      </SectionCard>
    </ScrollView>
  );
}

const styles = StyleSheet.create({ content: { padding: spacing.md, paddingBottom: spacing.xl } });
