import { act, fireEvent, render, screen, waitFor } from '@testing-library/react-native';
import { QueryClient, QueryClientProvider } from '@tanstack/react-query';
import { inflowApi, peopleApi, transactionsApi, type PeopleSuggestion } from '../../api/endpoints';
import { invalidateFinancialData } from '../../lib/invalidateFinancialData';
import { expectHaptics } from '../../test/haptics';
import { PeopleLookAtRow } from './PeopleLookAtRow';

// Hoisted above the imports by babel-jest. Every name and amount here is invented.
jest.mock('../../api/endpoints', () => ({
  peopleApi: { addEntry: jest.fn(), settle: jest.fn(), addSplitRule: jest.fn() },
  inflowApi: { setChoice: jest.fn() },
  transactionsApi: { updateCategory: jest.fn() },
}));
jest.mock('../../lib/invalidateFinancialData', () => ({ invalidateFinancialData: jest.fn() }));

const addEntry = peopleApi.addEntry as jest.Mock;
const settle = peopleApi.settle as jest.Mock;
const addSplitRule = peopleApi.addSplitRule as jest.Mock;
const setChoice = inflowApi.setChoice as jest.Mock;
const updateCategory = transactionsApi.updateCategory as jest.Mock;
const refreshed = invalidateFinancialData as jest.Mock;

function suggestion(overrides: Partial<PeopleSuggestion> = {}): PeopleSuggestion {
  return {
    kind: 'SHARE_CHECK', ackKey: 't-1', txnId: 't-1', relatedTxnId: null, personKey: 'vpa:flatmate.a',
    label: 'Flatmate A', amount: 9000, date: '2026-10-04', category: 'Rent', bills: [], owed: null,
    ...overrides,
  };
}

function show(item: PeopleSuggestion, paidBackKindId: string | null = 'k-paid-back') {
  const handlers = { onAck: jest.fn(), onPickBill: jest.fn(), onAsk: jest.fn(), onOpenPayment: jest.fn(), onAnswered: jest.fn() };
  render(
    <QueryClientProvider client={new QueryClient()}>
      <PeopleLookAtRow item={item} today="2026-12-05" paidBackKindId={paidBackKindId} {...handlers} />
    </QueryClientProvider>,
  );
  return handlers;
}

// How the server refuses an answer it cannot keep: an answered error with its own sentence.
const refused = (message: string) => Object.assign(new Error(message), {
  isAxiosError: true, response: { status: 422, data: { message, errorCode: 'TXN_007' } },
});

const press = (name: string) => fireEvent.press(screen.getByRole('button', { name }));

beforeEach(() => {
  for (const m of [addEntry, settle, addSplitRule, setChoice, updateCategory]) m.mockReset().mockResolvedValue({});
  refreshed.mockReset();
});

describe('PeopleLookAtRow: a share of a bill', () => {
  it('says where the money came from and how it was filed, and opens the bill picker', () => {
    const item = suggestion();
    const { onPickBill, onAck } = show(item);
    expect(screen.getByText('₹9,000 from Flatmate A on 4 Oct. You filed it under Rent.')).toBeTruthy();
    expect(screen.getByText('Is it their share of a bill you paid?')).toBeTruthy();
    press('Yes, pick the bill');
    expect(onPickBill).toHaveBeenCalledWith(item);
    press('Got it: Flatmate A');
    expect(onAck).toHaveBeenCalledTimes(1);
    expect(addEntry).not.toHaveBeenCalled();
  });

  it('leaves out the category sentence when the credit has none', () => {
    show(suggestion({ category: null }));
    expect(screen.getByText('₹9,000 from Flatmate A on 4 Oct.')).toBeTruthy();
  });

  it('"Not a share" asks what it was instead, and "They owed you" records the whole credit as paid back', async () => {
    show(suggestion());
    press('Not a share');
    expect(screen.getByText('What was it, then?')).toBeTruthy();
    press('They owed you');
    await waitFor(() => expect(addEntry).toHaveBeenCalledWith('t-1', 'PAID_BACK', undefined));
    await waitFor(() => expect(refreshed).toHaveBeenCalledTimes(1));
    expectHaptics({ success: 1 });
  });

  it('"Borrowed" after "Not a share" records the credit as borrowed', async () => {
    show(suggestion());
    press('Not a share');
    press('Borrowed');
    await waitFor(() => expect(addEntry).toHaveBeenCalledWith('t-1', 'BORROWED', undefined));
  });
});

