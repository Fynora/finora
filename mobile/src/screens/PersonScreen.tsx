import { useRef, useState } from 'react';
import { Pressable, ScrollView, StyleSheet, Text, View } from 'react-native';
import type { RouteProp } from '@react-navigation/native';
import { useQuery, useQueryClient } from '@tanstack/react-query';
import { peopleApi, type PersonTimelineItem } from '../api/endpoints';
import { AppRefreshControl } from '../components/AppRefreshControl';
import { AppSwitch } from '../components/AppSwitch';
import { Card, SectionHeading } from '../components/Card';
import { GlassScreen } from '../components/GlassScreen';
import { SkeletonCard } from '../components/skeletons/Skeletons';
import { toUserMessage } from '../lib/apiError';
import { withBypass } from '../lib/changeSync';
import { fmtCurrency, fromLocalDateString, withSep } from '../lib/format';
import { hapticError, hapticSuccess } from '../lib/haptics';
import { invalidateFinancialData } from '../lib/invalidateFinancialData';
import { usePreventScreenCapture } from '../lib/screenCapture';
import type { MoreStackParamList } from '../navigation/types';
import { padForTabBar, useTabBarSpace } from '../navigation/useTabBarSpace';
import { spacing, typography, useTheme } from '../theme';

function dayMonth(date: string): string {
  return withSep(fromLocalDateString(date).toLocaleDateString('en-IN', { day: 'numeric', month: 'short' }));
}

/** One line of the history, in the words of the approved loan mockup. */
function describe(item: PersonTimelineItem): string {
  const amount = fmtCurrency(item.amount);
  switch (item.kind) {
    case 'LENT': return `You lent ${amount}`;
    case 'PAID_BACK': return `Paid back ${amount}`;
    case 'BORROWED': return `You borrowed ${amount}`;
    case 'REPAID': return `You paid back ${amount}`;
    case 'CASH_SETTLED': return `Settled in cash ${amount}`;
    case 'LET_GO': return `Let go ${amount}`;
    case 'SHARE': return `Their share of a bill ${amount}`;
  }
}

/**
 * One person (spec §7, "Person page"): the balance both ways, the history of lent, back and shares,
 * the every-month rules for them (removed here), and whether they are family -- a family person is
 * never asked "lent?" or "settle it?".
 */
