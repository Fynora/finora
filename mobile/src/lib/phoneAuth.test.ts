import { getAuth, signInWithPhoneNumber, signOut } from '@react-native-firebase/auth';
import {
  PHONE_SEND_TIMEOUT_CODE, PHONE_SEND_TIMEOUT_MS, confirmPhoneVerificationCode,
  phoneAuthDiagnostics, sendPhoneVerificationCode,
} from './phoneAuth';
import { toUserMessage } from './apiError';
import { reportHandledEvent } from './monitoring';

jest.mock('./monitoring', () => ({
  reportHandledError: jest.fn(),
  reportHandledEvent: jest.fn(),
}));

const nativeSend = signInWithPhoneNumber as jest.Mock;

describe('sendPhoneVerificationCode', () => {
  beforeEach(() => {
    jest.useFakeTimers();
    nativeSend.mockReset();
  });

  afterEach(() => {
    jest.useRealTimers();
  });

  it('resolves with the confirmation handle when Firebase answers in time', async () => {
    const confirmation = { confirm: jest.fn() };
    nativeSend.mockResolvedValue(confirmation);

    await expect(sendPhoneVerificationCode('+919876543210')).resolves.toBe(confirmation); // synthetic-ok: invented test number
    expect(jest.getTimerCount()).toBe(0);
  });

  it('rejects with the timeout code when Firebase never answers', async () => {
    nativeSend.mockReturnValue(new Promise(() => {}));

    const pending = sendPhoneVerificationCode('+919876543210'); // synthetic-ok: invented test number
    const assertion = expect(pending).rejects.toMatchObject({ code: PHONE_SEND_TIMEOUT_CODE });
    await jest.advanceTimersByTimeAsync(PHONE_SEND_TIMEOUT_MS);

    await assertion;
  });

  it('does not time out one millisecond before the limit', async () => {
    let resolveNative!: (v: unknown) => void;
    nativeSend.mockReturnValue(new Promise((r) => { resolveNative = r; }));

    const pending = sendPhoneVerificationCode('+919876543210'); // synthetic-ok: invented test number
    let settled = false;
    pending.then(() => { settled = true; }, () => { settled = true; });
    await jest.advanceTimersByTimeAsync(PHONE_SEND_TIMEOUT_MS - 1);
    expect(settled).toBe(false);

    resolveNative({ confirm: jest.fn() });
    await pending;
  });

  it("passes Firebase's own rejection through unchanged, not as a timeout", async () => {
    const firebaseError = Object.assign(new Error('blocked'), { code: 'auth/too-many-requests' });
    nativeSend.mockRejectedValue(firebaseError);

    await expect(sendPhoneVerificationCode('+919876543210')).rejects.toBe(firebaseError); // synthetic-ok: invented test number
    expect(jest.getTimerCount()).toBe(0);
  });

  it('ignores a late answer after it has already timed out', async () => {
    let resolveNative!: (v: unknown) => void;
    nativeSend.mockReturnValue(new Promise((r) => { resolveNative = r; }));

    const pending = sendPhoneVerificationCode('+919876543210', 1000); // synthetic-ok: invented test number
    const assertion = expect(pending).rejects.toMatchObject({ code: PHONE_SEND_TIMEOUT_CODE });
    await jest.advanceTimersByTimeAsync(1000);
    await assertion;

    resolveNative({ confirm: jest.fn() });
    await jest.advanceTimersByTimeAsync(0);
  });
});

describe('toUserMessage for the send timeout', () => {
  it('explains the wait in plain words instead of the generic fallback', () => {
    const err = Object.assign(new Error('timeout'), { code: PHONE_SEND_TIMEOUT_CODE });

    expect(toUserMessage(err, 'Could not send a verification code right now.'))
      .toBe('Sending the code is taking too long. Check your connection and try again.');
  });
});

/*
 * The diagnostics are module state that outlives each test, so every case below starts with a
 * send of its own and asserts only what that send changed -- counts are compared as deltas.
 */
