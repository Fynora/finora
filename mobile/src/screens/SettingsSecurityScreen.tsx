import { useState } from 'react';
import { ActivityIndicator, ScrollView, StyleSheet, Text, View } from 'react-native';
import { useQuery, useQueryClient } from '@tanstack/react-query';
import { VerifiedBadge } from '../components/AccountUI';
import { Button } from '../components/Button';
import { AppLockSection } from './settings/AppLockSection';
import { ChangeEmailSheet } from './settings/ChangeEmailSheet';
import { ChangePasswordSheet } from './settings/ChangePasswordSheet';
import { DeviceSessionsSection } from './settings/DeviceSessionsSection';
import { userApi } from '../api/endpoints';
import { fmtRelativeTime } from '../lib/format';
import { maskPhone } from '../lib/maskPhone';
import { spacing, useTheme } from '../theme';

export function SettingsSecurityScreen() {
  const c = useTheme();
  const queryClient = useQueryClient();
  const userQ = useQuery({ queryKey: ['user-settings'], queryFn: () => userApi.get() });
  const user = userQ.data;

  const [changePasswordOpen, setChangePasswordOpen] = useState(false);
  const [changeEmailOpen, setChangeEmailOpen] = useState(false);

  if (userQ.isLoading) {
    return (
      <View style={[styles.centered, { backgroundColor: c.bg }]}>
        <ActivityIndicator size="large" color={c.primary} />
      </View>
    );
  }

  // Bug found in a fresh review pass: collapsing isLoading and !user into one branch meant a
  // genuine load failure (isError, data stays undefined) looked identical to still-loading --
  // the spinner never stopped and no error was ever shown. The pre-redesign monolith showed an
  // explicit message here instead.
  if (userQ.isError || !user) {
    return (
      <View style={[styles.centered, { backgroundColor: c.bg }]}>
        <Text style={[styles.message, { color: c.muted }]}>
          Couldn&apos;t load your settings — please try again later.
        </Text>
      </View>
    );
  }

  const passwordChanged = fmtRelativeTime(user.passwordChangedAt);

  return (
    <ScrollView style={{ backgroundColor: c.bg }} contentContainerStyle={styles.content}>
      <View style={[styles.row, { borderBottomColor: c.border }]}>
        <View style={styles.rowMain}>
          <Text style={[styles.rowTitle, { color: c.ink }]}>Email</Text>
          <Text style={[styles.rowMeta, { color: c.mutedInk }]}>{user.email}</Text>
        </View>
      </View>
      <View style={styles.changePassword}>
        <Button label="Change Email" onPress={() => setChangeEmailOpen(true)} />
      </View>

      <View style={[styles.row, { borderBottomColor: c.border }]}>
        <View style={styles.rowMain}>
          <Text style={[styles.rowTitle, { color: c.ink }]}>Password</Text>
          <Text style={[styles.rowMeta, { color: c.mutedInk }]}>
            {passwordChanged ? `Last changed ${passwordChanged}` : 'Never changed'}
          </Text>
        </View>
      </View>
      <View style={styles.changePassword}>
        <Button label="Change Password" onPress={() => setChangePasswordOpen(true)} />
      </View>

      <View style={[styles.row, { borderBottomColor: c.border }]}>
        <View style={styles.rowMain}>
          <Text style={[styles.rowTitle, { color: c.ink }]}>Phone verification</Text>
          <Text style={[styles.rowMeta, { color: c.mutedInk }]}>
            {user.phoneNumber ? maskPhone(user.phoneNumber) : 'No phone number on file'}
          </Text>
        </View>
        {user.phoneVerified ? (
          <VerifiedBadge />
        ) : (
          <Text style={[styles.rowMeta, { color: c.mutedInk }]}>Not verified</Text>
        )}
      </View>

      <AppLockSection />

      <View style={styles.sessions}>
        <DeviceSessionsSection />
      </View>

      {changePasswordOpen ? (
        <ChangePasswordSheet
          onClose={() => setChangePasswordOpen(false)}
          onSuccess={() => {
            void queryClient.invalidateQueries({ queryKey: ['user-settings'] });
            void queryClient.invalidateQueries({ queryKey: ['devices'] });
          }}
          signInMethod={user.signInMethod}
        />
      ) : null}

      {changeEmailOpen ? (
        <ChangeEmailSheet onClose={() => setChangeEmailOpen(false)} signInMethod={user.signInMethod} />
      ) : null}
    </ScrollView>
  );
}

const styles = StyleSheet.create({
  centered: { flex: 1, alignItems: 'center', justifyContent: 'center', padding: spacing.lg },
  message: { fontSize: 14, textAlign: 'center' },
  content: { padding: spacing.md, paddingBottom: spacing.xl },
  row: {
    flexDirection: 'row', alignItems: 'center', justifyContent: 'space-between',
    paddingVertical: 10, borderBottomWidth: StyleSheet.hairlineWidth,
  },
  rowMain: { flex: 1, marginRight: spacing.sm },
  rowTitle: { fontSize: 14, fontWeight: '600' },
  rowMeta: { fontSize: 12, marginTop: 2 },
  changePassword: { marginTop: spacing.sm, marginBottom: spacing.md },
  sessions: { marginTop: spacing.md },
});
