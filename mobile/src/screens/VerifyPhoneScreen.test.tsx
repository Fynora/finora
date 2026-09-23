import { act, fireEvent, render, screen, waitFor } from '@testing-library/react-native';
import { VerifyPhoneScreen } from './VerifyPhoneScreen';
import { phoneApi, phoneChangeApi, userApi } from '../api/endpoints';
import { confirmPhoneVerificationCode, sendPhoneVerificationCode } from '../lib/phoneAuth';
import { reportHandledError } from '../lib/monitoring';
import { AUTH_PHONE_ALREADY_REGISTERED } from '../api/errorCodes';
import { ThemeProvider } from '../theme';

/**
 * Covers the real production crash this screen used to have: a Google/Apple sign-up reaches this
 * screen with `phoneNumber: null` (AuthService.createOAuthUserRecord leaves it null), and the
 * unconditional sendPhoneVerificationCode(settings.phoneNumber) call crashed Firebase's Android
 * SDK with a native NullPointerException -- caught live via Sentry on a real tester's device (see
 * this screen's own doc comment on startVerification). Also covers the ordinary verify path and
 * the sendError "Change number" escape hatch, both new to this screen (ported from
 * frontend/src/pages/VerifyPhone.tsx).
 */

jest.mock('../api/endpoints', () => ({
  userApi: { get: jest.fn() },
  phoneApi: { verify: jest.fn() },
  phoneChangeApi: { start: jest.fn(), verifyOtp: jest.fn(), complete: jest.fn() },
}));

const mockDiagnostics = { sendsThisSession: 2, lastSendOutcome: 'answered' };
jest.mock('../lib/phoneAuth', () => ({
  sendPhoneVerificationCode: jest.fn(),
  confirmPhoneVerificationCode: jest.fn(),
  phoneAuthDiagnostics: jest.fn(() => mockDiagnostics),
}));

jest.mock('../lib/monitoring', () => ({
  reportHandledError: jest.fn(),
  reportTransportFailure: jest.fn(),
  requestStartedAt: jest.fn(() => 0),
}));

/**
 * AuthScreenLayout mounts AuthAmbientBackground, whose two decorative drifts are infinite
 * `withRepeat` animations. Under Jest, Reanimated schedules each animation frame as a
 * `setTimeout(0)`. Fake timers bump that to 1ms, so the Resend tests' 30s
 * `advanceTimersByTimeAsync` fired 60,000 frame timers (measured with a registration probe). The
 * async advance yields to the real event loop after every timer. That made each of those tests
 * take ~1s on an idle machine and 7-13s under heavy CPU load, close enough to the 15s test timeout
 * that a loaded full parallel run timed out. With this mock, the same 30s advance took 3-4ms under
 * the same load. The background is purely decorative and nothing here asserts on it.
 */
jest.mock('../components/AuthAmbientBackground', () => ({ AuthAmbientBackground: () => null }));

const mockSetPhoneVerified = jest.fn();
const mockLogout = jest.fn();
jest.mock('../context/AuthContext', () => ({
  useAuth: () => ({
    setPhoneVerified: mockSetPhoneVerified,
    logout: mockLogout,
  }),
}));

const userApiMock = userApi as jest.Mocked<typeof userApi>;
const phoneApiMock = phoneApi as jest.Mocked<typeof phoneApi>;
const phoneChangeApiMock = phoneChangeApi as jest.Mocked<typeof phoneChangeApi>;
const sendCode = sendPhoneVerificationCode as jest.MockedFunction<typeof sendPhoneVerificationCode>;
const confirmCode = confirmPhoneVerificationCode as jest.MockedFunction<typeof confirmPhoneVerificationCode>;

const PHONE = '+919876543210'; // synthetic-ok: invented, same fake sequential test number this suite's siblings use
const MASKED_PHONE = '+•••••••••210';

/** Matches what apiErrorCode() reads -- same shape LoginScreen.test.tsx's own deactivatedError()
 *  helper uses for AUTH_ACCOUNT_DEACTIVATED. */
function phoneAlreadyRegisteredError() {
  return Object.assign(new Error('Request failed'), {
    isAxiosError: true,
    response: {
      status: 409,
      data: { errorCode: AUTH_PHONE_ALREADY_REGISTERED, message: 'An account with this mobile number already exists.' },
    },
  });
}

