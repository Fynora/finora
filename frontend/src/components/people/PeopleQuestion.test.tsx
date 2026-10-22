import { describe, it, expect, vi, beforeEach } from 'vitest';
import { act, fireEvent, render, screen, waitFor } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { QueryClient, QueryClientProvider } from '@tanstack/react-query';
import { inflowApi, peopleApi, transactionsApi, type PeopleSuggestion } from '../../api/endpoints';
import { PeopleQuestion } from './PeopleQuestion';

// Every name and amount here is invented.
vi.mock('../../api/endpoints', () => ({
  peopleApi: { addEntry: vi.fn(), settle: vi.fn(), addSplitRule: vi.fn() },
  inflowApi: { setChoice: vi.fn() },
  transactionsApi: { updateCategory: vi.fn() },
}));
const refreshed = vi.fn();
vi.mock('../../lib/invalidateMoneyFigures', () => ({ useInvalidateMoneyFigures: () => refreshed }));

const addEntry = vi.mocked(peopleApi.addEntry);
const settle = vi.mocked(peopleApi.settle);
const addSplitRule = vi.mocked(peopleApi.addSplitRule);
const setChoice = vi.mocked(inflowApi.setChoice);
const updateCategory = vi.mocked(transactionsApi.updateCategory);

function suggestion(overrides: Partial<PeopleSuggestion> = {}): PeopleSuggestion {
  return {
    kind: 'SHARE_CHECK', ackKey: 't-1', txnId: 't-1', relatedTxnId: null, personKey: 'vpa:flatmate.a',
    label: 'Flatmate A', amount: 9000, date: '2026-10-04', category: 'Rent', bills: [], owed: null,
    ...overrides,
  };
}

function show(item: PeopleSuggestion, paidBackKindId: string | null = 'k-paid-back') {
  const handlers = { onAck: vi.fn(), onPickBill: vi.fn(), onAsk: vi.fn(), onOpenPayment: vi.fn(), onAnswered: vi.fn() };
  render(
    <QueryClientProvider client={new QueryClient()}>
      <PeopleQuestion item={item} today="2026-12-05" paidBackKindId={paidBackKindId} {...handlers} />
    </QueryClientProvider>,
  );
  return handlers;
}

const press = (name: string) => userEvent.click(screen.getByRole('button', { name }));
const refused = (message: string) => Object.assign(new Error(message), { response: { status: 422, data: { message } } });

beforeEach(() => {
  for (const m of [addEntry, settle, addSplitRule, setChoice, updateCategory]) m.mockReset().mockResolvedValue({} as never);
  refreshed.mockReset();
});

describe('PeopleQuestion: a share of a bill', () => {
  it('says where the money came from and how it was filed, and opens the bill picker', async () => {
    const item = suggestion();
    const { onPickBill, onAck } = show(item);
    expect(screen.getByText('₹9,000 from Flatmate A on 4 Oct. You filed it under Rent.')).toBeInTheDocument();
    expect(screen.getByText('Is it their share of a bill you paid?')).toBeInTheDocument();
    await press('Yes, link it to a bill');
    expect(onPickBill).toHaveBeenCalledWith(item);
    await press('Leave as it is: Flatmate A');
    expect(onAck).toHaveBeenCalledTimes(1);
    expect(addEntry).not.toHaveBeenCalled();
  });

  it('leaves out the category sentence when the credit has none', () => {
    show(suggestion({ category: null }));
    expect(screen.getByText('₹9,000 from Flatmate A on 4 Oct.')).toBeInTheDocument();
  });

  it('"No, it\'s not a share" asks what it was instead; "They owed you" records the whole credit as paid back', async () => {
    const { onAnswered } = show(suggestion());
    await press("No, it's not a share");
    expect(screen.getByText('What was it, then?')).toBeInTheDocument();
    await press('They owed you');
    await waitFor(() => expect(addEntry).toHaveBeenCalledWith('t-1', 'PAID_BACK', undefined));
    await waitFor(() => expect(refreshed).toHaveBeenCalledTimes(1));
    expect(onAnswered).toHaveBeenCalledTimes(1);
  });

  it('"Borrowed" after "No, it\'s not a share" records the credit as borrowed', async () => {
    show(suggestion());
    await press("No, it's not a share");
    await press('Borrowed');
    await waitFor(() => expect(addEntry).toHaveBeenCalledWith('t-1', 'BORROWED', undefined));
  });
});