describe('phoneAuthDiagnostics', () => {
  const NUMBER = '+919876543210'; // synthetic-ok: invented test number

  beforeEach(() => {
    jest.useFakeTimers();
    jest.setSystemTime(new Date('2026-01-01T00:00:00Z'));
    nativeSend.mockReset();
    (getAuth as jest.Mock).mockReturnValue({});
  });

  afterEach(() => {
    jest.useRealTimers();
  });

  it('reports how long a send took and how long ago it finished', async () => {
    const before = phoneAuthDiagnostics().sendsThisSession as number;
    const outstandingBefore = phoneAuthDiagnostics().nativeSendsOutstanding;
    nativeSend.mockImplementation(() => new Promise((r) => setTimeout(() => r({ confirm: jest.fn() }), 4300)));

    const pending = sendPhoneVerificationCode(NUMBER);
    await jest.advanceTimersByTimeAsync(4300);
    await pending;
    await jest.advanceTimersByTimeAsync(12000);

    expect(phoneAuthDiagnostics()).toMatchObject({
      sendsThisSession: before + 1,
      nativeSendsOutstanding: outstandingBefore,
      lastSendOutcome: 'answered',
      lastSendSeconds: 4.3,
      secondsSinceLastSendEnded: 12,
      lastSendLateAnswerSeconds: null,
      confirmAttemptsSinceLastSend: 0,
    });
  });

  it('shows a send that is still running, with how long it has been going', async () => {
    const outstandingBefore = phoneAuthDiagnostics().nativeSendsOutstanding as number;
    nativeSend.mockReturnValue(new Promise(() => {}));

    const pending = sendPhoneVerificationCode(NUMBER);
    pending.catch(() => {});
    await jest.advanceTimersByTimeAsync(25000);

    expect(phoneAuthDiagnostics()).toMatchObject({
      nativeSendsOutstanding: outstandingBefore + 1,
      lastSendOutcome: 'pending',
      lastSendSeconds: 25,
      secondsSinceLastSendEnded: null,
    });

    await jest.advanceTimersByTimeAsync(PHONE_SEND_TIMEOUT_MS);
  });

  it('records a timeout, then how long Firebase really took if it answers late', async () => {
    let resolveNative!: (v: unknown) => void;
    nativeSend.mockReturnValue(new Promise((r) => { resolveNative = r; }));

    const pending = sendPhoneVerificationCode(NUMBER, 1000);
    pending.catch(() => {});
    await jest.advanceTimersByTimeAsync(1000);
    expect(phoneAuthDiagnostics()).toMatchObject({
      lastSendOutcome: 'timed-out',
      lastSendSeconds: 1,
      lastSendLateAnswerSeconds: null,
    });

    await jest.advanceTimersByTimeAsync(6500);
    resolveNative({ confirm: jest.fn() });
    await jest.advanceTimersByTimeAsync(0);

    expect(phoneAuthDiagnostics()).toMatchObject({
      lastSendOutcome: 'timed-out',
      lastSendSeconds: 1,
      lastSendLateAnswerSeconds: 7.5,
    });
  });

  it("records Firebase's own rejection as a failure", async () => {
    const outstandingBefore = phoneAuthDiagnostics().nativeSendsOutstanding;
    nativeSend.mockRejectedValue(Object.assign(new Error('blocked'), { code: 'auth/too-many-requests' }));

    await expect(sendPhoneVerificationCode(NUMBER)).rejects.toBeDefined();

    expect(phoneAuthDiagnostics()).toMatchObject({
      lastSendOutcome: 'failed',
      nativeSendsOutstanding: outstandingBefore,
    });
  });

  it('cleans up when the native call throws instead of returning a promise', async () => {
    const outstandingBefore = phoneAuthDiagnostics().nativeSendsOutstanding;
    const timersBefore = jest.getTimerCount();
    const boom = new Error('native module threw');
    nativeSend.mockImplementation(() => { throw boom; });

    await expect(sendPhoneVerificationCode(NUMBER)).rejects.toBe(boom);

    // No orphaned 90s timer, no phantom in-flight send, and the record must not flip to
    // "timed-out" later for a send that failed at once.
    expect(jest.getTimerCount()).toBe(timersBefore);
    expect(phoneAuthDiagnostics()).toMatchObject({
      lastSendOutcome: 'failed',
      nativeSendsOutstanding: outstandingBefore,
    });
    await jest.advanceTimersByTimeAsync(PHONE_SEND_TIMEOUT_MS);
    expect(phoneAuthDiagnostics().lastSendOutcome).toBe('failed');
  });

  it('counts confirm attempts against the latest send, and a resend starts the count over', async () => {
    nativeSend.mockResolvedValue({ confirm: jest.fn() });
    await sendPhoneVerificationCode(NUMBER);

    const failing = { confirm: jest.fn().mockRejectedValue(new Error('nope')) };
    await expect(confirmPhoneVerificationCode(failing as never, '111111')).rejects.toThrow('nope');
    await expect(confirmPhoneVerificationCode(failing as never, '222222')).rejects.toThrow('nope');
    expect(phoneAuthDiagnostics().confirmAttemptsSinceLastSend).toBe(2);

    await sendPhoneVerificationCode(NUMBER);
    expect(phoneAuthDiagnostics().confirmAttemptsSinceLastSend).toBe(0);
  });

  it('notes whether Firebase already held a user when the send answered', async () => {
    (getAuth as jest.Mock).mockReturnValue({ currentUser: { uid: 'abc' } });
    nativeSend.mockResolvedValue({ confirm: jest.fn() });

    await sendPhoneVerificationCode(NUMBER);

    expect(phoneAuthDiagnostics()).toMatchObject({
      firebaseUserPresentWhenSendAnswered: true,
      firebaseUserPresentNow: true,
    });

    (getAuth as jest.Mock).mockReturnValue({ currentUser: null });
    expect(phoneAuthDiagnostics().firebaseUserPresentNow).toBe(false);
  });

  it('never carries the phone number, the code, or an error message', async () => {
    nativeSend.mockRejectedValue(new Error(`could not text ${NUMBER}`));
    await expect(sendPhoneVerificationCode(NUMBER)).rejects.toBeDefined();
    const failing = { confirm: jest.fn().mockRejectedValue(new Error('bad code 424242')) };
    await expect(confirmPhoneVerificationCode(failing as never, '424242')).rejects.toBeDefined();

    const serialized = JSON.stringify(phoneAuthDiagnostics());

    expect(serialized).not.toContain(NUMBER.slice(3));
    expect(serialized).not.toContain('424242');
    expect(serialized).not.toContain('could not text');
  });

  it('survives Firebase throwing while its user is read', () => {
    (getAuth as jest.Mock).mockImplementation(() => { throw new Error('native module missing'); });

    expect(phoneAuthDiagnostics().firebaseUserPresentNow).toBeNull();
  });
});

