import { Pressable, StyleSheet, Text, View } from 'react-native';
import Ionicons from '@expo/vector-icons/Ionicons';
import type { PersonBalance } from '../../api/endpoints';
import { Card, SectionHeading } from '../Card';
import { fmtCurrency } from '../../lib/format';
import { spacing, typography, useTheme } from '../../theme';

interface Props {
  /** Already split and sorted by lib/insightsFeed withPeople: family is never here. */
  owedToYou: PersonBalance[];
  youOwe: PersonBalance[];
  onOpenPerson: (person: PersonBalance) => void;
}

/**
 * "With people" (spec §7): one balance per person, both directions, a tap for that person's page.
 * Answers "Who owes me?". Absent when nobody owes or is owed.
 */
export function WithPeopleSection({ owedToYou, youOwe, onOpenPerson }: Props) {
  const c = useTheme();
  if (owedToYou.length === 0 && youOwe.length === 0) return null;

  function side(title: string, people: PersonBalance[], theyOwe: boolean, first: boolean) {
    if (people.length === 0) return null;
    return (
      <View style={!first && [styles.side, { borderTopColor: c.border }]}>
        <Text style={[typography.labelS, styles.sideTitle, { color: c.mutedInk }]}>{title}</Text>
        {people.map((p) => {
          const amount = fmtCurrency(Math.abs(p.owedToUser));
          return (
            <Pressable
              key={p.personKey}
              onPress={() => onOpenPerson(p)}
              accessibilityRole="button"
              accessibilityLabel={theyOwe ? `${p.label} owes you ${amount}` : `You owe ${p.label} ${amount}`}
              style={styles.row}
            >
              <Text style={[typography.labelM, styles.name, { color: c.ink }]}>{p.label}</Text>
              <Text style={[typography.numberS, { color: theyOwe ? c.successInk : c.dangerInk }]}>{amount}</Text>
              <Ionicons name="chevron-forward" size={16} color={c.mutedInk} />
            </Pressable>
          );
        })}
      </View>
    );
  }

  return (
    <View>
      <SectionHeading title="With people" />
      <Card>
        {side('Owed to you', owedToYou, true, true)}
        {side('You owe', youOwe, false, owedToYou.length === 0)}
      </Card>
    </View>
  );
}

const styles = StyleSheet.create({
  side: { borderTopWidth: StyleSheet.hairlineWidth, marginTop: spacing.xs },
  sideTitle: { paddingTop: spacing.sm },
  // 44pt minimum touch target (Apple HIG).
  row: { flexDirection: 'row', alignItems: 'center', gap: spacing.sm, minHeight: 44 },
  name: { flex: 1 },
});
