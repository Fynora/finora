import { act, fireEvent, render, screen, waitFor } from '@testing-library/react-native';
import { AccessibilityInfo } from 'react-native';
import { aiInsightsApi, type AiWhy } from '../../api/endpoints';
import { LookAtSection } from './LookAtSection';
import type { LookAtRow } from '../../lib/insightsFeed';

jest.mock('../../api/endpoints', () => ({ aiInsightsApi: { why: jest.fn(), feedback: jest.fn() } }));
const why = aiInsightsApi.why as jest.Mock;

// The question has its own tests; a stand-in that shows what it was given is enough here.
jest.mock('../RecurringQuestion', () => {
  const { Text: MockText } = jest.requireActual('react-native');
  return {
    RecurringQuestion: ({ merchant, state, amount }: { merchant: string; state: string; amount: number }) => (
      <MockText>{`question ${merchant} ${state} ${amount}`}</MockText>
    ),
  };
});

// The people row has its own tests; a stand-in that shows its kind and hands back its callbacks.
jest.mock('./PeopleLookAtRow', () => {
  const { Pressable: MockPressable, Text: MockText } = jest.requireActual('react-native');
  return {
    PeopleLookAtRow: ({ item, onAck, onPickBill }: { item: { kind: string; label: string }; onAck: () => void; onPickBill: (i: unknown) => void }) => (
      <>
        <MockText>{`people ${item.kind} ${item.label}`}</MockText>
        <MockPressable accessibilityRole="button" accessibilityLabel={`ack ${item.label}`} onPress={onAck} />
        <MockPressable accessibilityRole="button" accessibilityLabel={`pick ${item.label}`} onPress={() => onPickBill(item)} />
      </>
    ),
  };
});

const card: LookAtRow = {
  kind: 'card-statement', month: '2026-09', total: 21400, count: 2, cardName: 'Sample Card', cardLast4: '0001',
  ackKind: 'CARD_STATEMENT_MISSING', ackItemKey: '2026-09',
};
const unknownCard: LookAtRow = { ...card, cardName: null, cardLast4: null };
const high: LookAtRow = {
  kind: 'running-high', category: 'Food', current: 9400, priorAverage: 6300, month: '2026-09',
  ackKind: 'CATEGORY_HIGH', ackItemKey: 'Food|2026-09',
};
const changed: LookAtRow = {
  kind: 'amount-changed', merchant: 'Sample TV', category: 'Subscriptions', latestAmount: 649,
  latestDate: '2026-09-12', state: 'AMOUNT_CHANGED', ackKind: 'AMOUNT_CHANGED', ackItemKey: 'sample tv|2026-09-12',
};
const newOne: LookAtRow = {
  kind: 'new-recurring',
  item: { merchant: 'sample gym', label: 'Monthly', averageAmount: 1500, occurrences: 3, lastDate: '2026-09-05', nextEstimate: '2026-10-05', state: 'NEEDS_ANSWER' },
};

const share: LookAtRow = {
  kind: 'people',
  item: {
    kind: 'SHARE_CHECK', ackKey: 't-1', txnId: 't-1', relatedTxnId: null, personKey: 'vpa:flatmate.a',
    label: 'Flatmate A', amount: 9000, date: '2026-10-04', category: 'Rent', bills: [], owed: null,
  },
};

function show(rows: LookAtRow[], requestMonth?: string) {
  const handlers = {
    onOpenRow: jest.fn(), onAck: jest.fn(), onUpload: jest.fn(),
    onPickBill: jest.fn(), onAsk: jest.fn(), onOpenPayment: jest.fn(), onSeePlus: jest.fn(), onAiText: jest.fn(),
  };
  render(
    <LookAtSection rows={rows} monthLong="September 2026" today="2026-10-20" paidBackKindId="k" requestMonth={requestMonth} {...handlers} />,
  );
  return handlers;
}

