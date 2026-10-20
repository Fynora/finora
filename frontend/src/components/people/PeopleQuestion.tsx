import { useRef, useState } from 'react';
import { ArrowDownLeft, ArrowUpRight, CheckCheck, Hourglass, Repeat, type LucideIcon } from 'lucide-react';
import { inflowApi, peopleApi, transactionsApi, type PeopleSuggestion } from '../../api/endpoints';
import { Button } from '../../design-system';
import { useInvalidateMoneyFigures } from '../../lib/invalidateMoneyFigures';
import { dayMonth, daysBetween, rupees, serverMessage } from '../../lib/people';

/** The system category a lent payment the user calls a gift goes under (spec §5, "Let it go"). */
const GIFT_CATEGORY = 'Gifts & Donations';

interface Props {
  item: PeopleSuggestion;
  /** Today, YYYY-MM-DD: how long a balance has been open. */
  today: string;
  /** The built-in Paid back kind, for "Always do this"; null until the kinds have loaded. */
  paidBackKindId: string | null;
  onAck: () => void;
  onPickBill: (item: PeopleSuggestion) => void;
  onAsk: (item: PeopleSuggestion) => void;
  /** "Paid for something": the payment, to give it a category. */
  onOpenPayment: (item: PeopleSuggestion) => void;
  /** After an answer is saved: the page re-reads what it shows. */
  onAnswered?: () => void;
}

interface Choice {
  text: string;
  /** Spoken name, when the text alone would be ambiguous. */
  name?: string;
  primary?: boolean;
  /** An answer saved to the server: one at a time, then every figure refreshes. */
  write?: () => Promise<unknown>;
  /** Anything else: the page's own handling. */
  onClick?: () => void;
}

/**
 * One question about money with people (spec §6 A-G), in the words of the approved mockups and of
 * the app's own row (mobile/src/components/insights/PeopleLookAtRow.tsx). Nothing here changes a
 * figure until clicked; "Got it" and the "not now" answers go through the page's acknowledgement.
 */
