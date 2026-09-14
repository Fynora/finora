import { useState } from 'react';
import { ActivityIndicator, ScrollView, StyleSheet, Text, View } from 'react-native';
import { useQuery } from '@tanstack/react-query';
import type { NativeStackScreenProps } from '@react-navigation/native-stack';
import { Button } from '../components/Button';
import { DeactivateAccountSheet } from './settings/DeactivateAccountSheet';
import { DeleteAccountSheet } from './settings/DeleteAccountSheet';
import { userApi } from '../api/endpoints';
import { useAuth } from '../context/AuthContext';
import { spacing, useTheme } from '../theme';
import type { MoreStackParamList } from '../navigation/types';

type Props = NativeStackScreenProps<MoreStackParamList, 'SettingsAccount'>;

export function SettingsAccountScreen({ navigation }: Props) {
  const c = useTheme();
  const { logout } = useAuth();
  const userQ = useQuery({ queryKey: ['user-settings'], queryFn: () => userApi.get() });

  const [deactivateOpen, setDeactivateOpen] = useState(false);
  const [deleteOpen, setDeleteOpen] = useState(false);

  // UserAccountLifecycleService.deactivate/requestDeletion already revoke every refresh token
  // server-side before either sheet calls this -- logout() here is purely local cleanup.
  function endSessionAfterLifecycleAction() {
    setDeactivateOpen(false);
    setDeleteOpen(false);
    logout();
  }

  // Google/Apple-linked accounts can't complete Deactivate/Delete in-app yet -- routes to the one
  // in-app channel that can actually help. Same modal-dismiss-race fix as the original
  // SettingsScreen's contactSupportForAccountAction (see SettingsDataScreen's identical comment).
  function contactSupportForAccountAction() {
    setDeactivateOpen(false);
    setDeleteOpen(false);
    setTimeout(() => navigation.navigate('SupportTickets'), 350);
  }

  if (userQ.isLoading) {
    return (
      <View style={[styles.centered, { backgroundColor: c.bg }]}>
        <ActivityIndicator size="large" color={c.primary} />
      </View>
    );
  }

  // Bug found in a fresh review pass: same issue as SettingsSecurityScreen -- collapsing
  // isLoading and !userQ.data into one branch meant a load failure looked identical to
  // still-loading, spinning forever instead of showing the pre-redesign monolith's error message.
  if (userQ.isError || !userQ.data) {
    return (
      <View style={[styles.centered, { backgroundColor: c.bg }]}>
        <Text style={[styles.message, { color: c.muted }]}>
          Couldn&apos;t load your settings — please try again later.
        </Text>
      </View>
    );
  }

  const signInMethod = userQ.data.signInMethod;

  return (
    <ScrollView style={{ backgroundColor: c.bg }} contentContainerStyle={styles.content}>
      <View style={[styles.dangerRow, { borderBottomColor: c.border }]}>
        <Text style={[styles.fieldLabel, { color: c.ink, marginTop: 0 }]}>Deactivate Account</Text>
        <Text style={[styles.hint, { color: c.mutedInk }]}>
          Temporarily disable your account. You&apos;ll be signed out everywhere and won&apos;t be
          able to sign in until you reactivate — your data is retained securely, and reactivating
          is as simple as signing in again.
        </Text>
        <Button label="Deactivate Account" onPress={() => setDeactivateOpen(true)} variant="link" />
      </View>
      <View style={[styles.dangerRow, { borderBottomColor: 'transparent' }]}>
        <Text style={[styles.fieldLabel, { color: c.danger, marginTop: 0 }]}>Delete Account</Text>
        <Text style={[styles.hint, { color: c.mutedInk }]}>
          Permanently delete your account and all your data. This cannot be undone, and there is
          no way to cancel this request once submitted.
        </Text>
        <Button label="Delete Account" onPress={() => setDeleteOpen(true)} variant="link" />
      </View>

      {deactivateOpen ? (
        <DeactivateAccountSheet
          onClose={() => setDeactivateOpen(false)}
          onDeactivated={endSessionAfterLifecycleAction}
          signInMethod={signInMethod}
          onContactSupport={contactSupportForAccountAction}
        />
      ) : null}

      {deleteOpen ? (
        <DeleteAccountSheet
          onClose={() => setDeleteOpen(false)}
          onDeleted={endSessionAfterLifecycleAction}
          signInMethod={signInMethod}
          onContactSupport={contactSupportForAccountAction}
        />
      ) : null}
    </ScrollView>
  );
}

const styles = StyleSheet.create({
  centered: { flex: 1, alignItems: 'center', justifyContent: 'center', padding: spacing.lg },
  message: { fontSize: 14, textAlign: 'center' },
  content: { padding: spacing.md, paddingBottom: spacing.xl },
  fieldLabel: { fontSize: 12, fontWeight: '500', marginBottom: 6 },
  hint: { fontSize: 11, lineHeight: 16, marginTop: spacing.sm },
  dangerRow: { paddingVertical: spacing.md, borderBottomWidth: StyleSheet.hairlineWidth, alignItems: 'flex-start' },
});