describe('LookAtSection', () => {
  it('shows a people row, acknowledges it as that row, and passes the bill picker through', () => {
    const { onAck, onPickBill } = show([share]);
    expect(screen.getByText('people SHARE_CHECK Flatmate A')).toBeTruthy();
    fireEvent.press(screen.getByRole('button', { name: 'ack Flatmate A' }));
    expect(onAck).toHaveBeenCalledWith(share);
    fireEvent.press(screen.getByRole('button', { name: 'pick Flatmate A' }));
    expect(onPickBill).toHaveBeenCalledWith(share.kind === 'people' && share.item);
  });

  it('names the card when it is known, and offers the upload', () => {
    const { onUpload, onAck } = show([card]);
    expect(screen.getByText('You paid ₹21,400 toward your Sample Card ending 0001 in September 2026 with no card statement uploaded for it. Upload it to see what you bought.')).toBeTruthy();
    fireEvent.press(screen.getByRole('button', { name: 'Upload statement' }));
    expect(onUpload).toHaveBeenCalledTimes(1);
    fireEvent.press(screen.getByRole('button', { name: 'Got it: card statement for September 2026' }));
    expect(onAck).toHaveBeenCalledWith(card);
  });

  it('calls an account named after its bank a card, once', () => {
    show([{ ...card, cardName: 'Sample Bank' } as LookAtRow]);
    expect(screen.getByText(/toward your Sample Bank card ending 0001 in September 2026/)).toBeTruthy();
  });

  it('says "a credit card" when the card is not known', () => {
    show([unknownCard]);
    expect(screen.getByText('You paid ₹21,400 toward a credit card in September 2026 with no card statement uploaded for it. Upload it to see what you bought.')).toBeTruthy();
  });

  it('names the card without digits when only its name is known', () => {
    show([{ ...card, cardLast4: null } as LookAtRow]);
    expect(screen.getByText(/toward your Sample Card in September 2026/)).toBeTruthy();
  });

  it('says how far a category ran above its usual month, in rupees, and opens it', () => {
    const { onOpenRow, onAck } = show([high]);
    expect(screen.getByText('Food is ₹3,100 above your usual month')).toBeTruthy();
    expect(screen.getByText('₹9,400 vs ₹6,300')).toBeTruthy();
    fireEvent.press(screen.getByRole('button', { name: 'Food is ₹3,100 above your usual month, ₹9,400 vs ₹6,300. Shows the payments' }));
    expect(onOpenRow).toHaveBeenCalledWith(high);
    fireEvent.press(screen.getByRole('button', { name: 'Got it: Food' }));
    expect(onAck).toHaveBeenCalledWith(high);
  });

  it('asks about a new repeating payment in place, with no "Got it"', () => {
    show([newOne]);
    expect(screen.getByText('New repeating payment: sample gym')).toBeTruthy();
    expect(screen.getByText('question sample gym NEEDS_ANSWER 1500')).toBeTruthy();
    expect(screen.queryByRole('button', { name: /^Got it/ })).toBeNull();
  });

  it('asks again about an amount that changed, and can be marked "Got it"', () => {
    const { onAck, onOpenRow } = show([changed]);
    expect(screen.getByText('Amount changed: Sample TV')).toBeTruthy();
    expect(screen.getByText('question Sample TV AMOUNT_CHANGED 649')).toBeTruthy();
    fireEvent.press(screen.getByRole('button', { name: 'Show the payments to Sample TV' }));
    expect(onOpenRow).toHaveBeenCalledWith(changed);
    fireEvent.press(screen.getByRole('button', { name: 'Got it: Sample TV' }));
    expect(onAck).toHaveBeenCalledWith(changed);
  });

  it('says plainly when there is nothing to look at', () => {
    show([]);
    expect(screen.getByText('Nothing new to look at for September 2026.')).toBeTruthy();
  });

  it('names no month in the empty line when it has none', () => {
    const handlers = {
      onOpenRow: jest.fn(), onAck: jest.fn(), onUpload: jest.fn(),
      onPickBill: jest.fn(), onAsk: jest.fn(), onOpenPayment: jest.fn(), onSeePlus: jest.fn(),
    };
    render(<LookAtSection rows={[]} monthLong="" today="2026-10-20" paidBackKindId={null} {...handlers} />);
    expect(screen.getByText('Nothing new to look at.')).toBeTruthy();
  });

  it('heads the section with its count', () => {
    show([card, high]);
    expect(screen.getByText('To look at')).toBeTruthy();
    expect(screen.getByText('2')).toBeTruthy();
  });
});

