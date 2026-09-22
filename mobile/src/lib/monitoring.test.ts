import type { Breadcrumb, ErrorEvent } from '@sentry/react-native';
import * as Sentry from '@sentry/react-native';
import {
  redactPath,
  reportHandledError,
  reportHandledEvent,
  reportTransportFailure,
  scrubBreadcrumb,
  scrubEvent,
  scrubUrl,
} from './monitoring';

function transportFailure(code = 'ERR_NETWORK') {
  return Object.assign(new Error('Network Error'), { isAxiosError: true, code });
}

function serverError() {
  return Object.assign(new Error('Request failed'), {
    isAxiosError: true,
    response: { status: 500, data: {} },
  });
}

/*
 * These assert what must NEVER leave the device. Scrubbing that quietly stops working is
 * indistinguishable from scrubbing that works, so each case below names the specific leak it
 * prevents rather than just pinning current behaviour.
 */

describe('redactPath', () => {
  it('replaces account and transaction UUIDs', () => {
    const out = redactPath('/api/v1/transactions/3f2504e0-4f89-11d3-9a0c-0305e82c3301');
    expect(out).toBe('/api/v1/transactions/{id}');
    expect(out).not.toMatch(/3f2504e0/);
  });

  it('replaces long digit runs, which is what an account number looks like in a path', () => {
    expect(redactPath('/accounts/12345678901234')).toBe('/accounts/{n}'); // synthetic-ok: invented, and the assertion is that it gets redacted
  });

  it('leaves the route shape intact so errors still group', () => {
    expect(redactPath('/api/v1/statement-imports/{id}/file')).toBe('/api/v1/statement-imports/{id}/file');
    expect(redactPath('/api/v1/dashboard/summary')).toBe('/api/v1/dashboard/summary');
  });

  // /api/v1 must survive -- redacting it would collapse every route into the same string.
  it('does not redact short numbers that carry no identity', () => {
    expect(redactPath('/api/v1/goals')).toBe('/api/v1/goals');
  });
});

describe('scrubUrl', () => {
  // The ledger search sends whatever the user typed. That can be a merchant, a landlord, or their
  // own name -- the single most sensitive free-text field in the app.
  it('drops the query string entirely', () => {
    const out = scrubUrl('https://api.example.com/api/v1/transactions?keyword=Dr%20Sharma%20clinic&page=0');
    expect(out).toBe('https://api.example.com/api/v1/transactions');
    expect(out).not.toMatch(/keyword|Sharma/i);
  });

  it('redacts identifiers in the remaining path', () => {
    expect(scrubUrl('https://api.example.com/api/v1/accounts/3f2504e0-4f89-11d3-9a0c-0305e82c3301'))
      .toBe('https://api.example.com/api/v1/accounts/{id}');
  });

  it('returns undefined for a non-string rather than coercing', () => {
    expect(scrubUrl(undefined)).toBeUndefined();
    expect(scrubUrl(null)).toBeUndefined();
    expect(scrubUrl(42)).toBeUndefined();
  });
});

describe('scrubBreadcrumb', () => {
  // Anything console.log'd while debugging a finance bug tends to be an amount or an account.
  it('drops console breadcrumbs wholesale', () => {
    const crumb: Breadcrumb = {
      category: 'console',
      message: 'balance for 3f2504e0 is 84210.55',
    };
    expect(scrubBreadcrumb(crumb)).toBeNull();
  });

  it('keeps only method, status and a scrubbed URL from a network breadcrumb', () => {
    const crumb: Breadcrumb = {
      category: 'xhr',
      data: {
        method: 'POST',
        status_code: 400,
        url: 'https://api.example.com/api/v1/transactions?keyword=rent',
        // Fields Sentry or a future version might attach:
        request_body: '{"amount":84210.55}',
        headers: { Authorization: 'Bearer secret-token' },
      },
    };

    const out = scrubBreadcrumb(crumb);
    expect(out?.data).toEqual({
      method: 'POST',
      status_code: 400,
      url: 'https://api.example.com/api/v1/transactions',
    });
    // Rebuilt rather than deleted key by key, so unknown fields can't survive.
    expect(JSON.stringify(out)).not.toMatch(/Bearer|secret-token|84210|rent/);
  });

  it('applies the same treatment to fetch breadcrumbs', () => {
    const out = scrubBreadcrumb({
      category: 'fetch',
      data: { method: 'GET', url: 'https://api.example.com/x?q=private' },
    });
    expect(out?.data?.url).toBe('https://api.example.com/x');
  });

  it('passes through breadcrumbs that carry no request data', () => {
    const crumb: Breadcrumb = { category: 'navigation', message: 'Login -> Dashboard' };
    expect(scrubBreadcrumb(crumb)).toEqual(crumb);
  });
});

