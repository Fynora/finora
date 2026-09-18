import { act, renderHook } from '@testing-library/react-native';
import { Alert, Linking } from 'react-native';
import { authApi } from '../api/endpoints';
import { useEmailVerificationDeepLink } from './useEmailVerificationDeepLink';

jest.mock('../api/endpoints', () => ({
  authApi: { verifyEmail: jest.fn() },
}));

const verifyEmail = authApi.verifyEmail as jest.MockedFunction<typeof authApi.verifyEmail>;
const getInitialURLSpy = jest.spyOn(Linking, 'getInitialURL');
const addEventListenerSpy = jest.spyOn(Linking, 'addEventListener');
const alertSpy = jest.spyOn(Alert, 'alert').mockImplementation(() => {});

describe('useEmailVerificationDeepLink', () => {
  let urlListener: ((event: { url: string }) => void) | null;

  async function deliver(url: string) {
    await act(async () => { urlListener?.({ url }); });
  }

  beforeEach(() => {
    urlListener = null;
    verifyEmail.mockReset().mockResolvedValue({ message: 'ok' });
    alertSpy.mockClear();
    getInitialURLSpy.mockReset().mockResolvedValue(null);
    addEventListenerSpy.mockReset().mockImplementation((_event, listener) => {
      urlListener = listener as (event: { url: string }) => void;
      return { remove: jest.fn() } as never;
    });
  });

  it('confirms the token from the https app link and says so', async () => {
    renderHook(() => useEmailVerificationDeepLink());
    await deliver('https://app.fynora.net/verify-email?token=abc123');

    expect(verifyEmail).toHaveBeenCalledWith('abc123');
    expect(alertSpy).toHaveBeenCalledWith('Email verified', expect.any(String));
  });

  it('handles the custom-scheme form the same way', async () => {
    renderHook(() => useEmailVerificationDeepLink());
    await deliver('finora://verify-email?token=abc123');

    expect(verifyEmail).toHaveBeenCalledWith('abc123');
  });

  it('handles a link that cold-launched the app, not just a live one', async () => {
    getInitialURLSpy.mockResolvedValue('https://app.fynora.net/verify-email?token=cold');
    renderHook(() => useEmailVerificationDeepLink());
    await act(async () => {});

    expect(verifyEmail).toHaveBeenCalledWith('cold');
    expect(alertSpy).toHaveBeenCalledWith('Email verified', expect.any(String));
  });

  it("shows the server's reason when the token is rejected", async () => {
    verifyEmail.mockRejectedValue({ isAxiosError: true, response: { status: 400, data: { message: 'Token expired' } } });
    renderHook(() => useEmailVerificationDeepLink());
    await deliver('https://app.fynora.net/verify-email?token=old');

    expect(alertSpy).toHaveBeenCalledWith('Verification failed', expect.any(String));
    expect(alertSpy).not.toHaveBeenCalledWith('Email verified', expect.anything());
  });

  it('tells the user when the link carries no token, without calling the API', async () => {
    renderHook(() => useEmailVerificationDeepLink());
    await deliver('https://app.fynora.net/verify-email');

    expect(verifyEmail).not.toHaveBeenCalled();
    expect(alertSpy).toHaveBeenCalledWith('Verification failed', expect.stringContaining('token'));
  });

  it('does not spend a single-use token twice when the same URL is delivered again', async () => {
    renderHook(() => useEmailVerificationDeepLink());
    await deliver('https://app.fynora.net/verify-email?token=once');
    await deliver('https://app.fynora.net/verify-email?token=once');

    expect(verifyEmail).toHaveBeenCalledTimes(1);
  });

  it('retries the same link after a failure, so a dropped connection is not a dead end', async () => {
    verifyEmail.mockRejectedValueOnce(new Error('network')).mockResolvedValueOnce({ message: 'ok' });
    renderHook(() => useEmailVerificationDeepLink());
    await deliver('https://app.fynora.net/verify-email?token=retry');
    await deliver('https://app.fynora.net/verify-email?token=retry');

    expect(verifyEmail).toHaveBeenCalledTimes(2);
    expect(alertSpy).toHaveBeenLastCalledWith('Email verified', expect.any(String));
  });

  it('ignores every other link', async () => {
    renderHook(() => useEmailVerificationDeepLink());
    await deliver('https://app.fynora.net/reset-password?token=abc');
    await deliver('https://evil.example/verify-email?token=abc');
    await deliver('finora://register?ref=X');

    expect(verifyEmail).not.toHaveBeenCalled();
    expect(alertSpy).not.toHaveBeenCalled();
  });
});
