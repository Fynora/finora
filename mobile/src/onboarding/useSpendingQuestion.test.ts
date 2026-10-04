import { act, renderHook, waitFor } from '@testing-library/react-native';
import { onboardingApi } from '../api/endpoints';
import { useSpendingQuestion } from './useSpendingQuestion';

jest.mock('../api/endpoints', () => ({
  onboardingApi: { status: jest.fn(), setSpendingTracking: jest.fn() },
}));

const status = onboardingApi.status as jest.Mock;
const setSpendingTracking = onboardingApi.setSpendingTracking as jest.Mock;
const unanswered = { onboardingCompleted: true, financialFocus: [], spendingTrackingMethod: null };

beforeEach(() => {
  status.mockReset();
  setSpendingTracking.mockReset();
});

describe('useSpendingQuestion', () => {
  it('asks nothing and looks nothing up before the account is signed in and verified', () => {
    const { result } = renderHook(() => useSpendingQuestion(false, null));

    expect(result.current.needsAnswer).toBe(false);
    expect(result.current.pending).toBe(false);
    expect(status).not.toHaveBeenCalled();
  });

  it('is pending while it looks the answer up, then asks when there is none', async () => {
    let resolve!: (v: unknown) => void;
    status.mockReturnValue(new Promise((r) => { resolve = r; }));
    const { result } = renderHook(() => useSpendingQuestion(true, 'a@example.com'));

    expect(result.current.pending).toBe(true);
    expect(result.current.needsAnswer).toBe(false);
    await act(async () => resolve(unanswered));
    expect(result.current.pending).toBe(false);
    expect(result.current.needsAnswer).toBe(true);
  });

  it('does not ask an account that has answered', async () => {
    status.mockResolvedValue({ ...unanswered, spendingTrackingMethod: 'PAPER' });
    const { result } = renderHook(() => useSpendingQuestion(true, 'a@example.com'));

    await waitFor(() => expect(result.current.pending).toBe(false));
    expect(result.current.needsAnswer).toBe(false);
  });

  it('does not lock anyone out when the status cannot be loaded', async () => {
    status.mockRejectedValue(new Error('offline'));
    const { result } = renderHook(() => useSpendingQuestion(true, 'a@example.com'));

    await waitFor(() => expect(result.current.pending).toBe(false));
    expect(result.current.needsAnswer).toBe(false);
  });

  it('saves the answer and stops asking; a failed save keeps asking', async () => {
    status.mockResolvedValue(unanswered);
    const { result } = renderHook(() => useSpendingQuestion(true, 'a@example.com'));
    await waitFor(() => expect(result.current.needsAnswer).toBe(true));

    setSpendingTracking.mockRejectedValueOnce(new Error('offline'));
    await act(async () => {
      await expect(result.current.submit('SPREADSHEET')).rejects.toThrow('offline');
    });
    expect(result.current.needsAnswer).toBe(true);

    setSpendingTracking.mockResolvedValueOnce({ ...unanswered, spendingTrackingMethod: 'SPREADSHEET' });
    await act(async () => {
      await result.current.submit('SPREADSHEET');
    });
    expect(setSpendingTracking).toHaveBeenLastCalledWith('SPREADSHEET');
    expect(result.current.needsAnswer).toBe(false);
  });

  it('looks the answer up afresh for a different account', async () => {
    status.mockResolvedValue({ ...unanswered, spendingTrackingMethod: 'PAPER' });
    const { result, rerender } = renderHook(({ key }: { key: string }) => useSpendingQuestion(true, key), {
      initialProps: { key: 'a@example.com' },
    });
    await waitFor(() => expect(result.current.pending).toBe(false));

    status.mockResolvedValue(unanswered);
    rerender({ key: 'b@example.com' });

    await waitFor(() => expect(result.current.needsAnswer).toBe(true));
    expect(status).toHaveBeenCalledTimes(2);
  });
});