function renderScreen() {
  return render(
    <ThemeProvider>
      <VerifyPhoneScreen />
    </ThemeProvider>
  );
}

async function settle() {
  await act(async () => {});
}

beforeEach(() => {
  jest.clearAllMocks();
});

describe('VerifyPhoneScreen -- ordinary verify flow', () => {
  it('sends a code to the account phone number on mount and verifies it', async () => {
    userApiMock.get.mockResolvedValue({ phoneNumber: PHONE } as never);
    sendCode.mockResolvedValue({} as never);

    renderScreen();
    await settle();

    expect(sendCode).toHaveBeenCalledWith(PHONE);
    expect(screen.getByPlaceholderText('123456')).toBeTruthy();

    confirmCode.mockResolvedValue('id-token');
    phoneApiMock.verify.mockResolvedValue({ message: 'ok' } as never);

    fireEvent.changeText(screen.getByPlaceholderText('123456'), '123456');
    fireEvent.press(screen.getByText('Verify'));
    await settle();

    expect(phoneApiMock.verify).toHaveBeenCalledWith('id-token');
    expect(mockSetPhoneVerified).toHaveBeenCalledWith(true);
  });

  /** Bug fix (found live): a real tester's repeated "This code has expired" reports never once
   *  showed up in Sentry, because this catch block never reported -- only the send-step handlers
   *  did. This confirm-step failure (expired code, wrong code, backend rejection) was completely
   *  invisible to monitoring the whole time. */
  it('reports a failed confirm to monitoring, not just the failed send', async () => {
    userApiMock.get.mockResolvedValue({ phoneNumber: PHONE } as never);
    sendCode.mockResolvedValue({} as never);
    renderScreen();
    await settle();

    confirmCode.mockRejectedValue({ code: 'auth/code-expired' });

    fireEvent.changeText(screen.getByPlaceholderText('123456'), '123456');
    fireEvent.press(screen.getByText('Verify'));
    await settle();

    expect(reportHandledError).toHaveBeenCalledWith({ code: 'auth/code-expired' }, 'phone-verification-confirm', mockDiagnostics);
    expect(screen.getByText('This code has expired. Request a new one.')).toBeTruthy();
  });
});

