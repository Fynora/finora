import { ActivityIndicator, Modal, StyleSheet, Text, View } from 'react-native';
import { useQuery } from '@tanstack/react-query';
import { transactionsApi } from '../api/endpoints';
import { Button } from './Button';
import { Card, EmptyState, SectionHeading } from './Card';
import { toUserMessage } from '../lib/apiError';
import { reconciliationBadge } from '../lib/reconciliationBadge';
import { spacing, useTheme } from '../theme';

/**
 * "Why this category?" (Phase 4/Medium-Tier Parity) -- ported from frontend/src/pages/Ledger.tsx's
 * ExplanationModal. Same plain Modal + Card + backdrop shape and same lazy `enabled`-gated
 * useQuery keyed by id as TransactionSourceModal (Track C/C7), the closest existing precedent for
 * an on-demand detail panel in this codebase.
 *
 * transactionsApi.explanation already computes the full categorization AND reconciliation
 * reasoning server-side -- this was simply never rendered anywhere on mobile, so the Ledger row's
 * category chip and reconciliationBadge pill (LedgerScreen.tsx) were both static labels with no
 * way to ask "why".
 */
export function TransactionExplanationModal({
  transactionId, category, onClose,
}: {
  transactionId: string | null;
  /** The row's own current category label, shown above the answer as context -- same reason
   *  TransactionSourceModal doesn't need this: that modal has nothing of the row's own to echo
   *  back, while "why this category" is meaningless without saying which category it's explaining. */
  category: string | null;
  onClose: () => void;
}) {
  const c = useTheme();
  const { data, isLoading, isError, error } = useQuery({
    queryKey: ['transaction-explanation', transactionId],
    queryFn: () => transactionsApi.explanation(transactionId as string),
    enabled: transactionId !== null,
  });

  if (transactionId === null) return null;

  const badge = data?.reconciliation ? reconciliationBadge(data.reconciliation.status) : null;
  const badgeColors = badge ? ({
    danger: { bg: c.dangerBg, fg: c.danger },
    primary: { bg: c.primaryLight, fg: c.primary },
    success: { bg: c.successBg, fg: c.success },
    warning: { bg: c.warningBg, fg: c.warning },
    muted: { bg: c.border, fg: c.mutedInk },
  } as const)[badge.tone] : null;

  return (
    <Modal visible transparent animationType="slide" onRequestClose={onClose}>
      <View style={styles.backdrop}>
        <Card style={styles.card}>
          <SectionHeading title="Why this category?" />
          {category ? (
            <Text style={[styles.categoryLabel, { color: c.muted }]}>{category}</Text>
          ) : null}
          {isLoading ? (
            <ActivityIndicator color={c.primary} style={styles.loading} />
          ) : isError ? (
            <EmptyState message={toUserMessage(error, "Couldn't load this explanation.")} />
          ) : !data ? null : (
            <View style={styles.body}>
              {/* This is what makes the Status column's badge (reconciliationBadge,
                  LedgerScreen.tsx) explainable rather than a static label: the reasoning was
                  already computed server-side, just never surfaced. Absent for the common case
                  (reconciliationStatus OK, nothing matched this row). */}
              {data.reconciliation ? (
                <View style={[styles.section, styles.sectionDivider, { borderBottomColor: c.border }]}>
                  {badge && badgeColors ? (
                    <Text style={[styles.badge, { backgroundColor: badgeColors.bg, color: badgeColors.fg }]}>
                      {badge.label}
                    </Text>
                  ) : null}
                  <Text style={[styles.summary, { color: c.ink }]}>{data.reconciliation.summary}</Text>
                  {data.reconciliation.evidence.map((line, i) => (
                    <Text key={i} style={[styles.evidence, { color: c.muted }]}>
                      • {line}
                    </Text>
                  ))}
                </View>
              ) : null}

              <View style={styles.section}>
                <Text style={[styles.summary, { color: c.ink }]}>{data.summary}</Text>
                {data.confidence != null ? (
                  <Text style={[styles.confidence, { color: c.muted }]}>{data.confidence}% confidence</Text>
                ) : null}
                {data.evidence.map((line, i) => (
                  <Text key={i} style={[styles.evidence, { color: c.muted }]}>
                    • {line}
                  </Text>
                ))}
              </View>
            </View>
          )}
          <Button label="Close" variant="link" onPress={onClose} />
        </Card>
      </View>
    </Modal>
  );
}

const styles = StyleSheet.create({
  backdrop: {
    flex: 1, backgroundColor: 'rgba(0,0,0,0.45)',
    alignItems: 'center', justifyContent: 'center', padding: spacing.md,
  },
  card: { width: '100%', maxWidth: 420, gap: spacing.sm },
  categoryLabel: { fontSize: 12, marginTop: -spacing.xs },
  loading: { marginVertical: spacing.md },
  body: { gap: spacing.md },
  section: { gap: 6 },
  sectionDivider: { paddingBottom: spacing.sm, borderBottomWidth: StyleSheet.hairlineWidth },
  badge: {
    alignSelf: 'flex-start', fontSize: 10, fontWeight: '700', textTransform: 'uppercase',
    paddingHorizontal: 8, paddingVertical: 3, borderRadius: 6, overflow: 'hidden',
  },
  summary: { fontSize: 14, lineHeight: 20 },
  confidence: { fontSize: 12 },
  evidence: { fontSize: 12, lineHeight: 17 },
});
