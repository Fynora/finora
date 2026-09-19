import { getAuth, signInWithPhoneNumber } from '@react-native-firebase/auth';
import {
  PHONE_SEND_TIMEOUT_CODE, PHONE_SEND_TIMEOUT_MS, confirmPhoneVerificationCode,
  phoneAuthDiagnostics, sendPhoneVerificationCode,
} from './phoneAuth';
import { toUserMessage } from './apiError';

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