describe('PeopleQuestion: the rest of a credit', () => {
  it('answers only the part left over, never the whole credit', async () => {
    show(suggestion({ kind: 'LEFTOVER_CHECK', amount: 500 }));
    expect(screen.getByText("₹500 of Flatmate A's payment on 4 Oct is left after the bills it paid toward.")).toBeInTheDocument();
    expect(screen.getByText('What was the rest?')).toBeInTheDocument();
    await press('Borrowed');
    await waitFor(() => expect(addEntry).toHaveBeenCalledWith('t-1', 'BORROWED', 500));
  });
});

describe('PeopleQuestion: lent?', () => {
  const lent = suggestion({ kind: 'LENT_CHECK', personKey: 'vpa:friend.k', label: 'Friend K', amount: 2000, date: '2026-09-03', category: null });

  it('asks what a payment with nothing back was, and records "Lent"', async () => {
    show(lent);
    expect(screen.getByText('₹2,000 to Friend K on 3 Sep. Nothing back yet.')).toBeInTheDocument();
    expect(screen.getByText('What was it?')).toBeInTheDocument();
    await press('Lent to Friend K');
    await waitFor(() => expect(addEntry).toHaveBeenCalledWith('t-1', 'LENT'));
  });

  it('"Paid for something" opens the payment and is not asked again', async () => {
    const { onOpenPayment, onAck } = show(lent);
    await press('Paid for something');
    expect(onOpenPayment).toHaveBeenCalledWith(lent);
    expect(onAck).toHaveBeenCalledTimes(1);
    expect(addEntry).not.toHaveBeenCalled();
  });

  it('"Gift" files this payment under Gifts & Donations, and teaches nothing about the person\'s other payments', async () => {
    const { onAck } = show(lent);
    await press('Gift');
    await waitFor(() => expect(updateCategory).toHaveBeenCalledWith('t-1', 'Gifts & Donations', 'ONLY_THIS'));
    await waitFor(() => expect(onAck).toHaveBeenCalledTimes(1));
  });
});

describe('PeopleQuestion: paying back?', () => {
  it('names what they owe, and records the credit as paid back', async () => {
    show(suggestion({ kind: 'PAYBACK_CHECK', label: 'Friend K', amount: 1500, date: '2026-09-20', owed: 2000 }));
    expect(screen.getByText('₹1,500 from Friend K on 20 Sep.')).toBeInTheDocument();
    expect(screen.getByText('Paying back what you lent? Friend K owes you ₹2,000.')).toBeInTheDocument();
    await press('Yes, paid back');
    await waitFor(() => expect(addEntry).toHaveBeenCalledWith('t-1', 'PAID_BACK'));
    expect(addEntry).toHaveBeenCalledTimes(1);
  });

  it('marks the earlier payment lent first, then this one paid back, when nothing was recorded yet', async () => {
    show(suggestion({ kind: 'PAYBACK_CHECK', relatedTxnId: 't-0', owed: null }));
    expect(screen.getByText('Paying back what you lent?')).toBeInTheDocument();
    await press('Yes, paid back');
    await waitFor(() => expect(addEntry).toHaveBeenCalledTimes(2));
    expect(addEntry.mock.calls).toEqual([['t-0', 'LENT'], ['t-1', 'PAID_BACK']]);
  });

  it('"Something else" only acknowledges', async () => {
    const { onAck } = show(suggestion({ kind: 'PAYBACK_CHECK', owed: 2000 }));
    await press('Something else: Flatmate A');
    expect(onAck).toHaveBeenCalledTimes(1);
    expect(addEntry).not.toHaveBeenCalled();
  });
});