export function PersonScreen({ route }: { route: RouteProp<MoreStackParamList, 'Person'> }) {
  // Names and amounts of a real person's money: the same exposure the other money screens guard.
  usePreventScreenCapture();
  const c = useTheme();
  // The floating tab pill sits over this screen: room under the list, so the Family switch shows.
  const tabBar = useTabBarSpace();
  const queryClient = useQueryClient();
  const { personKey } = route.params;
  const personQ = useQuery({ queryKey: ['people', 'person', personKey], queryFn: () => peopleApi.person(personKey) });
  const [failed, setFailed] = useState<string | null>(null);
  const inFlight = useRef(false);

  async function save(write: () => Promise<unknown>) {
    if (inFlight.current) return;
    inFlight.current = true;
    setFailed(null);
    try {
      await write();
      hapticSuccess();
      invalidateFinancialData(queryClient);
    } catch (err) {
      hapticError();
      setFailed(toUserMessage(err, "Couldn't save that. Try again."));
    } finally {
      inFlight.current = false;
    }
  }

  function refresh() {
    // A pull is a read, not an edit: it must not wait for the change stamp (lib/changeSync.ts).
    withBypass(() => void queryClient.invalidateQueries({ queryKey: ['people', 'person', personKey] }));
  }

  const p = personQ.data;
  const balance = !p ? '' : p.owedToUser > 0 ? `Owes you ${fmtCurrency(p.owedToUser)}`
    : p.owedToUser < 0 ? `You owe ${fmtCurrency(-p.owedToUser)}` : 'All settled';

  return (
    <GlassScreen style={styles.root}>
      <ScrollView
        contentContainerStyle={padForTabBar(styles.content, tabBar)}
        scrollIndicatorInsets={{ bottom: tabBar }}
        refreshControl={<AppRefreshControl refreshing={personQ.isRefetching} onRefresh={refresh} tintColor={c.primary} />}
      >
        {personQ.isLoading ? (
          <SkeletonCard lines={4} />
        ) : !p ? (
          <Card>
            <Text style={[typography.bodyM, { color: c.mutedInk }]}>{"Couldn't load this. Pull down to try again."}</Text>
          </Card>
        ) : (
          <>
            <Text style={[typography.screenTitle, { color: c.ink }]} accessibilityRole="header">{p.label}</Text>
            <Text
              style={[typography.numberM, styles.balance, { color: p.owedToUser > 0 ? c.successInk : p.owedToUser < 0 ? c.dangerInk : c.ink }]}
            >
              {balance}
            </Text>
            {p.owedToUser !== 0 && p.since ? (
              <Text style={[typography.bodyS, { color: c.mutedInk }]}>{`Open since ${dayMonth(p.since)}`}</Text>
            ) : null}

            {p.timeline.length > 0 ? (
              <View style={styles.section}>
                <SectionHeading title="History" />
                <Card>
                  {p.timeline.map((item, i) => (
                    <View
                      key={`${item.date}-${item.kind}-${item.txnId ?? i}`}
                      style={[styles.row, i > 0 && { borderTopWidth: StyleSheet.hairlineWidth, borderTopColor: c.border }]}
                    >
                      <Text style={[typography.bodyS, styles.date, { color: c.mutedInk }]}>{dayMonth(item.date)}</Text>
                      <Text style={[typography.bodyM, styles.grow, { color: c.ink }]}>{describe(item)}</Text>
                    </View>
                  ))}
                </Card>
              </View>
            ) : null}

            {p.rules.length > 0 ? (
              <View style={styles.section}>
                <SectionHeading title="Every month" />
                <Card>
                  {p.rules.map((r, i) => {
                    const name = r.billCategory ?? 'bill';
                    return (
                      <View
                        key={r.id}
                        style={[styles.row, i > 0 && { borderTopWidth: StyleSheet.hairlineWidth, borderTopColor: c.border }]}
                      >
                        <View style={styles.grow}>
                          <Text style={[typography.labelM, { color: c.ink }]}>{`${name} shares, every month`}</Text>
                          {r.billMerchant ? <Text style={[typography.bodyS, { color: c.mutedInk }]}>{r.billMerchant}</Text> : null}
                        </View>
                        <Pressable
                          onPress={() => void save(() => peopleApi.removeSplitRule(r.id))}
                          accessibilityRole="button"
                          accessibilityLabel={`Remove the ${name} rule`}
                          hitSlop={8}
                          style={styles.remove}
                        >
                          <Text style={[typography.labelS, { color: c.dangerInk }]}>Remove</Text>
                        </Pressable>
                      </View>
                    );
                  })}
                </Card>
              </View>
            ) : null}

            <View style={styles.section}>
              <Card>
                <View style={styles.row}>
                  <View style={styles.grow}>
                    <Text style={[typography.labelM, { color: c.ink }]}>Family</Text>
                    <Text style={[typography.bodyS, { color: c.mutedInk }]}>
                      Money with family is never asked about as a loan.
                    </Text>
                  </View>
                  <AppSwitch
                    value={p.family}
                    onValueChange={(family) => void save(() => peopleApi.setFamily(personKey, family))}
                    accessibilityLabel="Family"
                    trackColor={{ true: c.primary, false: c.border }}
                    thumbColor={p.family ? c.onPrimary : undefined}
                  />
                </View>
              </Card>
            </View>
            {failed ? (
              <Text accessibilityRole="alert" style={[typography.bodyS, { color: c.dangerInk }]}>{failed}</Text>
            ) : null}
          </>
        )}
      </ScrollView>
    </GlassScreen>
  );
}

const styles = StyleSheet.create({
  root: { flex: 1 },
  content: { padding: spacing.md, paddingBottom: spacing.xl },
  balance: { marginTop: spacing.xs },
  section: { marginTop: spacing.md },
  // 44pt minimum touch target (Apple HIG).
  row: { flexDirection: 'row', alignItems: 'center', gap: spacing.sm, minHeight: 44, paddingVertical: spacing.xs },
  date: { width: 52 },
  grow: { flex: 1 },
  remove: { minWidth: 44, minHeight: 44, alignItems: 'center', justifyContent: 'center' },
});
