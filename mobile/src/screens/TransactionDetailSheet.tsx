import { ActivityIndicator, Modal, Pressable, ScrollView, StyleSheet, Text, View } from 'react-native';
import { useSafeAreaInsets } from 'react-native-safe-area-context';
import Ionicons from '@expo/vector-icons/Ionicons';
import { Button } from '../components/Button';
import { MerchantLogo } from '../components/MerchantLogo';
import { counterpartyLabel } from '../lib/counterpartyLabel';
import { fmtCurrency } from '../lib/format';
import { reconciliationBadge } from '../lib/reconciliationBadge';
import { radius, spacing, useTheme } from '../theme';
import type { Transaction } from '../types';
import { statusBadges } from './LedgerScreen';

interface Props {
  transaction: Transaction;
  onClose: () => void;
  onChangeCategory: () => void;
  onEdit: () => void;
  onViewSource: () => void;
  onExplainCategory: () => void;
  onMarkTransfer: () => void;
  onUnmarkTransfer: () => void;
  unmarking: boolean;
  onViewCorrection: () => void;
  onDelete: () => void;
  deleting: boolean;
}

/**
 * Redesign (per the reference layout each row's chevron now points to): the five actions a row
 * used to expose as its own inline icon buttons -- view source, edit, explain category, mark/
 * unmark transfer, view bank correction -- plus the row's own tap-to-recategorize shortcut, now
 * live here instead. LedgerScreen still owns every one of the underlying modals
 * (TransactionSourceModal/EditTransactionSheet/TransactionExplanationModal/MarkTransferModal/
 * BankCorrectionModal/OptionPickerModal) and every mutation (delete, mark/unmark transfer) --
 * this sheet only relocates the trigger UI, so none of that logic is duplicated or reimplemented.
 *
 * Long-press-to-delete on the row itself is untouched by this addition (see LedgerScreen.tsx) --
 * "Delete Transaction" below is a second, more discoverable path to the exact same
 * confirmDelete(t), not a replacement for it.
 *
 * Each action here is its own top-level, independently accessible Pressable -- unlike the icon
 * buttons this replaces, which were nested inside the row's own already-accessible Pressable and
 * so could never be an independently reachable screen-reader stop (VoiceOver/TalkBack group a
 * whole such subtree into one atomic element). Those needed a dedicated accessibilityAction on the
 * outer row as their only real reachable path; these need nothing extra to be reachable.
 */
