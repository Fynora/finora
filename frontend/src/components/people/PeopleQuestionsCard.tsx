import { useMemo, useState } from 'react';
import { useNavigate } from 'react-router-dom';
import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query';
import { inflowApi, insightsApi, peopleApi, type InsightAcknowledgement, type PeopleSuggestion } from '../../api/endpoints';
import { FinoraCard, SectionHeader } from '../../design-system';
import { trackNavigation } from '../../lib/trackNavigation';
import { toLocalDateString } from '../../utils/date';
import { useAskForMoney } from './AskForMoney';
import { BillPickerModal } from './BillPickerModal';
import { PeopleQuestion } from './PeopleQuestion';

interface Props {
  /** After an answer is saved: the page re-reads the figures it keeps itself. */
  onChanged: () => void;
}

/**
 * Insights' questions about money with people (spec §6 A-G), the web form of the app's "To look at"
 * rows. The server picks and orders them; "Got it" hides one at once and comes back if the server
 * refuses. Shows nothing with no questions, or on a server without them.
 */
export function PeopleQuestionsCard({ onChanged }: Props) {
  const queryClient = useQueryClient();
  const navigate = useNavigate();
  const suggestionsQ = useQuery({ queryKey: ['people', 'suggestions'], queryFn: () => peopleApi.suggestions(), retry: false });
  const acksQ = useQuery({ queryKey: ['insight-acknowledgements'], queryFn: () => insightsApi.acknowledgements(), retry: false });
  // The built-in Paid back kind, which "Always do this for <person>" remembers the sender with.
  const kindsQ = useQuery({ queryKey: ['inflow-kinds'], queryFn: () => inflowApi.kinds(), retry: false });
  const [picking, setPicking] = useState<PeopleSuggestion | null>(null);
  const { ask, prompt } = useAskForMoney();

  const acknowledge = useMutation({
    mutationFn: ({ kind, itemKey }: InsightAcknowledgement) => insightsApi.acknowledge(kind, itemKey),
    onMutate: async (ack: InsightAcknowledgement) => {
      await queryClient.cancelQueries({ queryKey: ['insight-acknowledgements'] });
      const previous = queryClient.getQueryData<InsightAcknowledgement[]>(['insight-acknowledgements']);
      queryClient.setQueryData<InsightAcknowledgement[]>(['insight-acknowledgements'], (list) => [...(list ?? []), ack]);
      return { previous };
    },
    onError: (_err, _ack, context) => queryClient.setQueryData(['insight-acknowledgements'], context?.previous),
  });

  const rows = useMemo(() => {
    const acked = new Set((acksQ.data ?? []).map((a) => `${a.kind}|${a.itemKey}`));
    return (suggestionsQ.data?.items ?? []).filter((s) => !acked.has(`${s.kind}|${s.ackKey}`));
  }, [suggestionsQ.data, acksQ.data]);
  const paidBackKindId = (kindsQ.data ?? []).find((k) => k.builtIn === 'PAID_BACK')?.id ?? null;
  const today = toLocalDateString(new Date());

  if (rows.length === 0 && !picking) return <>{prompt}</>;

  return (
    <>
      {rows.length > 0 && (
        <FinoraCard>
          <SectionHeader title="Money with people" />
          <div>
            {rows.map((item) => (
              <PeopleQuestion
                key={`${item.kind}-${item.ackKey}`}
                item={item}
                today={today}
                paidBackKindId={paidBackKindId}
                onAck={() => acknowledge.mutate({ kind: item.kind, itemKey: item.ackKey })}
                onPickBill={setPicking}
                onAsk={(s) => ask({ label: s.label, owed: s.owed ?? s.amount })}
                // "Paid for something": that day's transactions, to give this one a category. By date
                // alone: the person's name is the server's label, not words the payment's text is known to have.
                onOpenPayment={(s) => {
                  if (!s.date) return;
                  trackNavigation('transactions', 'contextual');
                  void navigate(`/app/transactions?from=${s.date}&to=${s.date}`);
                }}
                onAnswered={onChanged}
              />
            ))}
          </div>
          {prompt}
        </FinoraCard>
      )}
      {picking && picking.txnId && (
        <BillPickerModal
          mode="bill"
          credit={{ txnId: picking.txnId, label: picking.label, amount: picking.amount, date: picking.date ?? today }}
          options={picking.bills}
          onClose={() => { setPicking(null); onChanged(); }}
        />
      )}
    </>
  );
}
