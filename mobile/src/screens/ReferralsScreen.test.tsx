import { act, fireEvent, render, screen } from '@testing-library/react-native';
import { Linking, Platform, Share } from 'react-native';
import { QueryClient, QueryClientProvider } from '@tanstack/react-query';
import * as Clipboard from 'expo-clipboard';
import { AxiosError, AxiosHeaders } from 'axios';
import { ReferralsScreen } from './ReferralsScreen';
import { referralsApi } from '../api/endpoints';

function axiosErrorWithResponse(status: number, data: unknown): AxiosError {
  const err = new AxiosError('Request failed');
  err.response = {
    status,
    data,
    statusText: '',
    headers: new AxiosHeaders(),
    config: { headers: new AxiosHeaders() },
  } as AxiosError['response'];
  return err;
}

jest.mock('../api/endpoints', () => ({
  referralsApi: { myCode: jest.fn(), mine: jest.fn(), redeem: jest.fn() },
}));

jest.mock('expo-clipboard', () => ({
  setStringAsync: jest.fn().mockResolvedValue(true),
}));

const api = referralsApi as jest.Mocked<typeof referralsApi>;
const clipboard = Clipboard as jest.Mocked<typeof Clipboard>;

const openURL = jest.spyOn(Linking, 'openURL').mockResolvedValue(undefined);
const shareSpy = jest.spyOn(Share, 'share').mockResolvedValue({ action: Share.sharedAction });

function renderScreen() {
  const queryClient = new QueryClient({ defaultOptions: { queries: { retry: false, gcTime: 0 } } });
  return render(
    <QueryClientProvider client={queryClient}>
      <ReferralsScreen />
    </QueryClientProvider>
  );
}

async function settle() {
  await act(async () => {});
}

