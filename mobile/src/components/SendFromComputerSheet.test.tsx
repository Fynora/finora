import { act, fireEvent, render, screen } from '@testing-library/react-native';
import { SendFromComputerSheet, POLL_MS, RECEIVED_DWELL_MS } from './SendFromComputerSheet';
import { sendCodesApi } from '../api/endpoints';

jest.mock('../api/endpoints', () => ({
  sendCodesApi: { issue: jest.fn(), current: jest.fn() },
}));
// The real hook resolves once per test process and remounts the glass surface mid-test.
jest.mock('../lib/useReduceTransparency', () => ({ useReduceTransparency: jest.fn(() => null) }));

const api = sendCodesApi as jest.Mocked<typeof sendCodesApi>;
const onClose = jest.fn();
const onReceived = jest.fn();

async function settle() {
  await act(async () => {});
}

async function advance(ms: number) {
  await act(async () => {
    jest.advanceTimersByTime(ms);
  });
  await settle();
}

function renderSheet() {
  return render(<SendFromComputerSheet onClose={onClose} onReceived={onReceived} />);
}

beforeEach(() => {
  jest.useFakeTimers();
  onClose.mockReset();
  onReceived.mockReset();
  api.issue.mockReset().mockResolvedValue({ code: 'AB3K7M', expiresAt: '2031-05-03T05:10:00Z' });
  api.current.mockReset().mockResolvedValue({ state: 'WAITING', importJobId: null });
});

afterEach(() => {
  jest.useRealTimers();
});

describe('SendFromComputerSheet (Gate 2b spec §7)', () => {
  it('showsCodeAndInstructions', async () => {
    renderSheet();
    await settle();

    expect(screen.getByText('AB3K7M')).toBeOnTheScreen();
    expect(screen.getByText(
      'Open app.fynora.net/send on your computer and type this code. It works once, for 10 minutes.')).toBeOnTheScreen();
    expect(screen.getByText('Waiting for your file…')).toBeOnTheScreen();
  });

  it('pollsUntilReceived', async () => {
    renderSheet();
    await settle();
    await advance(POLL_MS);
    expect(api.current).toHaveBeenCalledTimes(1);

    api.current.mockResolvedValue({ state: 'RECEIVED', importJobId: 'job-1' });
    await advance(POLL_MS);
    expect(screen.getByText('Received.')).toBeOnTheScreen();

    await advance(POLL_MS * 3);
    expect(api.current).toHaveBeenCalledTimes(2);

    await advance(RECEIVED_DWELL_MS);
    expect(onReceived).toHaveBeenCalledTimes(1);
    // The job, so the import screen can follow it to the review (Sid, 2026-10-25).
    expect(onReceived).toHaveBeenCalledWith('job-1');
  });

  it('stopsPollingWhenClosed', async () => {
    const { unmount } = renderSheet();
    await settle();
    await advance(POLL_MS);
    expect(api.current).toHaveBeenCalledTimes(1);

    unmount();
    await advance(POLL_MS * 5);

    expect(api.current).toHaveBeenCalledTimes(1);
  });

  it('expiredOffersNewCode', async () => {
    renderSheet();
    await settle();
    api.current.mockResolvedValue({ state: 'EXPIRED', importJobId: null });
    await advance(POLL_MS);
    expect(screen.getByText('This code has expired.')).toBeOnTheScreen();
    await advance(POLL_MS * 3);
    expect(api.current).toHaveBeenCalledTimes(1);

    api.issue.mockResolvedValue({ code: 'QW8RT2', expiresAt: '2031-05-03T05:20:00Z' });
    api.current.mockResolvedValue({ state: 'WAITING', importJobId: null });
    fireEvent.press(screen.getByText('Get a new code'));
    await settle();

    expect(api.issue).toHaveBeenCalledTimes(2);
    expect(screen.getByText('QW8RT2')).toBeOnTheScreen();
    expect(screen.getByText('Waiting for your file…')).toBeOnTheScreen();
  });

  it('treatsAStatusAboutAnotherCodeAsThisCodeGone', async () => {
    // A sheet closed and reopened mid-request: the code asked for later replaced this one.
    api.issue.mockResolvedValue({ id: 'code-b', code: 'AB3K7M', expiresAt: '2031-05-03T05:10:00Z' });
    api.current.mockResolvedValue({ state: 'WAITING', importJobId: null, codeId: 'code-a' });
    renderSheet();
    await settle();

    await advance(POLL_MS);

    expect(screen.getByText('This code has expired.')).toBeOnTheScreen();
    expect(screen.getByText('Get a new code')).toBeOnTheScreen();
    await advance(POLL_MS * 3);
    expect(api.current).toHaveBeenCalledTimes(1);
  });

  it('keepsWaitingWhileTheStatusIsAboutThisCode', async () => {
    api.issue.mockResolvedValue({ id: 'code-b', code: 'AB3K7M', expiresAt: '2031-05-03T05:10:00Z' });
    api.current.mockResolvedValue({ state: 'WAITING', importJobId: null, codeId: 'code-b' });
    renderSheet();
    await settle();

    await advance(POLL_MS);

    expect(screen.getByText('Waiting for your file…')).toBeOnTheScreen();
    api.current.mockResolvedValue({ state: 'RECEIVED', importJobId: 'job-9', codeId: 'code-b' });
    await advance(POLL_MS);
    expect(screen.getByText('Received.')).toBeOnTheScreen();
  });

  it('says to wait when the server refuses more codes', async () => {
    api.issue.mockRejectedValue(Object.assign(new Error('Too Many Requests'), { response: { status: 429 } }));
    renderSheet();
    await settle();

    expect(screen.getByText("You've asked for a lot of codes. Wait a few minutes, then try again.")).toBeOnTheScreen();
    expect(screen.queryByText("Couldn't get a code. Check your connection and try again.")).not.toBeOnTheScreen();
  });

  it('says so when a code cannot be issued, and offers to try again', async () => {
    api.issue.mockRejectedValue(new Error('Network Error'));
    renderSheet();
    await settle();

    expect(screen.getByText("Couldn't get a code. Check your connection and try again.")).toBeOnTheScreen();
    expect(api.current).not.toHaveBeenCalled();

    api.issue.mockResolvedValue({ code: 'AB3K7M', expiresAt: '2031-05-03T05:10:00Z' });
    fireEvent.press(screen.getByText('Try again'));
    await settle();
    expect(screen.getByText('AB3K7M')).toBeOnTheScreen();
  });

  // Closing during the "Received." pause must not strand the file: the screen still follows it.
  it('follows the received file even when closed during the pause', async () => {
    renderSheet();
    await settle();
    api.current.mockResolvedValue({ state: 'RECEIVED', importJobId: 'job-2' });
    await advance(POLL_MS);
    expect(screen.getByText('Received.')).toBeOnTheScreen();

    fireEvent.press(screen.getByText('Close'));

    expect(onReceived).toHaveBeenCalledWith('job-2');
    expect(onClose).not.toHaveBeenCalled();
  });

  it('closes when Close is pressed', async () => {
    renderSheet();
    await settle();

    fireEvent.press(screen.getByText('Close'));

    expect(onClose).toHaveBeenCalledTimes(1);
  });
});
