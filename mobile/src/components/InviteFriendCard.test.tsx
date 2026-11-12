import { act, fireEvent, render, screen, waitFor } from '@testing-library/react-native';
import { Share } from 'react-native';
import { InviteFriendCard, REPORT_RETRY_MS, reportInviteEvent } from './InviteFriendCard';
import { invitePromptApi, referralsApi } from '../api/endpoints';
import { inviteMessage } from '../lib/inviteLink';
import { isSharing } from '../lib/appLock';

jest.mock('../api/endpoints', () => ({
  invitePromptApi: { check: jest.fn(), event: jest.fn() },
  referralsApi: { myCode: jest.fn() },
}));
jest.mock('../lib/useReduceTransparency', () => ({ useReduceTransparency: jest.fn(() => null) }));

const event = jest.mocked(invitePromptApi.event);
const myCode = jest.mocked(referralsApi.myCode);
const shareSpy = jest.spyOn(Share, 'share');

beforeEach(() => {
  jest.clearAllMocks();
  event.mockResolvedValue(undefined);
  myCode.mockResolvedValue({ code: 'AB12CD34' });
  shareSpy.mockResolvedValue({ action: Share.sharedAction, activityType: undefined });
});

describe('InviteFriendCard', () => {
  it('asks warmly, states the reward exactly, and reports it was shown once', async () => {
    render(<InviteFriendCard />);

    expect(await screen.findByText('Fynora sorted your statement.')).toBeOnTheScreen();
    expect(screen.getByText("Know someone who'd want this?")).toBeOnTheScreen();
    expect(screen.getByText('When 7 friends you invite start paying for Plus, you earn a month of Plus free.'))
      .toBeOnTheScreen();
    await waitFor(() => expect(event).toHaveBeenCalledWith('SHOWN'));
    expect(event).toHaveBeenCalledTimes(1);
  });

  it('renders nothing, and reports nothing, when the referral code cannot be read', async () => {
    myCode.mockRejectedValue(new Error('offline'));
    render(<InviteFriendCard />);
    await waitFor(() => expect(myCode).toHaveBeenCalled());
    await act(async () => {});
    expect(screen.queryByTestId('invite-friend-card')).not.toBeOnTheScreen();
    expect(event).not.toHaveBeenCalled();
  });

  it('opens the share sheet with the referral message, counted as a share in progress, and thanks them', async () => {
    let sharingDuringCall = false;
    shareSpy.mockImplementation(async () => {
      sharingDuringCall = isSharing();
      return { action: Share.sharedAction, activityType: undefined };
    });
    render(<InviteFriendCard />);

    fireEvent.press(await screen.findByText('Share'));

    await waitFor(() => expect(screen.getByText('Thanks for sharing Fynora.')).toBeOnTheScreen());
    expect(shareSpy).toHaveBeenCalledWith({ message: inviteMessage('AB12CD34') });
    expect(sharingDuringCall).toBe(true);
    expect(isSharing()).toBe(false);
    expect(event).toHaveBeenCalledWith('SHARE_TAPPED');
  });

  it('keeps the card when the share sheet is closed without sharing (iOS)', async () => {
    shareSpy.mockResolvedValue({ action: Share.dismissedAction, activityType: undefined });
    render(<InviteFriendCard />);

    fireEvent.press(await screen.findByText('Share'));

    await waitFor(() => expect(shareSpy).toHaveBeenCalled());
    await act(async () => {});
    expect(screen.getByText('Not now')).toBeOnTheScreen();
    expect(screen.queryByText('Thanks for sharing Fynora.')).not.toBeOnTheScreen();
  });

  it('keeps the card when the OS refuses to open the sheet', async () => {
    shareSpy.mockRejectedValue(new Error('no activity'));
    render(<InviteFriendCard />);

    fireEvent.press(await screen.findByText('Share'));

    await waitFor(() => expect(shareSpy).toHaveBeenCalled());
    await act(async () => {});
    expect(screen.getByText('Share')).toBeOnTheScreen();
  });

  it('Not now hides the card and reports it', async () => {
    render(<InviteFriendCard />);

    fireEvent.press(await screen.findByText('Not now'));

    expect(event).toHaveBeenCalledWith('DISMISSED');
    expect(screen.queryByTestId('invite-friend-card')).not.toBeOnTheScreen();
  });

  it('sends a failed report once more, a few seconds later, and then gives up', async () => {
    jest.useFakeTimers();
    try {
      event.mockRejectedValue(new Error('offline'));
      reportInviteEvent('DISMISSED');
      await act(async () => { await jest.advanceTimersByTimeAsync(0); });
      expect(event).toHaveBeenCalledTimes(1);
      await act(async () => { await jest.advanceTimersByTimeAsync(REPORT_RETRY_MS); });
      expect(event).toHaveBeenCalledTimes(2);
      expect(event).toHaveBeenLastCalledWith('DISMISSED');
      await act(async () => { await jest.advanceTimersByTimeAsync(REPORT_RETRY_MS * 10); });
      expect(event).toHaveBeenCalledTimes(2);
    } finally {
      jest.useRealTimers();
    }
  });

  it('does not resend a report that went through', async () => {
    jest.useFakeTimers();
    try {
      reportInviteEvent('SHOWN');
      await act(async () => { await jest.advanceTimersByTimeAsync(REPORT_RETRY_MS * 2); });
      expect(event).toHaveBeenCalledTimes(1);
    } finally {
      jest.useRealTimers();
    }
  });

  it('a report that throws before sending never throws out of it', () => {
    event.mockImplementation(() => { throw new Error('no client'); });
    expect(() => reportInviteEvent('SHOWN')).not.toThrow();
  });

  it('a failed report never breaks the card', async () => {
    event.mockRejectedValue(new Error('offline'));
    render(<InviteFriendCard />);
    fireEvent.press(await screen.findByText('Not now'));
    expect(screen.queryByTestId('invite-friend-card')).not.toBeOnTheScreen();
  });
});