describe('PeopleLookAtRow: the rest of a credit', () => {
  it('answers only the part left over, never the whole credit', async () => {
    show(suggestion({ kind: 'LEFTOVER_CHECK', amount: 500 }));
    expect(screen.getByText("₹500 of Flatmate A's payment on 4 Oct is left after the bills it paid toward.")).toBeTruthy();
    expect(screen.getByText('What was the rest?')).toBeTruthy();
    press('Borrowed');
    await waitFor(() => expect(addEntry).toHaveBeenCalledWith('t-1', 'BORROWED', 500));
  });
});

describe('PeopleLookAtRow: lent?', () => {
  const lent = suggestion({ kind: 'LENT_CHECK', personKey: 'vpa:friend.k', label: 'Friend K', amount: 2000, date: '2026-09-03', category: null });

  it('asks what a payment with nothing back was, and records "Lent"', async () => {
    show(lent);
    expect(screen.getByText('₹2,000 to Friend K on 3 Sep. Nothing back yet.')).toBeTruthy();
    expect(screen.getByText('What was it?')).toBeTruthy();
    press('Lent to Friend K');
    await waitFor(() => expect(addEntry).toHaveBeenCalledWith('t-1', 'LENT'));
  });

  it('"Paid for something" opens the payment and is not asked again', () => {
    const { onOpenPayment, onAck } = show(lent);
    press('Paid for something');
    expect(onOpenPayment).toHaveBeenCalledWith(lent);
    expect(onAck).toHaveBeenCalledTimes(1);
    expect(addEntry).not.toHaveBeenCalled();
  });

  it('"Gift" files this payment under Gifts & Donations, and teaches nothing about the person\'s other payments', async () => {
    const { onAck } = show(lent);
    press('Gift');
    await waitFor(() => expect(updateCategory).toHaveBeenCalledWith('t-1', 'Gifts & Donations', 'ONLY_THIS'));
    await waitFor(() => expect(onAck).toHaveBeenCalledTimes(1));
  });
});

describe('PeopleLookAtRow: paying back?', () => {
  it('names what they owe, and records the credit as paid back', async () => {
    show(suggestion({ kind: 'PAYBACK_CHECK', label: 'Friend K', amount: 1500, date: '2026-09-20', owed: 2000 }));
    expect(screen.getByText('₹1,500 from Friend K on 20 Sep.')).toBeTruthy();
    expect(screen.getByText('Paying back what you lent? Friend K owes you ₹2,000.')).toBeTruthy();
    press('Yes, paid back');
    await waitFor(() => expect(addEntry).toHaveBeenCalledWith('t-1', 'PAID_BACK'));
    expect(addEntry).toHaveBeenCalledTimes(1);
  });

  it('marks the earlier payment lent first, then this one paid back, when nothing was recorded yet', async () => {
    show(suggestion({ kind: 'PAYBACK_CHECK', relatedTxnId: 't-0', owed: null }));
    expect(screen.getByText('Paying back what you lent?')).toBeTruthy();
    press('Yes, paid back');
    await waitFor(() => expect(addEntry).toHaveBeenCalledTimes(2));
    expect(addEntry.mock.calls).toEqual([['t-0', 'LENT'], ['t-1', 'PAID_BACK']]);
  });

  it('"Something else" only acknowledges', () => {
    const { onAck } = show(suggestion({ kind: 'PAYBACK_CHECK', owed: 2000 }));
    press('Something else: Flatmate A');
    expect(onAck).toHaveBeenCalledTimes(1);
    expect(addEntry).not.toHaveBeenCalled();
  });
});

describe('PeopleLookAtRow: settle it', () => {
  const open = suggestion({ kind: 'SETTLE', txnId: null, ackKey: 'vpa:friend.k|e1', personKey: 'vpa:friend.k', label: 'Friend K', amount: 500, owed: 500, date: '2026-10-06' });

  it('says how much and for how long, and settles in cash or lets it go', async () => {
    show(open);
    expect(screen.getByText('Friend K owes you ₹500, 60 days.')).toBeTruthy();
    press('Paid in cash');
    await waitFor(() => expect(settle).toHaveBeenCalledWith('vpa:friend.k', 'CASH'));
    press('Let it go');
    await waitFor(() => expect(settle).toHaveBeenCalledWith('vpa:friend.k', 'LET_GO'));
  });

  it('"Ask" hands over to the screen, and "Keep waiting" acknowledges', () => {
    const { onAsk, onAck } = show(open);
    press('Ask Friend K');
    expect(onAsk).toHaveBeenCalledWith(open);
    press('Keep waiting: Friend K');
    expect(onAck).toHaveBeenCalledTimes(1);
    expect(settle).not.toHaveBeenCalled();
  });
});