export function PeopleQuestion({ item, today, paidBackKindId, onAck, onPickBill, onAsk, onOpenPayment, onAnswered }: Props) {
  const refreshFigures = useInvalidateMoneyFigures();
  const [busy, setBusy] = useState(false);
  // The guard itself: state only disables the buttons on the next render, too late for a double click.
  const saving = useRef(false);
  const [failed, setFailed] = useState<string | null>(null);
  // "Not a share": what the credit was, asked in place (spec §6 D).
  const [notAShare, setNotAShare] = useState(false);

  const amount = rupees(item.amount);
  const label = item.label;
  const txnId = item.txnId ?? '';
  const on = item.date ? ` on ${dayMonth(item.date)}` : '';

  async function answer(write: () => Promise<unknown>) {
    if (saving.current) return;
    saving.current = true;
    setBusy(true);
    setFailed(null);
    try {
      await write();
      refreshFigures();
      onAnswered?.();
    } catch (err) {
      setFailed(serverMessage(err, "Couldn't save that. Try again."));
    } finally {
      saving.current = false;
      setBusy(false);
    }
  }

  const gotIt = (text = 'Got it'): Choice => ({ text, name: `${text}: ${label}`, onClick: onAck });
  const owedOrBorrowed = (part?: number): Choice[] => [
    { text: 'They owed you', write: () => peopleApi.addEntry(txnId, 'PAID_BACK', part) },
    { text: 'Borrowed', write: () => peopleApi.addEntry(txnId, 'BORROWED', part) },
  ];

  let Icon: LucideIcon = ArrowDownLeft;
  let line: string;
  let question: string | null = null;
  let choices: Choice[];

  switch (item.kind) {
    case 'SHARE_CHECK':
      line = `${amount} from ${label}${on}.` + (item.category ? ` You filed it under ${item.category}.` : '');
      if (notAShare) {
        question = 'What was it, then?';
        choices = [...owedOrBorrowed(), gotIt('Something else')];
      } else {
        question = 'Is it their share of a bill you paid?';
        choices = [
          { text: 'Yes, pick the bill', primary: true, onClick: () => onPickBill(item) },
          { text: 'Not a share', onClick: () => setNotAShare(true) },
          gotIt(),
        ];
      }
      break;
    case 'LEFTOVER_CHECK':
      line = `${amount} of ${label}'s payment${on} is left after the bills it paid toward.`;
      question = 'What was the rest?';
      choices = [...owedOrBorrowed(item.amount), gotIt()];
      break;
    case 'LENT_CHECK':
      Icon = ArrowUpRight;
      line = `${amount} to ${label}${on}. Nothing back yet.`;
      question = 'What was it?';
      choices = [
        { text: `Lent to ${label}`, primary: true, write: () => peopleApi.addEntry(txnId, 'LENT') },
        { text: 'Paid for something', onClick: () => { onOpenPayment(item); onAck(); } },
        { text: 'Gift', write: async () => { await transactionsApi.updateCategory(txnId, GIFT_CATEGORY); onAck(); } },
        gotIt(),
      ];
      break;
    case 'PAYBACK_CHECK':
      line = `${amount} from ${label}${on}.`;
      question = item.owed != null ? `Paying back what you lent? ${label} owes you ${rupees(item.owed)}.` : 'Paying back what you lent?';
      choices = [
        {
          text: 'Yes, paid back',
          primary: true,
          // The second form (spec §6 C): the earlier payment becomes the loan this pays back.
          write: async () => {
            if (item.relatedTxnId) await peopleApi.addEntry(item.relatedTxnId, 'LENT');
            await peopleApi.addEntry(txnId, 'PAID_BACK');
          },
        },
        gotIt('Something else'),
      ];
      break;
    case 'SETTLE': {
      Icon = Hourglass;
      const days = item.date ? daysBetween(item.date, today) : null;
      line = `${label} owes you ${rupees(item.owed ?? item.amount)}${days != null ? `, ${days} days` : ''}.`;
      choices = [
        { text: `Ask ${label}`, primary: true, onClick: () => onAsk(item) },
        { text: 'Paid in cash', write: () => peopleApi.settle(item.personKey, 'CASH') },
        { text: 'Let it go', write: () => peopleApi.settle(item.personKey, 'LET_GO') },
        gotIt('Keep waiting'),
      ];
      break;
    }
    case 'EVERY_MONTH':
      Icon = Repeat;
      line = `${label} sent a share in other months too. Link their ${item.category ?? 'bill'} shares every month?`;
      choices = [
        { text: 'Every month', primary: true, write: () => peopleApi.addSplitRule(txnId, item.relatedTxnId ?? '') },
        gotIt('Just this one'),
      ];
      break;
    case 'ALWAYS_PERSON':
      Icon = CheckCheck;
      line = `${amount} from ${label} was money paid back.`;
      question = `Count money from ${label} as paid back every time?`;
      choices = [
        ...(paidBackKindId
          ? [{ text: `Always do this for ${label}`, primary: true, write: () => inflowApi.setChoice(txnId, paidBackKindId, 'SENDER') }]
          : []),
        gotIt('Ask each time'),
      ];
      break;
  }

  return (
    <div className="border-b border-dashed py-3 last:border-b-0">
      <div className="flex items-start gap-3">
        <div className="w-8 h-8 rounded-full flex items-center justify-center flex-shrink-0 bg-primary-light text-primary">
          <Icon size={16} aria-hidden />
        </div>
        <div className="min-w-0 flex-1 space-y-2">
          <div className="text-sm">
            <p className="text-ink">{line}</p>
            {question && <p className="font-medium text-ink">{question}</p>}
          </div>
          <div className="flex flex-wrap gap-2">
            {choices.map((c) => (
              <Button
                key={c.text}
                size="sm"
                variant={c.primary ? 'primary' : 'secondary'}
                disabled={busy}
                aria-label={c.name ?? c.text}
                onClick={() => (c.write ? void answer(c.write) : c.onClick?.())}
              >
                {c.text}
              </Button>
            ))}
          </div>
          {failed && <p role="alert" className="text-danger text-xs">{failed}</p>}
        </div>
      </div>
    </div>
  );
}
