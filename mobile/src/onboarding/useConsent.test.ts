import { act, renderHook } from '@testing-library/react-native';
import { userApi } from '../api/endpoints';
import { consentRequired, useConsent } from './useConsent';

jest.mock('../api/endpoints', () => ({
  userApi: { get: jest.fn(), consent: jest.fn() },
}));

const get = userApi.get as jest.Mock;
const consent = userApi.consent as jest.Mock;

function profile(overrides: object = {}) {
  return { termsAcceptanceRequired: false, currentTermsVersion: '2026-10', aiProcessingConsent: false, ...overrides };
}

beforeEach(() => {
  get.mockReset();
  consent.mockReset();
});

describe('consentRequired', () => {
  it('is needed when the current Terms are not accepted, or the AI question is unanswered', () => {
    expect(consentRequired(profile({ termsAcceptanceRequired: true }) as never)).toBe(true);
    expect(consentRequired(profile({ aiProcessingConsent: null }) as never)).toBe(true);
    expect(consentRequired(profile({ aiProcessingConsent: false }) as never)).toBe(false);
    expect(consentRequired(profile({ aiProcessingConsent: true }) as never)).toBe(false);
  });
});

describe('useConsent', () => {
  it('looks nothing up before the account is signed in and verified', () => {
    const { result } = renderHook(() => useConsent(false, null));

    expect(result.current.needsAnswer).toBe(false);
    expect(result.current.pending).toBe(false);
    expect(get).not.toHaveBeenCalled();
  });

  it('is pending while it looks the profile up, then asks when an answer is missing', async () => {
    let resolve!: (v: unknown) => void;
    get.mockReturnValue(new Promise((r) => { resolve = r; }));
    const { result } = renderHook(() => useConsent(true, 'a@example.com'));

    expect(result.current.pending).toBe(true);
    await act(async () => resolve(profile({ termsAcceptanceRequired: true, aiProcessingConsent: null })));
    expect(result.current.pending).toBe(false);
    expect(result.current.needsAnswer).toBe(true);
  });

  it('does not ask an account that has answered both', async () => {
    get.mockResolvedValue(profile());
    const { result } = renderHook(() => useConsent(true, 'a@example.com'));

    await act(async () => {});
    expect(result.current.needsAnswer).toBe(false);
    expect(result.current.pending).toBe(false);
  });

  /** A network failure never locks anyone out; AI stays off on the server regardless. */
  it('asks nothing when the profile cannot be loaded', async () => {
    get.mockRejectedValue(new Error('network down'));
    const { result } = renderHook(() => useConsent(true, 'a@example.com'));

    await act(async () => {});
    expect(result.current.needsAnswer).toBe(false);
    expect(result.current.pending).toBe(false);
  });

  it('stops asking once the answer is saved', async () => {
    get.mockResolvedValue(profile({ aiProcessingConsent: null }));
    consent.mockResolvedValue(profile({ aiProcessingConsent: true }));
    const { result } = renderHook(() => useConsent(true, 'a@example.com'));
    await act(async () => {});
    expect(result.current.needsAnswer).toBe(true);

    await act(async () => { await result.current.submit({ aiProcessing: true }); });

    expect(consent).toHaveBeenCalledWith({ aiProcessing: true });
    expect(result.current.needsAnswer).toBe(false);
  });

  it('never lets one account stand for another', async () => {
    get.mockResolvedValueOnce(profile());
    const { result, rerender } = renderHook(({ key }: { key: string }) => useConsent(true, key), {
      initialProps: { key: 'a@example.com' },
    });
    await act(async () => {});
    expect(result.current.needsAnswer).toBe(false);

    let resolve!: (v: unknown) => void;
    get.mockReturnValueOnce(new Promise((r) => { resolve = r; }));
    rerender({ key: 'b@example.com' });
    expect(result.current.pending).toBe(true);
    await act(async () => resolve(profile({ termsAcceptanceRequired: true })));
    expect(result.current.needsAnswer).toBe(true);
  });
});