describe('LookAtSection: "Why?"', () => {
  // A running-high row whose usual month holds enough history for an explanation.
  const explainable: LookAtRow = {
    kind: 'running-high', category: 'Travel', current: 12000, priorAverage: 7000, month: '2026-09',
    ackKind: 'CATEGORY_HIGH', ackItemKey: 'Travel|2026-09', whyAvailable: true,
  };
  const AI_ANSWER: AiWhy = {
    status: 'ready', id: 'why-1', text: 'Travel was ₹5,000 above your usual month, mostly one fare on 14 Sep.',
    writtenByAi: true, basis: 'Compared with your usual month (Jun–Aug)', freeLeftThisMonth: null,
  };
  const RULES_ANSWER: AiWhy = {
    status: 'ready', id: null, text: 'Travel was ₹12,000 this month against ₹7,000 in a usual month.',
    writtenByAi: false, basis: 'Compared with your usual month (Jun–Aug)', freeLeftThisMonth: 0,
  };

  let announce: jest.SpyInstance;
  beforeEach(() => {
    why.mockReset().mockResolvedValue(AI_ANSWER);
    announce = jest.spyOn(AccessibilityInfo, 'announceForAccessibility').mockImplementation(() => {});
  });
  afterEach(() => announce.mockRestore());

  it('is offered only on a running-high row the server can explain', () => {
    show([card, high, explainable, changed]);
    expect(screen.getAllByRole('button', { name: /^Why is / })).toHaveLength(1);
    expect(screen.getByRole('button', { name: 'Why is Travel above your usual month?' })).toBeTruthy();
  });

  it('opens the answer under the row, asked by the row\'s own key', async () => {
    const { onAiText } = show([explainable]);
    fireEvent.press(screen.getByRole('button', { name: 'Why is Travel above your usual month?' }));
    expect(await screen.findByText(AI_ANSWER.text as string)).toBeTruthy();
    expect(screen.getByText('Written by AI')).toBeTruthy();
    // No month picked: the newest, as the rows themselves were asked for.
    expect(why).toHaveBeenCalledWith('CATEGORY_HIGH', 'Travel|2026-09', undefined);
    expect(onAiText).toHaveBeenCalled();

    // Closing and opening again shows the same answer, without asking again.
    fireEvent.press(screen.getByRole('button', { name: 'Hide why Travel is above your usual month' }));
    expect(screen.queryByText(AI_ANSWER.text as string)).toBeNull();
    fireEvent.press(screen.getByRole('button', { name: 'Why is Travel above your usual month?' }));
    expect(screen.getByText(AI_ANSWER.text as string)).toBeTruthy();
    expect(why).toHaveBeenCalledTimes(1);
  });

  it('announces an opened answer to a screen reader', async () => {
    show([explainable]);
    fireEvent.press(screen.getByRole('button', { name: 'Why is Travel above your usual month?' }));
    await waitFor(() => expect(announce).toHaveBeenCalledWith(AI_ANSWER.text));
  });

  it('does not read out or count an answer closed before it arrived, and does once it is opened', async () => {
    let resolve: (v: AiWhy) => void = () => {};
    why.mockImplementation(() => new Promise<AiWhy>((r) => { resolve = r; }));
    const { onAiText } = show([explainable]);
    fireEvent.press(screen.getByRole('button', { name: 'Why is Travel above your usual month?' }));
    fireEvent.press(screen.getByRole('button', { name: 'Hide why Travel is above your usual month' }));
    await act(async () => { resolve(AI_ANSWER); });
    expect(announce).not.toHaveBeenCalled();
    expect(onAiText).not.toHaveBeenCalled();
    fireEvent.press(screen.getByRole('button', { name: 'Why is Travel above your usual month?' }));
    expect(screen.getByText(AI_ANSWER.text as string)).toBeTruthy();
    expect(onAiText).toHaveBeenCalled();
    expect(why).toHaveBeenCalledTimes(1);
  });

  it('sends one request for a double tap', async () => {
    let resolve: (v: AiWhy) => void = () => {};
    why.mockImplementation(() => new Promise<AiWhy>((r) => { resolve = r; }));
    show([explainable]);
    const button = screen.getByRole('button', { name: 'Why is Travel above your usual month?' });
    fireEvent.press(button);
    fireEvent.press(screen.getByRole('button', { name: /why.*Travel/i }));
    fireEvent.press(screen.getByRole('button', { name: /why.*Travel/i }));
    resolve(AI_ANSWER);
    await waitFor(() => expect(why).toHaveBeenCalledTimes(1));
    expect(why).toHaveBeenCalledTimes(1);
  });

  it('says how many free explanations are left this month', async () => {
    why.mockResolvedValue({ ...AI_ANSWER, freeLeftThisMonth: 4 });
    show([explainable]);
    fireEvent.press(screen.getByRole('button', { name: 'Why is Travel above your usual month?' }));
    expect(await screen.findByText('4 of 5 left this month')).toBeTruthy();
  });

  it('at none left shows the rules answer, the limit line and a way to Plus', async () => {
    why.mockResolvedValue(RULES_ANSWER);
    const { onSeePlus, onAiText } = show([explainable]);
    fireEvent.press(screen.getByRole('button', { name: 'Why is Travel above your usual month?' }));
    expect(await screen.findByText(RULES_ANSWER.text as string)).toBeTruthy();
    expect(screen.getByText("You've used this month's 5 AI explanations. Plus explains every row.")).toBeTruthy();
    expect(screen.queryByText('Written by AI')).toBeNull();
    fireEvent.press(screen.getByRole('button', { name: 'See Plus' }));
    expect(onSeePlus).toHaveBeenCalledTimes(1);
    expect(onAiText).not.toHaveBeenCalled();
  });

  it('shows the rules answer plainly when the AI was not used, with no limit line', async () => {
    why.mockResolvedValue({ ...RULES_ANSWER, freeLeftThisMonth: null });
    show([explainable]);
    fireEvent.press(screen.getByRole('button', { name: 'Why is Travel above your usual month?' }));
    expect(await screen.findByText(RULES_ANSWER.text as string)).toBeTruthy();
    expect(screen.queryByText(/5 AI explanations/)).toBeNull();
  });

  it('says so when the answer cannot be loaded, and tries again on request', async () => {
    why.mockRejectedValueOnce(new Error('offline')).mockResolvedValue(AI_ANSWER);
    show([explainable]);
    fireEvent.press(screen.getByRole('button', { name: 'Why is Travel above your usual month?' }));
    expect(await screen.findByText("Couldn't load the explanation.")).toBeTruthy();
    fireEvent.press(screen.getByRole('button', { name: 'Try again' }));
    expect(await screen.findByText(AI_ANSWER.text as string)).toBeTruthy();
    expect(why).toHaveBeenCalledTimes(2);
  });

  it('names the month the rows were asked for', async () => {
    show([explainable], '2026-08');
    fireEvent.press(screen.getByRole('button', { name: 'Why is Travel above your usual month?' }));
    await waitFor(() => expect(why).toHaveBeenCalledWith('CATEGORY_HIGH', 'Travel|2026-09', '2026-08'));
  });

  it('offers no retry for a row the server no longer lists', async () => {
    why.mockRejectedValue(Object.assign(new Error('Not Found'), { response: { status: 404 } }));
    show([explainable]);
    fireEvent.press(screen.getByRole('button', { name: 'Why is Travel above your usual month?' }));
    expect(await screen.findByText('There is no explanation for this yet.')).toBeTruthy();
    expect(screen.queryByRole('button', { name: 'Try again' })).toBeNull();
    expect(screen.queryByText("Couldn't load the explanation.")).toBeNull();
  });

  it('says so when the server has no explanation for the row', async () => {
    why.mockResolvedValue({ ...AI_ANSWER, status: 'none', id: null, text: null, basis: null });
    show([explainable]);
    fireEvent.press(screen.getByRole('button', { name: 'Why is Travel above your usual month?' }));
    expect(await screen.findByText('There is no explanation for this yet.')).toBeTruthy();
  });
});
