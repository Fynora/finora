import { act, fireEvent, render, screen, waitFor } from '@testing-library/react-native';
import { VerifyPhoneScreen } from './VerifyPhoneScreen';
import { phoneApi, phoneChangeApi, userApi } from '../api/endpoints';
import { confirmPhoneVerificationCode, sendPhoneVerificationCode } from '../lib/phoneAuth';
import { reportHandledError } from '../lib/monitoring';
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

jest.mock('../lib/phoneAuth', () => ({
  sendPhoneVerificationCode: jest.fn(),
  confirmPhoneVerificationCode: jest.fn(),
}));

jest.mock('../lib/monitoring', () => ({
  reportHandledError: jest.fn(),
}));

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
});

describe('VerifyPhoneScreen -- send failure escape hatch', () => {
  it('offers Change number / Sign out when the send itself fails, and reports it', async () => {
    userApiMock.get.mockResolvedValue({ phoneNumber: PHONE } as never);
    sendCode.mockRejectedValue(new Error('boom'));

    renderScreen();
    await settle();

    expect(reportHandledError).toHaveBeenCalledWith(expect.any(Error), 'phone-verification-send');
    expect(screen.getByText('Change number')).toBeTruthy();

    fireEvent.press(screen.getByText('Change number'));
    await waitFor(() => expect(screen.getByText('Change your number')).toBeTruthy());
    // Reached via the escape hatch, not the missing-number path -- a working `verify` state
    // exists to return to.
    expect(screen.getByText('Back')).toBeTruthy();
  });
});