describe('scrubEvent', () => {
  // The worst case: registration sends email, phone, and a plaintext password, and a crash there
  // is both likely and maximally sensitive.
  it('strips the request body from a failed registration', () => {
    const event = {
      request: {
        method: 'POST',
        url: 'https://api.example.com/api/v1/auth/register',
        data: { email: 'someone@example.com', password: 'hunter2', phoneNumber: '+910000000000' }, // synthetic-ok: invented payload proving it never leaves
        headers: { Authorization: 'Bearer secret-token' },
      },
    } as unknown as ErrorEvent;

    const out = scrubEvent(event);

    expect(out.request).toEqual({
      method: 'POST',
      url: 'https://api.example.com/api/v1/auth/register',
    });
    expect(JSON.stringify(out)).not.toMatch(/hunter2|someone@example|Bearer/);
  });

  it('removes user identity even if something attached it', () => {
    const event = {
      user: { email: 'someone@example.com', id: 'user-1', ip_address: '203.0.113.7' },
    } as unknown as ErrorEvent;

    expect(scrubEvent(event).user).toBeUndefined();
  });

  it('scrubs breadcrumbs carried on the event, not just live ones', () => {
    const event = {
      breadcrumbs: [
        { category: 'console', message: 'account 12345678901 balance' }, // synthetic-ok: invented, asserted to be dropped
        { category: 'xhr', data: { method: 'GET', url: 'https://api.example.com/t?keyword=rent' } },
      ],
    } as unknown as ErrorEvent;

    const out = scrubEvent(event);

    // The console crumb is dropped, not merely emptied.
    expect(out.breadcrumbs).toHaveLength(1);
    expect(JSON.stringify(out)).not.toMatch(/keyword|rent|12345678901/);
  });

  it('leaves an event with nothing sensitive untouched', () => {
    const event = { message: 'Something failed' } as unknown as ErrorEvent;
    expect(scrubEvent(event)).toEqual({ message: 'Something failed' });
  });
});

describe('reportHandledError', () => {
  const capture = Sentry.captureException as jest.Mock;
  const original = process.env.EXPO_PUBLIC_SENTRY_DSN;

  beforeEach(() => {
    capture.mockClear();
    process.env.EXPO_PUBLIC_SENTRY_DSN = 'https://example.invalid/1';
  });

  afterEach(() => {
    if (original === undefined) delete process.env.EXPO_PUBLIC_SENTRY_DSN;
    else process.env.EXPO_PUBLIC_SENTRY_DSN = original;
  });

  it('tags the context and attaches details as their own Sentry context', () => {
    const err = new Error('x');

    reportHandledError(err, 'some-context', { sendsThisSession: 2, lastSendOutcome: 'answered' });

    expect(capture).toHaveBeenCalledWith(err, {
      tags: { context: 'some-context' },
      contexts: { details: { sendsThisSession: 2, lastSendOutcome: 'answered' } },
    });
  });

  it('sends no contexts key at all when there are no details', () => {
    const err = new Error('x');

    reportHandledError(err, 'some-context');

    expect(capture).toHaveBeenCalledWith(err, { tags: { context: 'some-context' } });
  });

  it('does nothing when Sentry has no DSN', () => {
    delete process.env.EXPO_PUBLIC_SENTRY_DSN;

    reportHandledError(new Error('x'), 'some-context', { a: 1 });

    expect(capture).not.toHaveBeenCalled();
  });
});