describe('VerifyPhoneScreen -- missing phone number (Google/Apple sign-up)', () => {
  it('never calls sendPhoneVerificationCode(null) and routes into the add-number form instead', async () => {
    userApiMock.get.mockResolvedValue({ phoneNumber: null } as never);

    renderScreen();
    await settle();

    // The actual regression: a null must never reach Firebase's native SDK.
    expect(sendCode).not.toHaveBeenCalled();
    expect(screen.getByText('Add your phone number')).toBeTruthy();
    expect(
      screen.getByText("Your account doesn't have a mobile number on file yet. We'll send a code to confirm it's yours.")
    ).toBeTruthy();
    // No working `verify` state exists to go back to -- only Sign out, no "Back".
    expect(screen.queryByText('Back')).toBeNull();
  });

  it('lets the user set a number, then verify it, then lands as phoneVerified', async () => {
    userApiMock.get.mockResolvedValue({ phoneNumber: null } as never);
    renderScreen();
    await settle();

    phoneChangeApiMock.start.mockResolvedValue({ sessionId: 'sess-1', maskedPhone: MASKED_PHONE } as never);
    sendCode.mockResolvedValue({} as never);

    fireEvent.changeText(screen.getByLabelText('New mobile number'), '9876543210'); // synthetic-ok: local digits of PHONE above
    fireEvent.press(screen.getByText('Send code'));
    await settle();

    expect(phoneChangeApiMock.start).toHaveBeenCalledWith(PHONE);
    expect(sendCode).toHaveBeenCalledWith(PHONE);
    expect(screen.getByText('Confirm your number')).toBeTruthy();
    // Bug fix (found live): a real tester's code kept expiring right after typing it in, then hit
    // Firebase's rate limit from retrying -- likely a resend invalidating a slow-arriving SMS's
    // code. This screen must warn about arrival delay the same way the main verify screen does,
    // so the user waits instead of resending early.
    expect(
      screen.getByText(
        `Enter the 6-digit code we sent to ${MASKED_PHONE}. This can take a minute or two to arrive -- wait for it rather than resending, since a new code cancels the old one.`
      )
    ).toBeTruthy();

    confirmCode.mockResolvedValue('id-token-2');
    phoneChangeApiMock.verifyOtp.mockResolvedValue({ message: 'ok' } as never);
    phoneChangeApiMock.complete.mockResolvedValue({ message: 'ok', phoneNumber: PHONE } as never);

    fireEvent.changeText(screen.getByPlaceholderText('123456'), '654321');
    fireEvent.press(screen.getByText('Confirm number'));
    await settle();

    expect(phoneChangeApiMock.verifyOtp).toHaveBeenCalledWith('sess-1', 'id-token-2');
    expect(phoneChangeApiMock.complete).toHaveBeenCalledWith('sess-1');
    expect(mockSetPhoneVerified).toHaveBeenCalledWith(true);
  });

  /** Same gap, same fix as the ordinary verify flow's identical test above -- this is the other
   *  confirm-step handler on this screen (the actual one the real tester's repeated "code
   *  expired" reports came through) and had the identical blind spot. */
  it('reports a failed confirm to monitoring, not just the failed send', async () => {
    userApiMock.get.mockResolvedValue({ phoneNumber: null } as never);
    renderScreen();
    await settle();

    phoneChangeApiMock.start.mockResolvedValue({ sessionId: 'sess-1', maskedPhone: MASKED_PHONE } as never);
    sendCode.mockResolvedValue({} as never);

    fireEvent.changeText(screen.getByLabelText('New mobile number'), '9876543210'); // synthetic-ok: local digits of PHONE above
    fireEvent.press(screen.getByText('Send code'));
    await settle();

    confirmCode.mockRejectedValue({ code: 'auth/code-expired' });

    fireEvent.changeText(screen.getByPlaceholderText('123456'), '654321');
    fireEvent.press(screen.getByText('Confirm number'));
    await settle();

    expect(reportHandledError).toHaveBeenCalledWith({ code: 'auth/code-expired' }, 'verify-phone-change-number-confirm-otp', mockDiagnostics);
    expect(screen.getByText('This code has expired. Request a new one.')).toBeTruthy();
  });

  it('reports a failed change-number send with the phone-auth diagnostics attached', async () => {
    userApiMock.get.mockResolvedValue({ phoneNumber: null } as never);
    renderScreen();
    await settle();

    phoneChangeApiMock.start.mockResolvedValue({ sessionId: 'sess-1', maskedPhone: MASKED_PHONE } as never);
    const timeout = Object.assign(new Error('timeout'), { code: 'auth/phone-send-timeout' });
    sendCode.mockRejectedValue(timeout);

    fireEvent.changeText(screen.getByLabelText('New mobile number'), '9876543210'); // synthetic-ok: local digits of PHONE above
    fireEvent.press(screen.getByText('Send code'));
    await settle();

    expect(reportHandledError).toHaveBeenCalledWith(timeout, 'verify-phone-change-number-send-otp', mockDiagnostics);
  });

  /** FYNORA-MOBILE-6 (Sentry): a real tester hit "code expired" here with only one way out --
   *  "Didn't get a code? Change number", which discarded the number they'd already typed and
   *  forced re-entering all 10 digits just to get a fresh code for the SAME number. This is the
   *  new in-place Resend, matching the main verify flow's own low-friction recovery. */
  it('resends a fresh code for the same number without re-asking for it', async () => {
    // Resend shares changeResendCooldown with the initial "Send code" tap (by design -- see
    // Resend's own comment above the button), so it starts this test already mid-cooldown from
    // the send below. Fake timers fast-forward past the 30s instead of pretending it isn't there.
    jest.useFakeTimers({ doNotFake: ['queueMicrotask'] });

    userApiMock.get.mockResolvedValue({ phoneNumber: null } as never);
    renderScreen();
    await act(async () => { await jest.advanceTimersByTimeAsync(0); });

    phoneChangeApiMock.start.mockResolvedValue({ sessionId: 'sess-1', maskedPhone: MASKED_PHONE } as never);
    sendCode.mockResolvedValue({} as never);

    fireEvent.changeText(screen.getByLabelText('New mobile number'), '9876543210'); // synthetic-ok: local digits of PHONE above
    fireEvent.press(screen.getByText('Send code'));
    await act(async () => { await jest.advanceTimersByTimeAsync(0); });

    confirmCode.mockRejectedValue({ code: 'auth/code-expired' });
    fireEvent.changeText(screen.getByPlaceholderText('123456'), '654321');
    fireEvent.press(screen.getByText('Confirm number'));
    await act(async () => { await jest.advanceTimersByTimeAsync(0); });
    expect(screen.getByText('This code has expired. Request a new one.')).toBeTruthy();
    expect(screen.getByText('Resend in 30s')).toBeTruthy();

    phoneChangeApiMock.start.mockResolvedValue({ sessionId: 'sess-2', maskedPhone: MASKED_PHONE } as never);

    await act(async () => { await jest.advanceTimersByTimeAsync(30000); });
    fireEvent.press(screen.getByText("Didn't get a code? Resend"));
    await act(async () => { await jest.advanceTimersByTimeAsync(0); });

    jest.useRealTimers();

    // Same number resent, no detour through re-entering it -- still on this screen, not bounced
    // back to the number-entry form.
    expect(phoneChangeApiMock.start).toHaveBeenCalledTimes(2);
    expect(phoneChangeApiMock.start).toHaveBeenLastCalledWith(PHONE);
    expect(sendCode).toHaveBeenCalledTimes(2);
    expect(sendCode).toHaveBeenLastCalledWith(PHONE);
    expect(screen.queryByLabelText('New mobile number')).toBeNull();
    // The stale error and the stale (now-expired) typed code are both cleared for the fresh
    // attempt, not left showing against a confirmation object that's already been superseded.
    expect(screen.queryByText('This code has expired. Request a new one.')).toBeNull();
    expect(screen.getByPlaceholderText('123456').props.value).toBe('');

    confirmCode.mockResolvedValue('id-token-3');
    phoneChangeApiMock.verifyOtp.mockResolvedValue({ message: 'ok' } as never);
    phoneChangeApiMock.complete.mockResolvedValue({ message: 'ok', phoneNumber: PHONE } as never);

    fireEvent.changeText(screen.getByPlaceholderText('123456'), '111222');
    fireEvent.press(screen.getByText('Confirm number'));
    await settle();

    // Confirms against the NEW session, not the expired one the resend replaced.
    expect(phoneChangeApiMock.verifyOtp).toHaveBeenCalledWith('sess-2', 'id-token-3');
    expect(mockSetPhoneVerified).toHaveBeenCalledWith(true);
  });

  /** Resend (above) calls the exact same handleStartPhoneChange as the initial "Send code" --
   *  so it can hit AUTH_PHONE_ALREADY_REGISTERED too (another account claims the number in the
   *  gap between the original start() and a resend). This screen must offer the same way out, not
   *  just the enterNewNumber form the tester already got past. */
  it('offers "Log in instead" when a resend finds the number now taken', async () => {
    jest.useFakeTimers({ doNotFake: ['queueMicrotask'] });

    userApiMock.get.mockResolvedValue({ phoneNumber: null } as never);
    renderScreen();
    await act(async () => { await jest.advanceTimersByTimeAsync(0); });

    phoneChangeApiMock.start.mockResolvedValue({ sessionId: 'sess-1', maskedPhone: MASKED_PHONE } as never);
    sendCode.mockResolvedValue({} as never);

    fireEvent.changeText(screen.getByLabelText('New mobile number'), '9876543210'); // synthetic-ok: local digits of PHONE above
    fireEvent.press(screen.getByText('Send code'));
    await act(async () => { await jest.advanceTimersByTimeAsync(0); });
    expect(screen.getByText('Confirm your number')).toBeTruthy();

    phoneChangeApiMock.start.mockRejectedValue(phoneAlreadyRegisteredError());

    await act(async () => { await jest.advanceTimersByTimeAsync(30000); });
    fireEvent.press(screen.getByText("Didn't get a code? Resend"));
    await act(async () => { await jest.advanceTimersByTimeAsync(0); });

    jest.useRealTimers();

    expect(screen.getByText('An account with this mobile number already exists.')).toBeTruthy();
    fireEvent.press(screen.getByText('Log in instead'));
    expect(mockLogout).toHaveBeenCalled();
  });

  /** Self-review gap (found before shipping): handleConfirmPhoneChange's own catch never touched
   *  changeErrorCode, so a stale AUTH_PHONE_ALREADY_REGISTERED left over from the resend race
   *  above would leave "Log in instead" dangling under a completely unrelated confirm-step error
   *  -- a simple wrong code, not another taken number. */
  it('clears "Log in instead" once a later confirm attempt fails for an unrelated reason', async () => {
    jest.useFakeTimers({ doNotFake: ['queueMicrotask'] });

    userApiMock.get.mockResolvedValue({ phoneNumber: null } as never);
    renderScreen();
    await act(async () => { await jest.advanceTimersByTimeAsync(0); });

    phoneChangeApiMock.start.mockResolvedValue({ sessionId: 'sess-1', maskedPhone: MASKED_PHONE } as never);
    sendCode.mockResolvedValue({} as never);

    fireEvent.changeText(screen.getByLabelText('New mobile number'), '9876543210'); // synthetic-ok: local digits of PHONE above
    fireEvent.press(screen.getByText('Send code'));
    await act(async () => { await jest.advanceTimersByTimeAsync(0); });

    phoneChangeApiMock.start.mockRejectedValue(phoneAlreadyRegisteredError());
    await act(async () => { await jest.advanceTimersByTimeAsync(30000); });
    fireEvent.press(screen.getByText("Didn't get a code? Resend"));
    await act(async () => { await jest.advanceTimersByTimeAsync(0); });

    jest.useRealTimers();
    expect(screen.getByText('Log in instead')).toBeTruthy();

    // A later confirm attempt with a simply wrong code -- nothing to do with the number being
    // taken -- must not leave the earlier nudge attached to it.
    confirmCode.mockRejectedValue({ code: 'auth/invalid-verification-code' });
    fireEvent.changeText(screen.getByPlaceholderText('123456'), '000000');
    fireEvent.press(screen.getByText('Confirm number'));
    await settle();

    expect(screen.getByText("That code doesn't match — check and try again.")).toBeTruthy();
    expect(screen.queryByText('Log in instead')).toBeNull();
  });

  it('rejects an invalid local number without calling the backend', async () => {
    userApiMock.get.mockResolvedValue({ phoneNumber: null } as never);
    renderScreen();
    await settle();

    fireEvent.changeText(screen.getByLabelText('New mobile number'), '12345');
    fireEvent.press(screen.getByText('Send code'));
    await settle();

    expect(phoneChangeApiMock.start).not.toHaveBeenCalled();
    expect(screen.getByText('Enter a valid 10-digit mobile number (no leading 0-5).')).toBeTruthy();
  });

  it('does not double-submit when the keyboard "Go" fires again before the first request settles', async () => {
    // Regression: unlike a web <form>, RN's onSubmitEditing has no native protection from a
    // disabled submit button -- it fires unconditionally. Without handleStartPhoneChange's own
    // changeSubmitting guard, a second keyboard "Go" (or a fast double-tap racing the Button's
    // own disabled state) while the first request is still in flight would call
    // phoneChangeApi.start()/sendPhoneVerificationCode() a second time concurrently.
    userApiMock.get.mockResolvedValue({ phoneNumber: null } as never);
    renderScreen();
    await settle();

    let resolveStart: (v: { sessionId: string; maskedPhone: string }) => void;
    phoneChangeApiMock.start.mockReturnValue(
      new Promise((resolve) => {
        resolveStart = resolve;
      }) as never
    );
    sendCode.mockResolvedValue({} as never);

    const field = screen.getByLabelText('New mobile number');
    fireEvent.changeText(field, '9876543210'); // synthetic-ok: local digits of PHONE above
    fireEvent(field, 'submitEditing');
    fireEvent(field, 'submitEditing');
    await settle();

    expect(phoneChangeApiMock.start).toHaveBeenCalledTimes(1);

    await act(async () => {
      resolveStart({ sessionId: 'sess-1', maskedPhone: MASKED_PHONE });
    });

    expect(sendCode).toHaveBeenCalledTimes(1);
  });

  it('applies the resend cooldown after a FAILED send too, not just a successful one', async () => {
    // Regression (found live): a real tester hit Firebase's own auth/too-many-requests here,
    // then kept tapping "Send code" again immediately -- because a failed attempt never applied
    // the cooldown, only a successful one did. Nothing slowed the retries down, which is exactly
    // how they re-triggered (and likely extended) Firebase's own rate limit.
    userApiMock.get.mockResolvedValue({ phoneNumber: null } as never);
    renderScreen();
    await settle();

    phoneChangeApiMock.start.mockRejectedValue(new Error('auth/too-many-requests'));

    fireEvent.changeText(screen.getByLabelText('New mobile number'), '9876543210'); // synthetic-ok: local digits of PHONE above
    fireEvent.press(screen.getByText('Send code'));
    await settle();

    expect(screen.getByText('Send code in 30s')).toBeTruthy();
    expect(screen.queryByText('Send code')).toBeNull();
  });

  /** FYNORA-MOBILE-7 (Sentry): a real tester -- signed in with Google, no phone number on file
   *  yet -- typed a number that already belongs to a DIFFERENT account, almost certainly their
   *  own from before. The backend's rejection alone was a dead end for them; offer the actual
   *  fix directly instead of leaving them to retype numbers hoping one works. */
  it('offers "Log in instead" when the number already belongs to another account', async () => {
    userApiMock.get.mockResolvedValue({ phoneNumber: null } as never);
    renderScreen();
    await settle();

    phoneChangeApiMock.start.mockRejectedValue(phoneAlreadyRegisteredError());

    fireEvent.changeText(screen.getByLabelText('New mobile number'), '9876543210'); // synthetic-ok: local digits of PHONE above
    fireEvent.press(screen.getByText('Send code'));
    await settle();

    expect(screen.getByText('An account with this mobile number already exists.')).toBeTruthy();
    fireEvent.press(screen.getByText('Log in instead'));
    expect(mockLogout).toHaveBeenCalled();
  });

  /** The same offer must not appear for an ordinary rejection (e.g. an invalid number, or a
   *  genuine Firebase send failure) -- only this one specific, known-actionable cause. */
  it('does not offer "Log in instead" for an unrelated send failure', async () => {
    userApiMock.get.mockResolvedValue({ phoneNumber: null } as never);
    renderScreen();
    await settle();

    phoneChangeApiMock.start.mockRejectedValue(new Error('auth/too-many-requests'));

    fireEvent.changeText(screen.getByLabelText('New mobile number'), '9876543210'); // synthetic-ok: local digits of PHONE above
    fireEvent.press(screen.getByText('Send code'));
    await settle();

    expect(screen.queryByText('Log in instead')).toBeNull();
  });
});