describe('PeopleLookAtRow: every month?', () => {
  it('makes a rule from this credit and the bill it was linked to', async () => {
    show(suggestion({ kind: 'EVERY_MONTH', relatedTxnId: 'bill-1' }));
    expect(screen.getByText('Flatmate A sent a share in other months too. Link their Rent shares every month?')).toBeTruthy();
    press('Every month');
    await waitFor(() => expect(addSplitRule).toHaveBeenCalledWith('t-1', 'bill-1'));
  });

  it('"Just this one" acknowledges', () => {
    const { onAck } = show(suggestion({ kind: 'EVERY_MONTH', relatedTxnId: 'bill-1' }));
    press('Just this one: Flatmate A');
    expect(onAck).toHaveBeenCalledTimes(1);
  });
});

describe('PeopleLookAtRow: always for this person?', () => {
  const always = suggestion({ kind: 'ALWAYS_PERSON', label: 'Friend K', amount: 1500 });

  it('remembers the sender with the built-in Paid back kind', async () => {
    show(always);
    expect(screen.getByText('Count money from Friend K as paid back every time?')).toBeTruthy();
    press('Always do this for Friend K');
    await waitFor(() => expect(setChoice).toHaveBeenCalledWith('t-1', 'k-paid-back', 'SENDER'));
  });

  it('offers only "Ask each time" until the kinds have loaded', () => {
    show(always, null);
    expect(screen.queryByRole('button', { name: 'Always do this for Friend K' })).toBeNull();
    expect(screen.getByRole('button', { name: 'Ask each time: Friend K' })).toBeTruthy();
  });
});

describe('PeopleLookAtRow: once answered', () => {
  it('tells its host after a saved answer, never after a failed one', async () => {
    const { onAnswered } = show(suggestion({ kind: 'EVERY_MONTH', relatedTxnId: 'bill-1' }));
    addSplitRule.mockRejectedValueOnce(new Error('offline'));
    press('Every month');
    await waitFor(() => expect(screen.getByText("Couldn't save that. Try again.")).toBeTruthy());
    expect(onAnswered).not.toHaveBeenCalled();
    press('Every month');
    await waitFor(() => expect(onAnswered).toHaveBeenCalledTimes(1));
  });
});

describe('PeopleLookAtRow: a failed answer', () => {
  it('says why, in the server\'s words, when the server refuses the answer', async () => {
    addEntry.mockRejectedValueOnce(refused("Fynora can't tell this sender apart from others, so it can't keep this for them."));
    show(suggestion());
    press('Not a share');
    press('They owed you');
    expect(await screen.findByText("Fynora can't tell this sender apart from others, so it can't keep this for them.")).toBeTruthy();
  });

  it('says so, refreshes nothing, and lets the user try again', async () => {
    addEntry.mockRejectedValueOnce(new Error('offline'));
    show(suggestion({ kind: 'LENT_CHECK' }));
    press('Lent to Flatmate A');
    await waitFor(() => expect(screen.getByText("Couldn't save that. Try again.")).toBeTruthy());
    expect(refreshed).not.toHaveBeenCalled();
    expectHaptics({ error: 1 });
    press('Lent to Flatmate A');
    await waitFor(() => expect(refreshed).toHaveBeenCalledTimes(1));
    expect(screen.queryByText("Couldn't save that. Try again.")).toBeNull();
  });

  it('takes one answer at a time: a second tap while saving sends nothing', async () => {
    let finish: () => void = () => {};
    addEntry.mockImplementationOnce(() => new Promise<void>((resolve) => { finish = resolve; }));
    show(suggestion({ kind: 'LENT_CHECK' }));
    // Both taps land before the row re-renders, as two taps in one frame do on a phone.
    act(() => {
      press('Lent to Flatmate A');
      press('Lent to Flatmate A');
    });
    finish();
    await waitFor(() => expect(refreshed).toHaveBeenCalledTimes(1));
    expect(addEntry).toHaveBeenCalledTimes(1);
  });
});
