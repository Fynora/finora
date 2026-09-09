import { useState, type ReactNode } from 'react';
import { Pressable, StyleSheet, Text, View } from 'react-native';
import Ionicons from '@expo/vector-icons/Ionicons';
import type { BalanceChainDetails, VerificationFinding, VerificationReport } from '../types';
import { Card } from './Card';
import { spacing, useTheme, type Palette } from '../theme';

/**
 * Phase 5 (Low-Priority Polish). Mobile counterpart to frontend/src/components/VerificationPanel.tsx
 * -- see that file's own doc comment for why this exists: "127 transactions parsed" says the
 * parser produced a result, not that the result is right, and this is where a comparison against
 * the statement's own arithmetic becomes visible rather than silently assumed.
 *
 * A reduced port, not a full one. Web renders per-rule discrepancy/comparison TABLES (row-by-row
 * balance mismatches, printed-vs-parsed totals) inside each expanded finding; this shows the same
 * curated summary SENTENCE each rule already computes, but not the table beneath it. The trust
 * signal this exists for -- "was this checked, and does it look right" -- is carried entirely by
 * the collapsed header line (reliabilityStatus) and each finding's outcome/summary; the tables are
 * genuinely additional depth for someone auditing a discrepancy row-by-row; that level of detail
 * doesn't fit a phone screen well and isn't what a first mobile cut of this needs. Revisit if a
 * real user asks to see the itemized rows.
 */
export function VerificationPanel({ verification }: { verification: VerificationReport | null | undefined }) {
  const c = useTheme();
  const [expanded, setExpanded] = useState(false);

  // Null means verification never ran -- an older import, or a path that does not check. Saying
  // nothing is honest; a reassuring tick would claim a check that never happened.
  if (!verification) return null;

  const findings = verification.findings ?? [];
  if (findings.length === 0) return null;

  const notable = findings.filter((f) => f.outcome === 'WARNING' || f.outcome === 'FAILED');
  // Legacy fallback only, same reasoning as web's identical fallback -- used when the server never
  // computed reliabilityStatus (a report from before this field existed).
  const allClear = notable.length === 0 && findings.some((f) => f.outcome === 'VERIFIED');

  const verdict = verification.reliabilityStatus === 'CLEAN'
    ? { icon: 'checkmark-circle' as const, color: c.success, text: 'Imported successfully' }
    : verification.reliabilityStatus === 'NEEDS_ATTENTION'
      ? { icon: 'alert-circle' as const, color: c.danger, text: 'Import needs attention' }
      : verification.reliabilityStatus === 'REVIEW_RECOMMENDED'
        // Deliberately not "review recommended" -- reads as something went wrong. This status
        // fires on OCR alone as often as on an actual finding, and most OCR reads are fine.
        ? { icon: 'alert-circle' as const, color: c.warning, text: 'Imported with notes' }
        : allClear
          ? { icon: 'checkmark-circle' as const, color: c.success, text: 'Running balance verified' }
          : notable.length > 0
            ? { icon: 'alert-circle' as const, color: c.warning, text: `${notable.length} ${notable.length === 1 ? 'finding' : 'findings'}` }
            : { icon: 'help-circle-outline' as const, color: c.muted, text: "Couldn't be checked" };

  return (
    <Card style={styles.card}>
      <Pressable
        onPress={() => setExpanded((open) => !open)}
        accessibilityRole="button"
        accessibilityState={{ expanded }}
        style={styles.header}
      >
        <Ionicons name={expanded ? 'chevron-down' : 'chevron-forward'} size={15} color={c.muted} />
        <Text style={[styles.title, { color: c.ink }]}>Statement verification</Text>
        <View style={styles.verdict}>
          <Ionicons name={verdict.icon} size={14} color={verdict.color} />
          <Text style={[styles.verdictText, { color: verdict.color }]}>{verdict.text}</Text>
        </View>
      </Pressable>

      {expanded ? (
        <View style={[styles.body, { borderTopColor: c.border }]}>
          {/* The one fact that explains a REVIEW_RECOMMENDED status when every finding below is
              otherwise clean -- OCR provenance lives on the report itself, not as a finding, so
              without this line an OCR-only "review recommended" verdict has nothing visible to
              point at. */}
          {verification.textSource === 'OCR' || verification.textSource === 'NATIVE_PLUS_OCR' ? (
            <Text style={[styles.ocrNote, { color: c.muted }]}>
              This statement was read using OCR (scanned-image recognition), not the document's
              own text. Recognition can misread characters in ways a text-based read cannot.
            </Text>
          ) : null}
          {findings.map((finding, i) => <Finding key={`${finding.rule}-${i}`} finding={finding} c={c} />)}
        </View>
      ) : null}
    </Card>
  );
}