describe('VerifyPhoneScreen -- send failure escape hatch', () => {
  it('offers Change number / Sign out when the send itself fails, and reports it', async () => {
    userApiMock.get.mockResolvedValue({ phoneNumber: PHONE } as never);
    sendCode.mockRejectedValue(new Error('boom'));

    renderScreen();
    await settle();

    expect(reportHandledError).toHaveBeenCalledWith(expect.any(Error), 'phone-verification-send', mockDiagnostics);
    expect(screen.getByText('Change number')).toBeTruthy();

    fireEvent.press(screen.getByText('Change number'));
    await waitFor(() => expect(screen.getByText('Change your number')).toBeTruthy());
    // Reached via the escape hatch, not the missing-number path -- a working `verify` state
    // exists to return to.
    expect(screen.getByText('Back')).toBeTruthy();
  });

  /** A tester's Android phone sat on "Sending a verification code..." for over two minutes: the
   *  native send never answered and nothing bounded the wait. phoneAuth now rejects with this code
   *  after its own timeout; the screen must turn that into a message and a way out, not the
   *  generic fallback and not a stuck spinner. */
  it('shows a plain "taking too long" message when the send times out, and still offers a way out', async () => {
    userApiMock.get.mockResolvedValue({ phoneNumber: PHONE } as never);
    sendCode.mockRejectedValue(Object.assign(new Error('timeout'), { code: 'auth/phone-send-timeout' }));

    renderScreen();
    await settle();

    expect(screen.getByText('Sending the code is taking too long. Check your connection and try again.')).toBeTruthy();
    expect(screen.queryByText('Could not send a verification code right now.')).toBeNull();
    expect(reportHandledError).toHaveBeenCalledWith(
      expect.objectContaining({ code: 'auth/phone-send-timeout' }),
      'phone-verification-send',
      mockDiagnostics
    );
    expect(screen.getByText('Change number')).toBeTruthy();
  });
});
