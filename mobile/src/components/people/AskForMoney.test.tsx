import { Pressable, Share, Text } from 'react-native';
import { fireEvent, render, screen, waitFor } from '@testing-library/react-native';
import { QueryClient, QueryClientProvider } from '@tanstack/react-query';
import { peopleApi } from '../../api/endpoints';
import { upiRequestUrl, useAskForMoney } from './AskForMoney';

// Hoisted above the imports by babel-jest. Every id and amount here is invented.
jest.mock('../../api/endpoints', () => ({
  peopleApi: { preferences: jest.fn(), setOwnUpiId: jest.fn() },
}));
jest.mock('../../lib/useReduceTransparency', () => ({ useReduceTransparency: jest.fn(() => null) }));
// The UPI request names the user (the payee).
jest.mock('../../context/AuthContext', () => ({ useAuth: () => ({ fullName: 'Sample User' }) }));

const preferences = peopleApi.preferences as jest.Mock;
const setOwnUpiId = peopleApi.setOwnUpiId as jest.Mock;
let share: jest.SpyInstance;

function Host() {
  const { ask, prompt } = useAskForMoney();
  return (
    <>
      <Pressable accessibilityRole="button" accessibilityLabel="ask" onPress={() => ask({ label: 'Friend K', owed: 500 })}>
        <Text>ask</Text>
      </Pressable>
      {prompt}
    </>
  );
}

function show() {
  render(
    <QueryClientProvider client={new QueryClient({ defaultOptions: { queries: { retry: false, gcTime: 0 } } })}>
      <Host />
    </QueryClientProvider>,
  );
}

beforeEach(() => {
  preferences.mockReset();
  setOwnUpiId.mockReset().mockImplementation(async (ownUpiId: string) => ({ ownUpiId }));
  share = jest.spyOn(Share, 'share').mockResolvedValue({ action: 'sharedAction' } as never);
});

afterEach(() => share.mockRestore());

describe('upiRequestUrl', () => {
  // NPCI UPI Linking Specification 1.6: pa and pn (the payee's name) are mandatory.
  it('asks for the amount owed, to the user\'s own UPI id and name, in rupees', () => {
    expect(upiRequestUrl('user.sample@okbank', 'Sample User', 500))
      .toBe('upi://pay?pa=user.sample@okbank&pn=Sample%20User&am=500.00&cu=INR');
    expect(upiRequestUrl('user.sample@okbank', 'Sample User', 1234.5))
      .toBe('upi://pay?pa=user.sample@okbank&pn=Sample%20User&am=1234.50&cu=INR');
  });

  it('names the payee by the UPI handle when the account has no name', () => {
    expect(upiRequestUrl('user.sample@okbank', null, 500)).toBe('upi://pay?pa=user.sample@okbank&pn=user.sample&am=500.00&cu=INR');
  });

  it('keeps a name\'s own characters from changing the request', () => {
    expect(upiRequestUrl('user.sample@okbank', 'A&B=C', 500)).toBe('upi://pay?pa=user.sample@okbank&pn=A%26B%3DC&am=500.00&cu=INR');
  });
});

describe('useAskForMoney', () => {
  it('shares the request at once when the UPI id is already saved, and sends nothing itself', async () => {
    preferences.mockResolvedValue({ ownUpiId: 'user.sample@okbank' });
    show();
    fireEvent.press(screen.getByRole('button', { name: 'ask' }));
    await waitFor(() => expect(share).toHaveBeenCalledWith({ message: 'upi://pay?pa=user.sample@okbank&pn=Sample%20User&am=500.00&cu=INR' }));
    expect(setOwnUpiId).not.toHaveBeenCalled();
    expect(screen.queryByLabelText('Your UPI id')).toBeNull();
  });

  it('asks once for the UPI id, saves it, then shares', async () => {
    preferences.mockResolvedValue({ ownUpiId: null });
    show();
    fireEvent.press(screen.getByRole('button', { name: 'ask' }));
    const input = await screen.findByLabelText('Your UPI id');
    expect(share).not.toHaveBeenCalled();
    fireEvent.changeText(input, ' user.sample@okbank ');
    fireEvent.press(screen.getByRole('button', { name: 'Save and share' }));
    await waitFor(() => expect(setOwnUpiId).toHaveBeenCalledWith('user.sample@okbank'));
    await waitFor(() => expect(share).toHaveBeenCalledWith({ message: 'upi://pay?pa=user.sample@okbank&pn=Sample%20User&am=500.00&cu=INR' }));
    await waitFor(() => expect(screen.queryByLabelText('Your UPI id')).toBeNull());
  });

  it('refuses what is not a UPI id, and saves nothing', async () => {
    preferences.mockResolvedValue({ ownUpiId: null });
    show();
    fireEvent.press(screen.getByRole('button', { name: 'ask' }));
    fireEvent.changeText(await screen.findByLabelText('Your UPI id'), 'not an id');
    fireEvent.press(screen.getByRole('button', { name: 'Save and share' }));
    expect(await screen.findByText("That doesn't look like a UPI id, such as name@bank.")).toBeTruthy();
    expect(setOwnUpiId).not.toHaveBeenCalled();
    expect(share).not.toHaveBeenCalled();
  });

  it('asks for the UPI id when the saved one cannot be read, rather than failing', async () => {
    preferences.mockRejectedValue(new Error('offline'));
    show();
    fireEvent.press(screen.getByRole('button', { name: 'ask' }));
    expect(await screen.findByLabelText('Your UPI id')).toBeTruthy();
  });
});