const OUTCOME_LABEL: Record<VerificationFinding['outcome'], string> = {
  VERIFIED: 'verified',
  WARNING: 'needs review',
  FAILED: 'did not reconcile',
  NOT_APPLICABLE: 'not applicable',
};

function outcomeColor(outcome: VerificationFinding['outcome'], c: Palette): string {
  if (outcome === 'VERIFIED') return c.success;
  if (outcome === 'WARNING') return c.warning;
  if (outcome === 'FAILED') return c.danger;
  return c.muted;
}

/**
 * Renders one finding by looking its rule up in a registry, rather than branching on the rule
 * name at each site that displays one -- same reasoning as the web version: a new backend
 * validator needs one entry here and nothing else.
 */
function Finding({ finding, c }: { finding: VerificationFinding; c: Palette }) {
  const renderer = RULE_RENDERERS[finding.rule];
  return (
    <View style={styles.finding}>
      <Text style={[styles.findingLabel, { color: c.ink }]}>
        {renderer?.label ?? finding.rule}
        <Text style={{ color: outcomeColor(finding.outcome, c), fontWeight: '400' }}>
          {' · '}{OUTCOME_LABEL[finding.outcome]}
        </Text>
      </Text>
      {renderer ? (
        renderer.summary(finding, c)
      ) : (
        // A rule this build has no renderer for -- a newer backend, most likely. Naming it beats
        // hiding it or dumping raw JSON at the user.
        <Text style={[styles.findingBody, { color: c.muted }]}>
          This check reported an outcome this version of the app doesn't know how to display yet.
        </Text>
      )}
    </View>
  );
}

/** One entry per backend rule -- additive, same convention as web's RULE_RENDERERS. Each returns
 *  the curated summary SENTENCE only; see this file's own doc comment for why the itemized
 *  discrepancy/comparison tables web also renders are out of scope for a first mobile cut. */
const RULE_RENDERERS: Record<string, { label: string; summary: (f: VerificationFinding, c: Palette) => ReactNode }> = {
  BALANCE_CHAIN: {
    label: 'Running balance',
    summary: (finding, c) => {
      const d = finding.details as unknown as BalanceChainDetails;
      const discrepancyCount = d?.discrepancies?.length ?? 0;
      return (
        <Text style={[styles.findingBody, { color: c.muted }]}>
          {d?.rowsChecked ?? 0} transaction(s) checked against the statement's own running
          balance.{' '}
          {d?.anchoredOnOpeningBalance
            ? 'Checked from the opening balance, so the first transaction is covered too.'
            : 'The statement gave no opening balance, so the first transaction could not be checked.'}
          {discrepancyCount > 0
            ? ` ${discrepancyCount} ${discrepancyCount === 1 ? 'row' : 'rows'} didn't match.`
            : ''}
        </Text>
      );
    },
  },
  STATEMENT_TOTALS: {
    label: 'Statement totals',
    summary: (finding, c) => {
      const d = finding.details as { reason?: string; explanation?: string };
      return (
        <Text style={[styles.findingBody, { color: c.muted }]}>
          {d?.reason ?? d?.explanation ?? "Compared the statement's opening balance, credits and debits against its own stated closing balance."}
        </Text>
      );
    },
  },
  SUMMARY_TOTALS: {
    label: "The bank's own totals",
    summary: (finding, c) => {
      const d = finding.details as { reason?: string; explanation?: string };
      return (
        <Text style={[styles.findingBody, { color: c.muted }]}>
          {d?.reason ?? d?.explanation ?? "Compared against the totals printed on the statement itself."}
        </Text>
      );
    },
  },
  COLUMN_AMBIGUITY: {
    label: 'Rows that could be read two ways',
    summary: (finding, c) => {
      const d = finding.details as { reason?: string; explanation?: string; ambiguousRows?: number };
      const count = d?.ambiguousRows ?? 0;
      return (
        <Text style={[styles.findingBody, { color: c.muted }]}>
          {d?.reason ?? d?.explanation
            ?? (count > 0
              ? `${count} row(s) had an amount or direction the document didn't state outright.`
              : "Every transaction's amount and direction was stated by the document, not assumed.")}
        </Text>
      );
    },
  },
};

const styles = StyleSheet.create({
  card: { gap: spacing.xs },
  header: { flexDirection: 'row', alignItems: 'center', gap: spacing.xs },
  title: { fontSize: 14, fontWeight: '600', flex: 1 },
  verdict: { flexDirection: 'row', alignItems: 'center', gap: 4 },
  verdictText: { fontSize: 12, fontWeight: '600' },
  body: { gap: spacing.sm, marginTop: spacing.xs, paddingTop: spacing.sm, borderTopWidth: StyleSheet.hairlineWidth },
  ocrNote: { fontSize: 12, lineHeight: 17 },
  finding: { gap: 2 },
  findingLabel: { fontSize: 13, fontWeight: '600' },
  findingBody: { fontSize: 12, lineHeight: 17 },
});