export function TransactionDetailSheet({
  transaction: t, onClose, onChangeCategory, onEdit, onViewSource, onExplainCategory,
  onMarkTransfer, onUnmarkTransfer, unmarking, onViewCorrection, onDelete, deleting,
}: Props) {
  const c = useTheme();
  const insets = useSafeAreaInsets();
  // Bug found in review: while a delete or unmark is in flight, LedgerScreen's own completion
  // callback closes THIS sheet once that request settles (see LedgerScreen.tsx's onDelete/
  // onUnmarkTransfer wiring) -- unconditionally, by calling setViewingDetail(null) on whatever is
  // currently open. Without this guard, nothing stopped the user from dismissing this sheet mid-
  // request (backdrop tap, the close icon, Android back) and opening a DIFFERENT transaction's
  // sheet before the first request settled -- whose completion would then force-close that
  // unrelated sheet out from under them. Disabling dismissal while busy, the same pattern
  // EditTransactionSheet already uses for its own `saving` guard, closes the race instead of
  // just patching around it.
  const busy = deleting || unmarking;
  const cp = counterpartyLabel(t.counterpartyType, t.type);
  const badge = reconciliationBadge(t.reconciliationStatus);
  const badgeColors = badge ? {
    danger: { bg: c.dangerBg, fg: c.danger },
    primary: { bg: c.primaryLight, fg: c.primary },
    success: { bg: c.successBg, fg: c.success },
    warning: { bg: c.warningBg, fg: c.warning },
    muted: { bg: c.border, fg: c.mutedInk },
  }[badge.tone] : null;
  const badges = statusBadges(t);
  const badgeToneColors = {
    primary: { bg: c.primaryLight, fg: c.primary },
    success: { bg: c.successBg, fg: c.success },
    warning: { bg: c.warningBg, fg: c.warning },
    danger: { bg: c.dangerBg, fg: c.danger },
  } as const;

  return (
    <Modal visible transparent animationType="slide" onRequestClose={busy ? () => {} : onClose}>
      <View style={styles.flex}>
        <Pressable
          style={styles.backdrop}
          onPress={busy ? undefined : onClose}
          disabled={busy}
          accessibilityLabel="Close transaction details"
        />
        <View style={[styles.sheet, { backgroundColor: c.card, paddingBottom: insets.bottom + spacing.md }]}>
          <ScrollView style={styles.scroll}>
            <View style={styles.headerRow}>
              <Text style={[styles.title, { color: c.ink }]}>Transaction Details</Text>
              <Pressable
                onPress={onClose}
                disabled={busy}
                hitSlop={10}
                accessibilityRole="button"
                accessibilityLabel="Close"
              >
                <Ionicons name="close" size={22} color={busy ? c.border : c.muted} />
              </Pressable>
            </View>

            <View style={styles.summary}>
              <MerchantLogo merchant={t.merchant || t.description || '?'} size={44} />
              <View style={styles.summaryText}>
                <Text style={[styles.desc, { color: c.ink }]}>{t.description || t.merchant || 'Transaction'}</Text>
                <Text style={[styles.amount, { color: t.type === 'INCOME' ? c.success : c.danger }]}>
                  {t.type === 'INCOME' ? '+' : '-'}{fmtCurrency(Math.abs(t.amount))}
                </Text>
              </View>
            </View>

            <View style={styles.badgeRow}>
              {badge && badgeColors ? (
                <Text style={[styles.pill, { backgroundColor: badgeColors.bg, color: badgeColors.fg }]}>
                  {badge.label}
                </Text>
              ) : null}
              {badges.map((b) => (
                <Text
                  key={b.label}
                  style={[styles.pill, { backgroundColor: badgeToneColors[b.tone].bg, color: badgeToneColors[b.tone].fg }]}
                >
                  {b.label}
                </Text>
              ))}
            </View>

            <View style={[styles.infoCard, { borderColor: c.border }]}>
              <InfoRow label="Category" value={t.categoryName} />
              <InfoRow label="Date" value={t.date} />
              {t.paymentMethod ? <InfoRow label="Payment Method" value={t.paymentMethod} /> : null}
              {cp ? <InfoRow label="Counterparty" value={cp.full} /> : null}
            </View>

            {/* Bug found in review: every row below gets `disabled={busy}` -- while a delete or
                unmark is in flight for THIS transaction, every other action (Edit, Change
                Category, Mark Transfer, ...) stayed fully interactive, so a user fast enough
                could navigate away to edit -- or start marking a transfer for -- a transaction
                that might cease to exist moments later. Only the row whose OWN request is running
                showed any disabled state before this; the rest need to wait for that request to
                settle too, not just avoid double-firing themselves. */}
            <View style={styles.actionsList}>
              <ActionRow
                icon="pricetag-outline"
                label="Change Category"
                onPress={onChangeCategory}
                disabled={busy}
                testID={`category-button-${t.id}`}
              />
              <ActionRow
                icon="pencil-outline"
                label="Edit Transaction"
                onPress={onEdit}
                disabled={busy}
                testID={`edit-button-${t.id}`}
              />
              <ActionRow
                icon="information-circle-outline"
                label="Where This Came From"
                onPress={onViewSource}
                disabled={busy}
                testID={`source-button-${t.id}`}
              />
              <ActionRow
                icon="help-circle-outline"
                label="Why This Category?"
                onPress={onExplainCategory}
                disabled={busy}
                testID={`explain-button-${t.id}`}
              />
              {t.reconciliationStatus === 'TRANSFER' ? (
                <ActionRow
                  icon="swap-horizontal"
                  label="Unmark as Transfer"
                  onPress={onUnmarkTransfer}
                  loading={unmarking}
                  disabled={busy}
                  testID={`unmark-transfer-button-${t.id}`}
                />
              ) : t.reconciliationStatus === 'OK' ? (
                <ActionRow
                  icon="swap-horizontal-outline"
                  label="Mark as Transfer"
                  onPress={onMarkTransfer}
                  disabled={busy}
                  testID={`mark-transfer-button-${t.id}`}
                />
              ) : null}
              {t.pendingBankCorrection ? (
                <ActionRow
                  icon="alert-circle-outline"
                  label="View Bank Correction"
                  onPress={onViewCorrection}
                  disabled={busy}
                  tone="danger"
                  testID={`bank-correction-button-${t.id}`}
                />
              ) : null}
              <ActionRow
                icon="trash-outline"
                label="Delete Transaction"
                disabled={busy}
                onPress={onDelete}
                loading={deleting}
                tone="danger"
                testID={`delete-button-${t.id}`}
                last
              />
            </View>

            <Button label="Close" variant="link" onPress={onClose} disabled={busy} />
          </ScrollView>
        </View>
      </View>
    </Modal>
  );
}

