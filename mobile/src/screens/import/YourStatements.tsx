import { StyleSheet, Text, View } from 'react-native';
import { useQueries } from '@tanstack/react-query';
import { accountsApi } from '../../api/endpoints';
import { Card, SectionHeading } from '../../components/Card';
import { monthName, monthsLineText, qualifiesForMonths, statementMonths, type MonthChip } from '../../lib/statementMonths';
import { radius, spacing, typography, useTheme, type Palette } from '../../theme';
import type { Account } from '../../types';

/**
 * "Your statements" on the Import screen's add step (spec 2026-11-02, Step 1): for each account
 * imported from statements, which months Fynora has, which is missing, and which comes next.
 * Renders nothing when no account qualifies -- the screen shows its first-statement lead instead.
 */
export function YourStatements({ accounts, today }: { accounts: Account[]; today: string }) {
  const c = useTheme();
  // Newest import first; an account never imported sorts last.
  const shown = accounts
    .filter(qualifiesForMonths)
    .sort((a, b) => (b.lastImportedAt ?? '').localeCompare(a.lastImportedAt ?? ''));
  const coverage = useQueries({
    queries: shown.map((a) => ({
      queryKey: ['account-coverage', a.id],
      queryFn: () => accountsApi.coverage(a.id),
      retry: false,
    })),
  });

  if (shown.length === 0) return null;
  return (
    <Card testID="your-statements">
      <SectionHeading title="Your statements" />
      {shown.map((a, i) => {
        const q = coverage[i];
        const months = q.data ? statementMonths(q.data, today) : null;
        return (
          <View key={a.id} style={[styles.account, i > 0 && { borderTopColor: c.border, borderTopWidth: StyleSheet.hairlineWidth }]}>
            <View style={styles.nameRow}>
              <Text testID="statement-months-name" style={[typography.labelM, { color: c.ink }]}>{a.name}</Text>
              {a.accountNumberMasked ? (
                <Text style={[typography.bodyS, { color: c.mutedInk }]}>{a.accountNumberMasked}</Text>
              ) : null}
            </View>
            {q.isError ? (
              <Text style={[typography.bodyS, { color: c.mutedInk }]}>Couldn&apos;t load months</Text>
            ) : months && months.chips.length > 0 ? (
              <>
                <View style={styles.chips}>
                  {months.chips.map((chip) => <Chip key={chip.month} chip={chip} c={c} />)}
                </View>
                <Text style={[typography.bodyS, { color: months.line.kind === 'missing' ? c.warningInk : c.mutedInk }]}>
                  {monthsLineText(months.line)}
                </Text>
              </>
            ) : null}
          </View>
        );
      })}
    </Card>
  );
}

const CHIP_WORD: Record<MonthChip['state'], string> = { covered: 'in Fynora', missing: 'missing', next: 'next' };

function Chip({ chip, c }: { chip: MonthChip; c: Palette }) {
  const look = chip.state === 'covered'
    ? { backgroundColor: c.successBg, borderColor: c.successBg, color: c.successInk, borderStyle: 'solid' as const }
    : chip.state === 'missing'
      ? { backgroundColor: 'transparent', borderColor: c.warning, color: c.warningInk, borderStyle: 'dashed' as const }
      : { backgroundColor: 'transparent', borderColor: c.primary, color: c.ink, borderStyle: 'dashed' as const };
  return (
    <View
      accessible
      accessibilityLabel={`${monthName(chip.month, 'long')}, ${CHIP_WORD[chip.state]}`}
      style={[styles.chip, { backgroundColor: look.backgroundColor, borderColor: look.borderColor, borderStyle: look.borderStyle }]}
    >
      <Text style={[typography.labelS, { color: look.color }]}>{monthName(chip.month, 'short')}</Text>
      {chip.state !== 'covered' ? (
        <Text style={[typography.caption, { color: look.color }]}>{CHIP_WORD[chip.state]}</Text>
      ) : null}
    </View>
  );
}

const styles = StyleSheet.create({
  account: { paddingVertical: spacing.sm, gap: spacing.xs },
  nameRow: { flexDirection: 'row', alignItems: 'baseline', flexWrap: 'wrap', gap: spacing.xs },
  chips: { flexDirection: 'row', flexWrap: 'wrap', gap: spacing.xs },
  chip: { borderWidth: 1, borderRadius: radius.md, paddingVertical: 4, paddingHorizontal: spacing.sm, alignItems: 'center' },
});
