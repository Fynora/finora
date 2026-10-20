import { useEffect, useMemo, useRef, useState } from 'react';
import { FlatList, KeyboardAvoidingView, Pressable, StyleSheet, Text, TextInput, View } from 'react-native';
import { useSafeAreaInsets } from 'react-native-safe-area-context';
import { useQuery, useQueryClient } from '@tanstack/react-query';
import {
  insightsApi, peopleApi, transactionsApi, type BillOption, type BillShare, type PeopleSuggestion, type PeopleSuggestions,
} from '../../api/endpoints';
import { AppModal } from '../AppModal';
import { Button } from '../Button';
import { GlassSurface } from '../GlassSurface';
import { PeopleLookAtRow } from '../insights/PeopleLookAtRow';
import { toUserMessage } from '../../lib/apiError';
import { fmtCurrency, fromLocalDateString, toLocalDateString, withSep } from '../../lib/format';
import { hapticError, hapticSelectionIfChanged, hapticSuccess } from '../../lib/haptics';
import { invalidateFinancialData } from '../../lib/invalidateFinancialData';
import { rowTitle } from '../../lib/ledgerRows';
import { radius, spacing, typography, useTheme } from '../../theme';
import type { Transaction } from '../../types';

/** The picker's window: two weeks before the payment, then two weeks after (spec §6 A). */
const WINDOW_DAYS = 14;

interface Payment {
  txnId: string;
  label: string;
  amount: number;
  date: string;
}

type Props =
  /** A credit: which bill is it a share of? `options` are the server's, best first; null: look them up. */
  | { mode: 'bill'; credit: Payment; options: BillOption[] | null; onClose: () => void }
  /** A bill ("Split this"): whose money was a share of it? */
  | { mode: 'credit'; bill: Payment & { category: string | null }; onClose: () => void };

interface Option {
  id: string;
  category: string | null;
  payee: string;
  date: string;
  amount: number;
  /** What is left of the bill after its shares; for a credit, its amount. */
  remaining: number;
}

function dayMonth(date: string): string {
  return withSep(fromLocalDateString(date).toLocaleDateString('en-IN', { day: 'numeric', month: 'short' }));
}

function addDays(date: string, days: number): string {
  const d = fromLocalDateString(date);
  d.setDate(d.getDate() + days);
  return toLocalDateString(d);
}

function fromTransaction(t: Transaction): Option {
  return { id: t.id, category: t.categoryName || null, payee: rowTitle(t), date: t.date, amount: Math.abs(t.amount), remaining: Math.abs(t.amount) };
}

/** Before the anchor date first, nearest first; then after it, nearest first. */
function nearestFirst(options: Option[], anchor: string): Option[] {
  const distance = (o: Option) => Math.abs(fromLocalDateString(o.date).getTime() - fromLocalDateString(anchor).getTime());
  return [...options].sort((a, b) => {
    const aAfter = a.date > anchor ? 1 : 0;
    const bAfter = b.date > anchor ? 1 : 0;
    return aAfter - bAfter || distance(a) - distance(b);
  });
}

/**
 * Links a credit to a bill as someone's share of it (spec §7; screens 2 and 3 of the approved share
 * mockup). The first bill is chosen for the user, who still confirms: the app never links on its
 * own the first time. "Something else" searches every expense, for a share that came back outside
 * the two weeks (a deposit returned months later). Once linked, "every month?" is asked in place
 * when the server now suggests it for this credit.
 */