describe('ReferralsScreen', () => {
  beforeEach(() => {
    api.mine.mockReset();
    clipboard.setStringAsync.mockClear();
    openURL.mockClear();
    openURL.mockResolvedValue(undefined);
    shareSpy.mockClear();
  });

  it('shows the code, a zero count, and a zero earned amount for a user with no referrals yet', async () => {
    api.mine.mockResolvedValue({
      code: 'ABCD1234', referrals: [], walletBalance: 0, referralCount: 0,
      plusMilestoneCounter: 0, premiumMilestoneCounter: 0, grants: [],
    });
    renderScreen();

    expect(await screen.findByText('ABCD1234')).toBeTruthy();
    expect(screen.getByLabelText('Friends Referred: 0')).toBeTruthy();
    expect(screen.getByLabelText('Pending: 0')).toBeTruthy();
    expect(screen.getByLabelText('Earned: ₹0')).toBeTruthy();
    expect(screen.getByText('No referrals yet')).toBeTruthy();
  });

  it('shows the real referral count, pending count, and earned amount once they load', async () => {
    api.mine.mockResolvedValue({
      code: 'ABCD1234',
      referrals: [
        { referralId: 'r1', referredUserFullName: 'Jane', status: 'SUBSCRIBED', reward: null, createdAt: '2026-09-01T00:00:00Z' },
        { referralId: 'r2', referredUserFullName: 'Jo', status: 'REWARDED', reward: 250, createdAt: '2026-08-20T00:00:00Z' },
      ],
      walletBalance: 250,
      referralCount: 2,
      plusMilestoneCounter: 0,
      premiumMilestoneCounter: 0,
      grants: [],
    });
    renderScreen();

    expect(await screen.findByText('2')).toBeTruthy();
    expect(screen.getByText('₹250')).toBeTruthy();
    expect(screen.getByText('1')).toBeTruthy(); // pending count: the one SUBSCRIBED-not-yet-REWARDED row
    expect(screen.getByText('Jane')).toBeTruthy();
    expect(screen.getByText('Subscribed')).toBeTruthy();
    expect(screen.getByText('Jo')).toBeTruthy();
    expect(screen.getByText('Rewarded')).toBeTruthy();
    expect(screen.getByText(/Earned ₹250/)).toBeTruthy();
  });

  it('copies the code to the clipboard and shows a transient "Copied" confirmation', async () => {
    api.mine.mockResolvedValue({
      code: 'ABCD1234', referrals: [], walletBalance: 0, referralCount: 0,
      plusMilestoneCounter: 0, premiumMilestoneCounter: 0, grants: [],
    });
    renderScreen();
    await screen.findByText('ABCD1234');

    fireEvent.press(screen.getByLabelText('Copy referral code'));
    await settle();

    expect(clipboard.setStringAsync).toHaveBeenCalledWith('ABCD1234');
    expect(await screen.findByLabelText('Copied')).toBeTruthy();
  });

  it('shows an error state with a retry action when the code fails to load', async () => {
    api.mine.mockRejectedValue(new Error('network down'));
    renderScreen();

    expect(await screen.findByText("Couldn't load your referral code.")).toBeTruthy();
  });

  // No canOpenURL pre-check, deliberately (see ReferralsScreen's own doc comment on CHANNELS):
  // canOpenURL is unreliable for whatsapp:// without native config this app doesn't declare, so
  // the real behavior is attempt-then-catch. These two tests exercise exactly that, via openURL
  // resolving vs. rejecting -- not a canOpenURL mock, which would test the wrong mechanism.
  it('opens WhatsApp with the code pre-filled when it resolves', async () => {
    api.mine.mockResolvedValue({
      code: 'ABCD1234', referrals: [], walletBalance: 0, referralCount: 0,
      plusMilestoneCounter: 0, premiumMilestoneCounter: 0, grants: [],
    });
    renderScreen();
    await screen.findByText('ABCD1234');

    fireEvent.press(screen.getByLabelText('Share via WhatsApp'));
    await settle();

    expect(openURL).toHaveBeenCalledWith(expect.stringContaining('whatsapp://send?text='));
    expect(openURL.mock.calls[0][0]).toContain(encodeURIComponent('ABCD1234'));
    expect(shareSpy).not.toHaveBeenCalled();
  });

  it('falls back to the OS share sheet when opening WhatsApp rejects (not installed)', async () => {
    api.mine.mockResolvedValue({
      code: 'ABCD1234', referrals: [], walletBalance: 0, referralCount: 0,
      plusMilestoneCounter: 0, premiumMilestoneCounter: 0, grants: [],
    });
    openURL.mockRejectedValueOnce(new Error('No app handles whatsapp://'));
    renderScreen();
    await screen.findByText('ABCD1234');

    fireEvent.press(screen.getByLabelText('Share via WhatsApp'));
    await settle();

    expect(shareSpy).toHaveBeenCalledWith(expect.objectContaining({ message: expect.stringContaining('ABCD1234') }));
  });

  it('opens the iOS-style SMS composer URL on iOS', async () => {
    api.mine.mockResolvedValue({
      code: 'ABCD1234', referrals: [], walletBalance: 0, referralCount: 0,
      plusMilestoneCounter: 0, premiumMilestoneCounter: 0, grants: [],
    });
    renderScreen();
    await screen.findByText('ABCD1234');

    fireEvent.press(screen.getByLabelText('Share via Messages'));
    await settle();
    fireEvent.press(screen.getByLabelText('Share via Email'));
    await settle();

    expect(openURL).toHaveBeenCalledWith(expect.stringMatching(/^sms:&body=/));
    expect(openURL).toHaveBeenCalledWith(expect.stringMatching(/^mailto:/));
  });

  // Platform.OS is 'ios' throughout this suite (jest-expo's default) -- this is the only test
  // that exercises the Android branch of the sms: URL ternary, which is otherwise completely
  // untested (a bug flagged and fixed in review: an assertion of `/^sms:/` alone passes
  // identically for either branch, silently hiding a broken Android URL).
  it('opens the Android-style SMS composer URL on Android', async () => {
    const originalOS = Platform.OS;
    // A plain writable string at runtime (see react-native/Libraries/Utilities/Platform.ios.js) --
    // this is the standard way to exercise a Platform.OS branch in RN tests, restored below.
    Platform.OS = 'android';
    try {
      api.mine.mockResolvedValue({
      code: 'ABCD1234', referrals: [], walletBalance: 0, referralCount: 0,
      plusMilestoneCounter: 0, premiumMilestoneCounter: 0, grants: [],
    });
      renderScreen();
      await screen.findByText('ABCD1234');

      fireEvent.press(screen.getByLabelText('Share via Messages'));
      await settle();

      expect(openURL).toHaveBeenCalledWith(expect.stringMatching(/^sms:\?body=/));
    } finally {
      Platform.OS = originalOS;
    }
  });

  it('opens the OS share sheet from "More"', async () => {
    api.mine.mockResolvedValue({
      code: 'ABCD1234', referrals: [], walletBalance: 0, referralCount: 0,
      plusMilestoneCounter: 0, premiumMilestoneCounter: 0, grants: [],
    });
    renderScreen();
    await screen.findByText('ABCD1234');

    fireEvent.press(screen.getByLabelText('More share options'));
    await settle();

    expect(shareSpy).toHaveBeenCalledWith(expect.objectContaining({ message: expect.stringContaining('ABCD1234') }));
  });

  // Phase 5: the share message now also carries a "finora://register?ref=CODE" deep link
  // (useReferralDeepLink.ts consumes it), alongside -- not instead of -- the bare code, since the
  // bare code is the only part that works for someone without the app installed yet.
  it('includes both the bare code and the finora:// deep link in the share message', async () => {
    api.mine.mockResolvedValue({
      code: 'ABCD1234', referrals: [], walletBalance: 0, referralCount: 0,
      plusMilestoneCounter: 0, premiumMilestoneCounter: 0, grants: [],
    });
    renderScreen();
    await screen.findByText('ABCD1234');

    fireEvent.press(screen.getByLabelText('Share referral code'));
    await settle();

    expect(shareSpy).toHaveBeenCalledWith(expect.objectContaining({
      message: expect.stringMatching(/\bABCD1234\b.*finora:\/\/register\?ref=ABCD1234/),
    }));
  });

  describe('milestone redemption', () => {
    it('shows a persistent progress readout toward Plus below the threshold', async () => {
      api.mine.mockResolvedValue({
        code: 'ABCD1234', referrals: [], walletBalance: 0, referralCount: 0,
        plusMilestoneCounter: 2, premiumMilestoneCounter: 2, grants: [],
      });
      renderScreen();

      expect(await screen.findByText(/2\s*\/\s*3/)).toBeTruthy();
      expect(screen.queryByText(/redeem plus/i)).toBeNull();
    });

    it('shows both redeem rows simultaneously once both thresholds are reached', async () => {
      api.mine.mockResolvedValue({
        code: 'ABCD1234', referrals: [], walletBalance: 0, referralCount: 0,
        plusMilestoneCounter: 4, premiumMilestoneCounter: 7, grants: [],
      });
      renderScreen();

      expect(await screen.findByText(/redeem plus/i)).toBeTruthy();
      expect(await screen.findByText(/redeem premium/i)).toBeTruthy();
    });

    it('calls referralsApi.redeem with the right tier on press', async () => {
      api.mine.mockResolvedValue({
        code: 'ABCD1234', referrals: [], walletBalance: 0, referralCount: 0,
        plusMilestoneCounter: 3, premiumMilestoneCounter: 7, grants: [],
      });
      api.redeem.mockResolvedValue(undefined);
      renderScreen();

      const button = await screen.findByText(/redeem premium/i);
      fireEvent.press(button);
      await settle();

      expect(api.redeem).toHaveBeenCalledWith('PREMIUM');
    });

    it('shows the server error message under the right tier when redemption fails', async () => {
      api.mine.mockResolvedValue({
        code: 'ABCD1234', referrals: [], walletBalance: 0, referralCount: 0,
        plusMilestoneCounter: 3, premiumMilestoneCounter: 7, grants: [],
      });
      api.redeem.mockRejectedValue(
        axiosErrorWithResponse(409, { message: 'This reward was just redeemed by another request.' })
      );
      renderScreen();

      const button = await screen.findByText(/redeem plus/i);
      fireEvent.press(button);
      await settle();

      expect(await screen.findByText('This reward was just redeemed by another request.')).toBeTruthy();
    });
  });

  describe('your rewards (grant status)', () => {
    it('shows an ACTIVE grant with its expiry date', async () => {
      api.mine.mockResolvedValue({
        code: 'ABCD1234', referrals: [], walletBalance: 0, referralCount: 0,
        plusMilestoneCounter: 0, premiumMilestoneCounter: 0,
        grants: [{ id: 'grant-1', tier: 'PREMIUM', status: 'ACTIVE', activatedAt: '2026-09-14T00:00:00Z', expiresAt: '2026-10-14T00:00:00Z' }],
      });
      renderScreen();

      expect(await screen.findByText(/Premium active/i)).toBeTruthy();
    });

    it('shows a PENDING grant as queued', async () => {
      api.mine.mockResolvedValue({
        code: 'ABCD1234', referrals: [], walletBalance: 0, referralCount: 0,
        plusMilestoneCounter: 0, premiumMilestoneCounter: 0,
        grants: [{ id: 'grant-1', tier: 'PLUS', status: 'PENDING', activatedAt: null, expiresAt: null }],
      });
      renderScreen();

      expect(await screen.findByText(/Plus queued/i)).toBeTruthy();
    });
  });

  describe('upgrade celebration', () => {
    it('shows the celebration once for a newly-active grant it has not shown before', async () => {
      api.mine.mockResolvedValue({
        code: 'ABCD1234', referrals: [], walletBalance: 0, referralCount: 0,
        plusMilestoneCounter: 0, premiumMilestoneCounter: 0,
        grants: [{ id: 'grant-1', tier: 'PREMIUM', status: 'ACTIVE', activatedAt: '2026-09-14T00:00:00Z', expiresAt: '2026-10-14T00:00:00Z' }],
      });
      renderScreen();
      await settle();

      expect(await screen.findByTestId('upgrade-celebration')).toBeTruthy();
    });

    it('does not show the celebration again for a grant already recorded as seen', async () => {
      const SecureStore = require('expo-secure-store');
      await SecureStore.setItemAsync('finora_seen_active_referral_grants', JSON.stringify(['grant-1']));
      api.mine.mockResolvedValue({
        code: 'ABCD1234', referrals: [], walletBalance: 0, referralCount: 0,
        plusMilestoneCounter: 0, premiumMilestoneCounter: 0,
        grants: [{ id: 'grant-1', tier: 'PREMIUM', status: 'ACTIVE', activatedAt: '2026-09-14T00:00:00Z', expiresAt: '2026-10-14T00:00:00Z' }],
      });
      renderScreen();
      await screen.findByText('ABCD1234');
      await settle();

      expect(screen.queryByTestId('upgrade-celebration')).toBeNull();
    });
  });
});