describe('reportTransportFailure', () => {
  const capture = Sentry.captureMessage as jest.Mock;
  const original = process.env.EXPO_PUBLIC_SENTRY_DSN;

  beforeEach(() => {
    capture.mockClear();
    process.env.EXPO_PUBLIC_SENTRY_DSN = 'https://example.invalid/1';
  });

  afterEach(() => {
    if (original === undefined) delete process.env.EXPO_PUBLIC_SENTRY_DSN;
    else process.env.EXPO_PUBLIC_SENTRY_DSN = original;
  });

  // Each case below uses its own context string -- the rate limit is keyed per context (see the
  // throttle test at the bottom), and module state persists across `it()` blocks in the same file,
  // so reusing a context here would make an earlier test's report silently swallow this one's.

  it('reports a transport failure with its code and, when tracked, its duration', () => {
    const startedAt = Date.now() - 1234;
    reportTransportFailure(transportFailure('ECONNABORTED'), 'test:duration-case', startedAt);

    expect(capture).toHaveBeenCalledWith('Request never got a response', {
      level: 'info',
      tags: { context: 'transport-failure' },
      contexts: {
        details: expect.objectContaining({
          context: 'test:duration-case',
          code: 'ECONNABORTED',
          durationMs: expect.any(Number),
        }),
      },
    });
  });

  it('omits durationMs when the caller did not track a start time', () => {
    reportTransportFailure(transportFailure(), 'test:no-duration-case');

    const details = capture.mock.calls[0][1].contexts.details;
    expect(details).not.toHaveProperty('durationMs');
  });

  it('does not report a real server response -- that is the server’s own problem, not a transport failure', () => {
    reportTransportFailure(serverError(), 'test:server-error-case');
    expect(capture).not.toHaveBeenCalled();
  });

  it('does not report a deliberate cancel', () => {
    const canceled = Object.assign(new Error('canceled'), { isAxiosError: true, code: 'ERR_CANCELED' });
    reportTransportFailure(canceled, 'test:cancel-case');
    expect(capture).not.toHaveBeenCalled();
  });

  it('does not report a plain, non-axios error', () => {
    reportTransportFailure(new Error('boom'), 'test:non-axios-case');
    expect(capture).not.toHaveBeenCalled();
  });

  // The reason this exists at all: an outage fails every in-flight request on a screen, and a
  // person staring at a dead screen retries. Without this, one bad minute files dozens of
  // near-identical events for the same context instead of one.
  it('rate-limits repeats for the same context, but not across different contexts', () => {
    reportTransportFailure(transportFailure(), 'test:throttle-case');
    expect(capture).toHaveBeenCalledTimes(1);

    reportTransportFailure(transportFailure(), 'test:throttle-case');
    expect(capture).toHaveBeenCalledTimes(1); // same context, well inside the window -- suppressed

    reportTransportFailure(transportFailure(), 'test:throttle-case-other-context');
    expect(capture).toHaveBeenCalledTimes(2); // a different context is not affected by the first
  });

  it('does nothing when Sentry has no DSN', () => {
    delete process.env.EXPO_PUBLIC_SENTRY_DSN;
    reportTransportFailure(transportFailure(), 'test:no-dsn-case');
    expect(capture).not.toHaveBeenCalled();
  });
});

describe('reportHandledEvent', () => {
  const capture = Sentry.captureMessage as jest.Mock;
  const original = process.env.EXPO_PUBLIC_SENTRY_DSN;

  beforeEach(() => {
    capture.mockClear();
    process.env.EXPO_PUBLIC_SENTRY_DSN = 'https://example.invalid/1';
  });

  afterEach(() => {
    if (original === undefined) delete process.env.EXPO_PUBLIC_SENTRY_DSN;
    else process.env.EXPO_PUBLIC_SENTRY_DSN = original;
  });

  it('sends an info-level message tagged with the context and carrying the details', () => {
    reportHandledEvent('something happened', 'some-context', { count: 2 });

    expect(capture).toHaveBeenCalledWith('something happened', {
      level: 'info',
      tags: { context: 'some-context' },
      contexts: { details: { count: 2 } },
    });
  });

  it('sends no contexts key when there are no details', () => {
    reportHandledEvent('something happened', 'some-context');

    expect(capture).toHaveBeenCalledWith('something happened', {
      level: 'info',
      tags: { context: 'some-context' },
    });
  });

  it('does nothing when Sentry has no DSN', () => {
    delete process.env.EXPO_PUBLIC_SENTRY_DSN;

    reportHandledEvent('something happened', 'some-context');

    expect(capture).not.toHaveBeenCalled();
  });
});
