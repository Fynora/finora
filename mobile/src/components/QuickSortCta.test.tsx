import { fireEvent, render, screen, waitFor } from '@testing-library/react-native';
import { QuickSortCta } from './QuickSortCta';
import { transactionsApi } from '../api/endpoints';

jest.mock('../api/endpoints', () => ({ transactionsApi: { quickSort: jest.fn() } }));

const tx = transactionsApi as jest.Mocked<typeof transactionsApi>;

function q(id: string) {
  return {
    id, anchorTransactionId: id, kind: 'SHOP' as const, payee: id, payments: 1, total: 10, latestDate: '2026-09-01',
    largeOneOff: false, currentCategory: 'Other', answers: [], samples: [],
  };
}
const noRest = { questions: 0, payments: 0, amount: 0, transactionIds: [] };

describe('QuickSortCta', () => {
  beforeEach(() => jest.clearAllMocks());

  it('offers the questions waiting, counted without recording a batch shown', async () => {
    tx.quickSort.mockResolvedValue({ questions: [q('a'), q('b'), q('c')], waitingTotal: 30, rest: noRest });
    render(<QuickSortCta onPress={jest.fn()} />);
    expect(await screen.findByText('Sort 3 questions')).toBeTruthy();
    expect(tx.quickSort).toHaveBeenCalledWith(0, true);
  });

  it('says question for one, and pressing it opens Quick sort', async () => {
    tx.quickSort.mockResolvedValue({ questions: [q('a')], waitingTotal: 10, rest: noRest });
    const onPress = jest.fn();
    render(<QuickSortCta onPress={onPress} />);
    fireEvent.press(await screen.findByText('Sort 1 question'));
    expect(onPress).toHaveBeenCalled();
  });

  it('shows nothing when nothing is waiting or the count cannot be read', async () => {
    tx.quickSort.mockResolvedValueOnce({ questions: [], waitingTotal: 0, rest: noRest });
    const first = render(<QuickSortCta onPress={jest.fn()} />);
    await waitFor(() => expect(tx.quickSort).toHaveBeenCalled());
    expect(first.toJSON()).toBeNull();
    first.unmount();

    tx.quickSort.mockRejectedValueOnce(new Error('down'));
    const second = render(<QuickSortCta onPress={jest.fn()} />);
    await waitFor(() => expect(tx.quickSort).toHaveBeenCalledTimes(2));
    expect(second.toJSON()).toBeNull();
  });
});