/*
 * FYNORA-MOBILE-6: on some Android phones Firebase reads the SMS itself and signs the person in
 * before they type anything; the code they then type fails ("session expired"). react-native-
 * firebase's own phone-auth guide documents this. These cover the recovery, and -- just as
 * important -- every case where it must NOT fire.
 */
describe('confirmPhoneVerificationCode: automatic (SMS-read) verification', () => {
  const NUMBER = '+919876543210'; // synthetic-ok: invented test number
  const signOutMock = signOut as jest.Mock;
  const reportEvent = reportHandledEvent as jest.Mock;

  const sessionExpired = () =>
    Object.assign(new Error('The sms code has expired.'), { code: 'auth/session-expired' });

  /** A confirmation whose confirm() fails the way a code already used in the background does. */
  const rejectingConfirmation = (err: unknown = sessionExpired()) =>
    ({ confirm: jest.fn().mockRejectedValue(err) });

  const autoUser = (over: Record<string, unknown> = {}) => ({
    phoneNumber: NUMBER,
    getIdToken: jest.fn().mockResolvedValue('auto-id-token'),
    ...over,
  });

  /** Firebase holds no user at send time; `after` is what it holds by the time confirm() runs. */
  async function sendThenConfirm(confirmation: unknown, after: { currentUser: unknown }) {
    (getAuth as jest.Mock).mockReturnValue({ currentUser: null });
    nativeSend.mockResolvedValue(confirmation);
    const handle = await sendPhoneVerificationCode(NUMBER);
    (getAuth as jest.Mock).mockReturnValue(after);
    return confirmPhoneVerificationCode(handle, '123456');
  }

  beforeEach(() => {
    jest.useFakeTimers();
    nativeSend.mockReset();
    (getAuth as jest.Mock).mockReset();
    signOutMock.mockReset();
    signOutMock.mockResolvedValue(undefined);
    reportEvent.mockClear();
  });

  afterEach(() => {
    jest.useRealTimers();
  });

  it('uses the auto-verified user\'s ID token when the typed code is rejected', async () => {
    const user = autoUser();

    const token = await sendThenConfirm(rejectingConfirmation(), { currentUser: user });

    expect(token).toBe('auto-id-token');
    expect(user.getIdToken).toHaveBeenCalledTimes(1);
    expect(signOutMock).toHaveBeenCalled();
    expect(phoneAuthDiagnostics().recoveredViaAutoVerification).toBe(true);
  });

  it('records the rescue, since nothing else would ever show that this path ran', async () => {
    await sendThenConfirm(rejectingConfirmation(), { currentUser: autoUser() });

    expect(reportEvent).toHaveBeenCalledWith(
      expect.any(String),
      'phone-auto-verification-recovered',
      expect.objectContaining({ recoveredViaAutoVerification: true })
    );
    expect(JSON.stringify(reportEvent.mock.calls)).not.toContain(NUMBER);
  });

  it('still throws the original error when Firebase holds no user (a genuinely wrong code)', async () => {
    const err = sessionExpired();

    await expect(sendThenConfirm(rejectingConfirmation(err), { currentUser: null })).rejects.toBe(err);

    expect(reportEvent).not.toHaveBeenCalled();
    expect(phoneAuthDiagnostics().recoveredViaAutoVerification).toBe(false);
  });

  it('does not accept a user whose verified number is a DIFFERENT number', async () => {
    const err = sessionExpired();
    const other = autoUser({ phoneNumber: '+910000000000' }); // synthetic-ok: invented test number

    await expect(sendThenConfirm(rejectingConfirmation(err), { currentUser: other })).rejects.toBe(err);

    expect(other.getIdToken).not.toHaveBeenCalled();
  });

  it('does not accept a user that has no verified phone number at all', async () => {
    const err = sessionExpired();
    const bare = autoUser({ phoneNumber: null });

    await expect(sendThenConfirm(rejectingConfirmation(err), { currentUser: bare })).rejects.toBe(err);

    expect(bare.getIdToken).not.toHaveBeenCalled();
  });

  it('throws the original error if the auto-verified user\'s token cannot be fetched', async () => {
    const err = sessionExpired();
    const broken = autoUser({ getIdToken: jest.fn().mockRejectedValue(new Error('offline')) });

    await expect(sendThenConfirm(rejectingConfirmation(err), { currentUser: broken })).rejects.toBe(err);
  });

  it('signs out a leftover Firebase user BEFORE starting the send, and only then may it trust a later one', async () => {
    const order: string[] = [];
    const leftover = autoUser({ phoneNumber: NUMBER });
    (getAuth as jest.Mock).mockReturnValue({ currentUser: leftover });
    signOutMock.mockImplementation(async () => { order.push('signOut'); });
    nativeSend.mockImplementation(async () => { order.push('send'); return rejectingConfirmation(); });

    await sendPhoneVerificationCode(NUMBER);

    expect(order).toEqual(['signOut', 'send']);
    expect(phoneAuthDiagnostics().startedWithNoFirebaseUser).toBe(true);
  });

  it('refuses to trust a later user when the leftover one could not be signed out', async () => {
    const err = sessionExpired();
    const stale = autoUser();
    (getAuth as jest.Mock).mockReturnValue({ currentUser: stale });
    signOutMock.mockRejectedValue(new Error('cannot sign out'));
    nativeSend.mockResolvedValue(rejectingConfirmation(err));
    const handle = await sendPhoneVerificationCode(NUMBER);

    await expect(confirmPhoneVerificationCode(handle, '123456')).rejects.toBe(err);

    expect(stale.getIdToken).not.toHaveBeenCalled();
    expect(phoneAuthDiagnostics().startedWithNoFirebaseUser).toBe(false);
  });

  it('leaves the normal path untouched when the typed code is accepted', async () => {
    const user = { getIdToken: jest.fn().mockResolvedValue('typed-id-token') };
    const confirmation = { confirm: jest.fn().mockResolvedValue({ user }) };

    const token = await sendThenConfirm(confirmation, { currentUser: null });

    expect(token).toBe('typed-id-token');
    expect(signOutMock).toHaveBeenCalled();
    expect(reportEvent).not.toHaveBeenCalled();
  });

  it('still fails a confirm that returns no user and no auto-verified user exists', async () => {
    const confirmation = { confirm: jest.fn().mockResolvedValue({ user: null }) };

    await expect(sendThenConfirm(confirmation, { currentUser: null }))
      .rejects.toThrow('did not return a user credential');
  });
});
