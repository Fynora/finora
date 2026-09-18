import { signInWithPhoneNumber } from '@react-native-firebase/auth';
import {
  PHONE_SEND_TIMEOUT_CODE, PHONE_SEND_TIMEOUT_MS, sendPhoneVerificationCode,
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