export function BillPickerSheet(props: Props) {
  const c = useTheme();
  const insets = useSafeAreaInsets();
  const queryClient = useQueryClient();
  const anchor = props.mode === 'bill' ? props.credit : props.bill;
  const [searching, setSearching] = useState(false);
  const [keyword, setKeyword] = useState('');
  const [searchFor, setSearchFor] = useState('');
  const [chosenId, setChosenId] = useState<string | null>(null);
  const [linking, setLinking] = useState(false);
  const inFlight = useRef(false);
  const [failed, setFailed] = useState<string | null>(null);
  const [linked, setLinked] = useState<{ share: BillShare; option: Option } | null>(null);
  const [everyMonth, setEveryMonth] = useState<PeopleSuggestion | null>(null);

  // The search waits for a pause in typing, so each letter is not a request.
  useEffect(() => {
    const timer = setTimeout(() => setSearchFor(keyword.trim()), 300);
    return () => clearTimeout(timer);
  }, [keyword]);

  const given = props.mode === 'bill' ? props.options : null;
  const windowQ = useQuery({
    queryKey: ['people', 'pick', props.mode, anchor.txnId],
    queryFn: () => transactionsApi.search({
      type: props.mode === 'bill' ? 'EXPENSE' : 'INCOME',
      dateFrom: addDays(anchor.date, -WINDOW_DAYS),
      dateTo: addDays(anchor.date, WINDOW_DAYS),
      size: 50,
    }),
    enabled: !searching && given === null,
  });
  const searchQ = useQuery({
    queryKey: ['people', 'pick', 'search', searchFor],
    queryFn: () => transactionsApi.search({ type: 'EXPENSE', keyword: searchFor || undefined, size: 30, sortField: 'date', sortDir: 'desc' }),
    enabled: searching,
  });

  const options: Option[] = useMemo(() => {
    if (searching) return (searchQ.data?.content ?? []).filter((t) => t.id !== anchor.txnId).map(fromTransaction);
    if (given) {
      return given.map((b) => ({ id: b.billId, category: b.category, payee: b.payeeLabel, date: b.date, amount: b.amount, remaining: b.remaining }));
    }
    const rows = (windowQ.data?.content ?? [])
      .filter((t) => t.id !== anchor.txnId)
      // A share comes from a person; a bill can be paid to anyone.
      .filter((t) => props.mode === 'bill' || t.counterpartyType === 'PERSON');
    return nearestFirst(rows.map(fromTransaction), anchor.date);
  }, [searching, searchQ.data, given, windowQ.data, anchor.txnId, anchor.date, props.mode]);

  const chosen = options.find((o) => o.id === chosenId) ?? options[0] ?? null;
  const loading = searching ? searchQ.isLoading : given === null && windowQ.isLoading;

  async function linkChosen() {
    if (!chosen || inFlight.current) return;
    inFlight.current = true;
    setLinking(true);
    setFailed(null);
    const creditId = props.mode === 'bill' ? props.credit.txnId : chosen.id;
    const billId = props.mode === 'bill' ? chosen.id : props.bill.txnId;
    try {
      const share = await peopleApi.link(creditId, billId);
      hapticSuccess();
      invalidateFinancialData(queryClient);
      setLinked({ share, option: chosen });
      // Screen 3: the same sender in other months? The server says, once the link is in.
      const next = await queryClient.fetchQuery<PeopleSuggestions>({
        queryKey: ['people', 'suggestions'], queryFn: () => peopleApi.suggestions(), staleTime: 0,
      }).catch(() => null);
      setEveryMonth(next?.items.find((s) => s.kind === 'EVERY_MONTH' && s.txnId === creditId) ?? null);
    } catch (err) {
      hapticError();
      setFailed(toUserMessage(err, "Couldn't link it. Try again."));
    } finally {
      inFlight.current = false;
      setLinking(false);
    }
  }

  function linkLabel(o: Option): string {
    return props.mode === 'bill'
      ? `Link to ${dayMonth(o.date)} ${o.category ?? o.payee}`
      : `Link ${dayMonth(o.date)} ${fmtCurrency(o.amount)}`;
  }

  function doneText(): string {
    if (!linked) return '';
    const bill = props.mode === 'bill'
      ? { date: linked.option.date, name: linked.option.category ?? linked.option.payee, remaining: linked.option.remaining }
      : { date: props.bill.date, name: props.bill.category ?? props.bill.label, remaining: props.bill.amount };
    // What the server's own figure will be: what was left of the bill, less this share.
    const yours = Math.max(0, bill.remaining - linked.share.amount);
    return `Your share of the ${dayMonth(bill.date)} ${bill.name} is now ${fmtCurrency(yours)}.`;
  }

  const title = props.mode === 'bill' ? 'Share of which bill?' : 'Who paid a share?';
  const subtitle = props.mode === 'bill'
    ? `${fmtCurrency(props.credit.amount)} from ${props.credit.label} · ${dayMonth(props.credit.date)}`
    : `${fmtCurrency(props.bill.amount)} to ${props.bill.label} · ${dayMonth(props.bill.date)}`;

  return (
    <AppModal visible animationType="slide" transparent onRequestClose={linking ? () => {} : props.onClose}>
      <KeyboardAvoidingView style={styles.flex} behavior="padding">
        <Pressable
          style={styles.backdrop}
          onPress={linking ? undefined : props.onClose}
          accessible={false}
          accessibilityElementsHidden
          importantForAccessibility="no-hide-descendants"
        />
        <GlassSurface style={[styles.sheet, { paddingBottom: insets.bottom + spacing.md }]} accessibilityViewIsModal>
          {linked ? (
            <View style={styles.done}>
              <Text style={[typography.cardTitle, { color: c.ink }]} accessibilityRole="header">Linked.</Text>
              <Text style={[typography.bodyM, { color: c.ink }]}>{doneText()}</Text>
              {everyMonth ? (
                <PeopleLookAtRow
                  item={everyMonth}
                  today={toLocalDateString(new Date())}
                  paidBackKindId={null}
                  onAck={() => {
                    // Insights asks the same question: once the server has the answer, it goes there too.
                    void insightsApi.acknowledge(everyMonth.kind, everyMonth.ackKey)
                      .then(() => {
                        void queryClient.invalidateQueries({ queryKey: ['insight-acknowledgements'] });
                        void queryClient.invalidateQueries({ queryKey: ['people', 'suggestions'] });
                      })
                      .catch(() => {});
                    props.onClose();
                  }}
                  onAnswered={props.onClose}
                  // Not offered on an every-month row; there only to satisfy the row's other kinds.
                  onPickBill={() => {}}
                  onAsk={() => {}}
                  onOpenPayment={() => {}}
                />
              ) : (
                <Button label="Done" onPress={props.onClose} />
              )}
            </View>
          ) : (
            <>
              <Text style={[typography.cardTitle, { color: c.ink }]} accessibilityRole="header">{title}</Text>
              <Text style={[typography.bodyS, styles.subtitle, { color: c.mutedInk }]}>{subtitle}</Text>
              {searching ? (
                <TextInput
                  value={keyword}
                  onChangeText={setKeyword}
                  placeholder="Search your payments"
                  placeholderTextColor={c.mutedInk}
                  accessibilityLabel="Search your payments"
                  autoFocus
                  style={[styles.search, { backgroundColor: c.inputBg, borderColor: c.border, color: c.ink }]}
                />
              ) : null}
              <FlatList
                data={options}
                keyExtractor={(o) => o.id}
                style={styles.list}
                ListEmptyComponent={
                  <Text style={[typography.bodyM, styles.empty, { color: c.mutedInk }]}>
                    {loading ? 'Looking…' : props.mode === 'bill' ? 'No payments here.' : 'No money from people in these two weeks.'}
                  </Text>
                }
                renderItem={({ item: o }) => {
                  const selected = chosen?.id === o.id;
                  return (
                    <Pressable
                      onPress={() => { hapticSelectionIfChanged(chosen?.id ?? null, o.id); setChosenId(o.id); }}
                      accessibilityRole="button"
                      accessibilityLabel={`${o.category ?? 'No category'}, ${dayMonth(o.date)}, ${o.payee}, ${fmtCurrency(o.amount)}`}
                      accessibilityState={{ selected }}
                      style={[styles.option, { borderColor: selected ? c.primary : c.border }]}
                    >
                      <View style={styles.optionText}>
                        <Text style={[typography.labelM, { color: c.ink }]}>{`${o.category ?? 'No category'} · ${dayMonth(o.date)}`}</Text>
                        <Text style={[typography.bodyS, { color: c.mutedInk }]} numberOfLines={1}>{o.payee}</Text>
                      </View>
                      <Text style={[typography.numberS, { color: props.mode === 'bill' ? c.dangerInk : c.successInk }]}>
                        {`${props.mode === 'bill' ? '−' : '+'}${fmtCurrency(o.amount)}`}
                      </Text>
                    </Pressable>
                  );
                }}
              />
              {props.mode === 'bill' && !searching ? (
                <Text style={[typography.bodyS, styles.note, { color: c.mutedInk }]}>
                  {'Bills in the 2 weeks around it, nearest first. "Something else" searches all your payments.'}
                </Text>
              ) : null}
              {failed ? (
                <Text accessibilityRole="alert" style={[typography.bodyS, styles.note, { color: c.dangerInk }]}>
                  {failed}
                </Text>
              ) : null}
              <View style={styles.actions}>
                {chosen ? <Button label={linkLabel(chosen)} onPress={() => void linkChosen()} loading={linking} /> : null}
                {props.mode === 'bill' && !searching ? (
                  <Button label="Something else" variant="link" onPress={() => setSearching(true)} disabled={linking} />
                ) : null}
                <Button label="Cancel" variant="link" onPress={props.onClose} disabled={linking} />
              </View>
            </>
          )}
        </GlassSurface>
      </KeyboardAvoidingView>
    </AppModal>
  );
}

