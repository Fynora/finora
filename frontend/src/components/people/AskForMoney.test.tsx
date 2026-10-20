import { describe, it, expect, vi, beforeEach, afterEach } from 'vitest';
import { render, screen, waitFor } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { QueryClient, QueryClientProvider } from '@tanstack/react-query';
import { peopleApi } from '../../api/endpoints';
import { useAskForMoney } from './AskForMoney';

// Every id and amount here is invented.
vi.mock('../../api/endpoints', () => ({
  peopleApi: { preferences: vi.fn(), setOwnUpiId: vi.fn() },
}));
const preferences = vi.mocked(peopleApi.preferences);
const setOwnUpiId = vi.mocked(peopleApi.setOwnUpiId);
const URL = 'upi://pay?pa=user.sample@okbank&am=500.00&cu=INR';

function Host() {
  const { ask, prompt } = useAskForMoney();
  return (
    <>
      <button type="button" onClick={() => ask({ label: 'Friend K', owed: 500 })}>ask</button>
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

let share: ReturnType<typeof vi.fn> | undefined;
let writeText: ReturnType<typeof vi.fn>;

beforeEach(() => {
  preferences.mockReset();
  setOwnUpiId.mockReset().mockImplementation(async (ownUpiId: string) => ({ ownUpiId }));
  share = vi.fn().mockResolvedValue(undefined);
  writeText = vi.fn().mockResolvedValue(undefined);
  Object.defineProperty(navigator, 'share', { configurable: true, value: share });
  Object.defineProperty(navigator, 'clipboard', { configurable: true, value: { writeText } });
});

afterEach(() => {
  Object.defineProperty(navigator, 'share', { configurable: true, value: undefined });
});

describe('useAskForMoney', () => {
  it('opens the browser\'s share sheet at once when the UPI id is already saved, and sends nothing itself', async () => {
    preferences.mockResolvedValue({ ownUpiId: 'user.sample@okbank' });
    show();
    await userEvent.click(screen.getByRole('button', { name: 'ask' }));
    await waitFor(() => expect(share).toHaveBeenCalledWith({ text: URL }));
    expect(writeText).not.toHaveBeenCalled();
    expect(setOwnUpiId).not.toHaveBeenCalled();
  });

  it('copies the request where the browser has no share sheet, and says so', async () => {
    Object.defineProperty(navigator, 'share', { configurable: true, value: undefined });
    preferences.mockResolvedValue({ ownUpiId: 'user.sample@okbank' });
    show();
    await userEvent.click(screen.getByRole('button', { name: 'ask' }));
    await waitFor(() => expect(writeText).toHaveBeenCalledWith(URL));
    expect(await screen.findByText('Copied the UPI request. Send it to Friend K yourself.')).toBeInTheDocument();
  });

  it('asks once for the UPI id, saves it, then shares', async () => {
    preferences.mockResolvedValue({ ownUpiId: null });
    show();
    await userEvent.click(screen.getByRole('button', { name: 'ask' }));
    const input = await screen.findByRole('textbox', { name: 'Your UPI id' });
    expect(share).not.toHaveBeenCalled();
    await userEvent.type(input, ' user.sample@okbank ');
    await userEvent.click(screen.getByRole('button', { name: 'Save and share' }));
    await waitFor(() => expect(setOwnUpiId).toHaveBeenCalledWith('user.sample@okbank'));
    await waitFor(() => expect(share).toHaveBeenCalledWith({ text: URL }));
    await waitFor(() => expect(screen.queryByRole('textbox', { name: 'Your UPI id' })).toBeNull());
  });

  it('refuses what is not a UPI id, and saves nothing', async () => {
    preferences.mockResolvedValue({ ownUpiId: null });
    show();
    await userEvent.click(screen.getByRole('button', { name: 'ask' }));
    await userEvent.type(await screen.findByRole('textbox', { name: 'Your UPI id' }), 'not an id');
    await userEvent.click(screen.getByRole('button', { name: 'Save and share' }));
    expect(await screen.findByText("That doesn't look like a UPI id, such as name@bank.")).toBeInTheDocument();
    expect(setOwnUpiId).not.toHaveBeenCalled();
    expect(share).not.toHaveBeenCalled();
  });

  it('asks for the UPI id when the saved one cannot be read, rather than failing', async () => {
    preferences.mockRejectedValue(new Error('offline'));
    show();
    await userEvent.click(screen.getByRole('button', { name: 'ask' }));
    expect(await screen.findByRole('textbox', { name: 'Your UPI id' })).toBeInTheDocument();
  });
});