function InfoRow({ label, value }: { label: string; value: string }) {
  const c = useTheme();
  return (
    <View style={styles.infoRow}>
      <Text style={[styles.infoLabel, { color: c.muted }]}>{label}</Text>
      <Text style={[styles.infoValue, { color: c.ink }]}>{value}</Text>
    </View>
  );
}

function ActionRow({
  icon, label, onPress, testID, loading = false, disabled = false, tone = 'default', last = false,
}: {
  icon: keyof typeof Ionicons.glyphMap;
  label: string;
  onPress: () => void;
  testID: string;
  loading?: boolean;
  /** Distinct from `loading` -- `loading` is THIS row's own request running (shows the spinner);
   *  `disabled` is every OTHER row while `loading` is true somewhere else in the sheet (no
   *  spinner of its own, just non-interactive, so the user can't navigate away from a transaction
   *  a request elsewhere in this same sheet might be about to change or remove). */
  disabled?: boolean;
  tone?: 'default' | 'danger';
  last?: boolean;
}) {
  const c = useTheme();
  const color = tone === 'danger' ? c.danger : c.ink;
  const isDisabled = loading || disabled;
  return (
    <Pressable
      onPress={onPress}
      disabled={isDisabled}
      testID={testID}
      accessibilityRole="button"
      accessibilityLabel={label}
      accessibilityState={{ disabled: isDisabled, busy: loading }}
      style={[
        styles.actionRow,
        !last && { borderBottomColor: c.border, borderBottomWidth: StyleSheet.hairlineWidth },
        // Not while `loading`: that row already communicates its own state via the spinner
        // replacing its chevron, so dimming it too would be a redundant, weaker second signal.
        // This is for every OTHER row -- non-interactive, but not the one actually busy.
        disabled && !loading && styles.disabledRow,
      ]}
    >
      <Ionicons name={icon} size={20} color={color} style={styles.actionIcon} />
      <Text style={[styles.actionLabel, { color }]}>{label}</Text>
      {loading ? (
        <ActivityIndicator size="small" color={c.muted} />
      ) : (
        <Ionicons name="chevron-forward" size={18} color={c.muted} />
      )}
    </Pressable>
  );
}

const styles = StyleSheet.create({
  flex: { flex: 1 },
  backdrop: { flex: 1, backgroundColor: 'rgba(0,0,0,0.35)' },
  sheet: {
    maxHeight: '90%',
    borderTopLeftRadius: radius.xl,
    borderTopRightRadius: radius.xl,
    paddingHorizontal: spacing.md,
    paddingTop: spacing.md,
  },
  scroll: { flexGrow: 0 },
  headerRow: {
    flexDirection: 'row', justifyContent: 'space-between', alignItems: 'center', marginBottom: spacing.md,
  },
  title: { fontSize: 17, fontWeight: '700' },
  summary: { flexDirection: 'row', alignItems: 'center', gap: spacing.sm, marginBottom: spacing.sm },
  summaryText: { flex: 1 },
  desc: { fontSize: 16, fontWeight: '600' },
  amount: { fontSize: 20, fontWeight: '700', marginTop: 2 },
  badgeRow: { flexDirection: 'row', flexWrap: 'wrap', gap: 6, marginBottom: spacing.md },
  pill: {
    fontSize: 10, fontWeight: '700', textTransform: 'uppercase',
    paddingHorizontal: 6, paddingVertical: 2, borderRadius: radius.md, overflow: 'hidden',
  },
  infoCard: {
    borderWidth: 1, borderRadius: radius.md, padding: spacing.sm, marginBottom: spacing.md,
  },
  infoRow: { flexDirection: 'row', justifyContent: 'space-between', paddingVertical: 4 },
  infoLabel: { fontSize: 13 },
  infoValue: { fontSize: 13, fontWeight: '600' },
  actionsList: { marginBottom: spacing.sm },
  actionRow: { flexDirection: 'row', alignItems: 'center', paddingVertical: 14 },
  disabledRow: { opacity: 0.5 },
  actionIcon: { marginRight: spacing.sm },
  actionLabel: { fontSize: 15, flex: 1 },
});