const styles = StyleSheet.create({
  flex: { flex: 1 },
  backdrop: { flex: 1, backgroundColor: 'rgba(0,0,0,0.35)' },
  sheet: {
    maxHeight: '85%',
    borderTopLeftRadius: radius.xl,
    borderTopRightRadius: radius.xl,
    paddingHorizontal: spacing.md,
    paddingTop: spacing.md,
  },
  subtitle: { marginTop: 2, marginBottom: spacing.sm },
  search: { minHeight: 44, borderWidth: 1, borderRadius: radius.md, paddingHorizontal: spacing.sm, marginBottom: spacing.sm },
  // flexShrink:1 so a long list scrolls inside the sheet's cap (see OptionPickerModal).
  list: { flexGrow: 0, flexShrink: 1 },
  option: {
    flexDirection: 'row', alignItems: 'center', gap: spacing.sm, minHeight: 56, paddingHorizontal: spacing.sm,
    borderWidth: 1, borderRadius: radius.md, marginBottom: spacing.xs,
  },
  optionText: { flex: 1 },
  empty: { paddingVertical: spacing.md },
  note: { marginTop: spacing.xs },
  actions: { marginTop: spacing.sm, gap: spacing.xs },
  done: { gap: spacing.sm, paddingBottom: spacing.sm },
});