describe('PeopleQuestion: settle it', () => {
  const open = suggestion({ kind: 'SETTLE', txnId: null, ackKey: 'vpa:friend.k|e1', personKey: 'vpa:friend.k', label: 'Friend K', amount: 500, owed: 500, date: '2026-10-06' });

  it('says how much and for how long, and settles in cash or lets it go', async () => {
    show(open);
    expect(screen.getByText('Friend K owes you ₹500, 60 days.')).toBeInTheDocument();
    await press('Paid in cash');
    await waitFor(() => expect(settle).toHaveBeenCalledWith('vpa:friend.k', 'CASH'));
    await press('Let it go');
    await waitFor(() => expect(settle).toHaveBeenCalledWith('vpa:friend.k', 'LET_GO'));
  });

  it('"Ask" hands over to the page, and "Keep waiting" acknowledges', async () => {
    const { onAsk, onAck } = show(open);
    await press('Ask Friend K');
    expect(onAsk).toHaveBeenCalledWith(open);
    await press('Keep waiting: Friend K');
    expect(onAck).toHaveBeenCalledTimes(1);
    expect(settle).not.toHaveBeenCalled();
  });
});

describe('PeopleQuestion: every month?', () => {
  it('makes a rule from this credit and the bill it was linked to', async () => {
    show(suggestion({ kind: 'EVERY_MONTH', relatedTxnId: 'bill-1' }));
    expect(screen.getByText('Flatmate A sent a share in other months too. Link their Rent shares every month?')).toBeInTheDocument();
    await press('Every month');
    await waitFor(() => expect(addSplitRule).toHaveBeenCalledWith('t-1', 'bill-1'));
  });

  it('"Just this one" acknowledges', async () => {
    const { onAck } = show(suggestion({ kind: 'EVERY_MONTH', relatedTxnId: 'bill-1' }));
    await press('Just this one: Flatmate A');
    expect(onAck).toHaveBeenCalledTimes(1);
  });
});

describe('PeopleQuestion: always for this person?', () => {
  const always = suggestion({ kind: 'ALWAYS_PERSON', label: 'Friend K', amount: 1500 });

  it('remembers the sender with the built-in Paid back kind', async () => {
    show(always);
    expect(screen.getByText('Count money from Friend K as paid back every time?')).toBeInTheDocument();
    await press('Always do this for Friend K');
    await waitFor(() => expect(setChoice).toHaveBeenCalledWith('t-1', 'k-paid-back', 'SENDER'));
  });

  it('offers only "Ask each time" until the kinds have loaded', () => {
    show(always, null);
    expect(screen.queryByRole('button', { name: 'Always do this for Friend K' })).toBeNull();
    expect(screen.getByRole('button', { name: 'Ask each time: Friend K' })).toBeInTheDocument();
  });
});

describe('PeopleQuestion: a failed answer', () => {
  it('says why, in the server\'s words, when the server refuses the answer', async () => {
    addEntry.mockRejectedValueOnce(refused("Fynora can't tell this sender apart from others, so it can't keep this for them."));
    show(suggestion());
    await press("No, it's not a share");
    await press('They owed you');
    expect(await screen.findByText("Fynora can't tell this sender apart from others, so it can't keep this for them.")).toBeInTheDocument();
  });

  it('says so, refreshes nothing and tells nobody it was answered, and lets the user try again', async () => {
    addEntry.mockRejectedValueOnce(new Error('offline'));
    const { onAnswered } = show(suggestion({ kind: 'LENT_CHECK' }));
    await press('Lent to Flatmate A');
    expect(await screen.findByText("Couldn't save that. Try again.")).toBeInTheDocument();
    expect(refreshed).not.toHaveBeenCalled();
    expect(onAnswered).not.toHaveBeenCalled();
    await press('Lent to Flatmate A');
    await waitFor(() => expect(refreshed).toHaveBeenCalledTimes(1));
    expect(screen.queryByText("Couldn't save that. Try again.")).toBeNull();
  });

  it('takes one answer at a time: a second click before the first lands sends nothing', async () => {
    let finish: () => void = () => {};
    addEntry.mockImplementationOnce(() => new Promise((resolve) => { finish = () => resolve({} as never); }));
    show(suggestion({ kind: 'LENT_CHECK' }));
    const button = screen.getByRole('button', { name: 'Lent to Flatmate A' });
    // Both clicks land before React re-renders the disabled state, as a fast double click can.
    act(() => {
      fireEvent.click(button);
      fireEvent.click(button);
    });
    finish();
    await waitFor(() => expect(refreshed).toHaveBeenCalledTimes(1));
    expect(addEntry).toHaveBeenCalledTimes(1);
  });
});
