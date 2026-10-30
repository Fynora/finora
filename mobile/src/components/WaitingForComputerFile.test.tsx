import { act, fireEvent, render, screen } from '@testing-library/react-native';
import { WaitingForComputerFile } from './WaitingForComputerFile';
import { POLL_MS } from './SendFromComputerSheet';
import { sendCodesApi } from '../api/endpoints';

jest.mock('../api/endpoints', () => ({
  sendCodesApi: { issue: jest.fn(), current: jest.fn() },
}));
jest.mock('../lib/useReduceTransparency', () => ({ useReduceTransparency: jest.fn(() => null) }));

const api = sendCodesApi as jest.Mocked<typeof sendCodesApi>;
const onReceived = jest.fn();
const onGone = jest.fn();

async function advance(ms: number) {
  await act(async () => {
    jest.advanceTimersByTime(ms);
  });
  await act(async () => {});
}

function renderCard(props: { code?: string; codeId?: string } = {}) {
  return render(<WaitingForComputerFile {...props} onReceived={onReceived} onGone={onGone} />);
}

beforeEach(() => {
  jest.useFakeTimers();
  onReceived.mockReset();
  onGone.mockReset();
  api.current.mockReset().mockResolvedValue({ state: 'WAITING', importJobId: null });
});

afterEach(() => {
  jest.useRealTimers();
});

describe('WaitingForComputerFile', () => {
  it('shows the code when it is known', () => {
    renderCard({ code: 'AB3K7M' });

    expect(screen.getByText(
      'On your computer, open app.fynora.net/send and type AB3K7M. The review opens here when your file arrives.',
    )).toBeOnTheScreen();
  });

  it('says where to send from when the code is not known', () => {
    renderCard();

    expect(screen.getByText(
      'Send your statement from app.fynora.net/send on your computer. The review opens here when it arrives.',
    )).toBeOnTheScreen();
  });

  it('keeps asking while the code is waiting, then follows the file', async () => {
    renderCard({ codeId: 'code-b' });
    await advance(POLL_MS);
    await advance(POLL_MS);
    expect(api.current).toHaveBeenCalledTimes(2);
    expect(onReceived).not.toHaveBeenCalled();

    api.current.mockResolvedValue({ state: 'RECEIVED', importJobId: 'job-3', codeId: 'code-b' });
    await advance(POLL_MS);

    expect(onReceived).toHaveBeenCalledWith('job-3');
    await advance(POLL_MS * 3);
    expect(api.current).toHaveBeenCalledTimes(3);
  });

  it.each(['EXPIRED', 'NONE'] as const)('gives up when the code is %s', async (state) => {
    renderCard();
    api.current.mockResolvedValue({ state, importJobId: null });
    await advance(POLL_MS);

    expect(onGone).toHaveBeenCalledTimes(1);
    expect(onReceived).not.toHaveBeenCalled();
    await advance(POLL_MS * 3);
    expect(api.current).toHaveBeenCalledTimes(1);
  });

  // A newer code replaced this one (the sheet was opened again elsewhere): its file is not ours.
  it('gives up when the status is about another code', async () => {
    renderCard({ codeId: 'code-a' });
    api.current.mockResolvedValue({ state: 'RECEIVED', importJobId: 'job-9', codeId: 'code-b' });
    await advance(POLL_MS);

    expect(onGone).toHaveBeenCalledTimes(1);
    expect(onReceived).not.toHaveBeenCalled();
  });

  it('asks again after a failed check', async () => {
    api.current.mockRejectedValueOnce(new Error('Network Error'));
    renderCard();
    await advance(POLL_MS);
    expect(onGone).not.toHaveBeenCalled();

    api.current.mockResolvedValue({ state: 'RECEIVED', importJobId: 'job-4' });
    await advance(POLL_MS);
    expect(onReceived).toHaveBeenCalledWith('job-4');
  });

  it('stops when asked', () => {
    renderCard();

    fireEvent.press(screen.getByText('Stop waiting'));

    expect(onGone).toHaveBeenCalledTimes(1);
  });

  it('stops asking once unmounted', async () => {
    const { unmount } = renderCard();
    await advance(POLL_MS);
    expect(api.current).toHaveBeenCalledTimes(1);

    unmount();
    await advance(POLL_MS * 5);

    expect(api.current).toHaveBeenCalledTimes(1);
  });
});
