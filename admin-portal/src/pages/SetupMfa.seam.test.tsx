import { describe, it, expect, vi, beforeEach, afterEach } from 'vitest';
import { render, screen, waitFor } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { MemoryRouter, Routes, Route } from 'react-router-dom';
import { QueryClient, QueryClientProvider } from '@tanstack/react-query';
import { AdminAuthProvider, useAdminAuth } from '../context/AdminAuthContext';
import { ProtectedRoute } from '../components/ProtectedRoute';
import SetupMfa from './SetupMfa';
import { api, rawApi, setAdminToken } from '../api/client';

vi.mock('../context/NotificationContext', () => ({
  useNotify: () => ({ success: vi.fn(), error: vi.fn() }),
}));
vi.mock('qrcode', () => ({ default: { toDataURL: vi.fn().mockResolvedValue('data:image/png;base64,AAAA') } }));

/**
 * A seam test: the REAL auth provider, the REAL API client (its request and response interceptors,
 * unwrap and redirect logic), the REAL route guard and the REAL setup screen, with only the network
 * faked at axios's adapter. Each piece has unit tests of its own; what none of them can show is what
 * happens when the client's interceptor and the auth context both react to the same 403, which is
 * exactly what an admin who has not enrolled produces on the /users/me/access call after login.
 */

type Fake = { status: number; body: unknown };

function envelope(data: unknown): Fake {
  return { status: 200, body: { success: true, data } };
}

function refused(errorCode: string): Fake {
  return { status: 403, body: { success: false, message: 'Set up two-factor authentication to continue.', errorCode } };
}

/** Routes a request to a canned answer and turns non-2xx into the rejection axios would raise. */
function installBackend(routes: Record<string, () => Fake>) {
  // Two axios instances: `api` (interceptors, used for everything) and `rawApi` (no interceptors,
  // used only for the silent refresh, see client.ts). Both go to the same fake backend.
  const adapter = async (config: any) => {
    const key = `${(config.method ?? 'get').toUpperCase()} ${config.url}`;
    const handler = routes[key];
    if (!handler) throw new Error(`unexpected request in seam test: ${key}`);
    const { status, body } = handler();
    const response = { data: body, status, statusText: String(status), headers: {}, config };
    if (status >= 400) {
      const err: any = new Error(`Request failed with status code ${status}`);
      err.isAxiosError = true;
      err.response = response;
      err.config = config;
      throw err;
    }
    return response;
  };
  api.defaults.adapter = adapter;
  rawApi.defaults.adapter = adapter;
}

function Probe() {
  const { login, mfaEnrollmentRequired, token } = useAdminAuth();
  return (
    <div>
      <button onClick={() => { void login('admin@example.test', 'not-a-real-password').catch(() => {}); }}>sign in</button>
      <span data-testid="state">{`token=${token ?? 'none'} required=${mfaEnrollmentRequired}`}</span>
    </div>
  );
}

function renderApp(initialPath: string) {
  const queryClient = new QueryClient({ defaultOptions: { queries: { retry: false } } });
  return render(
    <QueryClientProvider client={queryClient}>
      <AdminAuthProvider>
        <MemoryRouter initialEntries={[initialPath]}>
          <Routes>
            <Route path="/login" element={<Probe />} />
            <Route path="/setup-mfa" element={<ProtectedRoute allowMfaSetup><SetupMfa /></ProtectedRoute>} />
            <Route path="/" element={<ProtectedRoute><div>Dashboard</div></ProtectedRoute>} />
          </Routes>
        </MemoryRouter>
      </AdminAuthProvider>
    </QueryClientProvider>
  );
}

describe('forced two-factor setup: auth context + API client interceptor + route guard together', () => {
  const realLocation = window.location;
  const originalAdapter = api.defaults.adapter;
  const originalRawAdapter = rawApi.defaults.adapter;

  beforeEach(() => {
    localStorage.clear();
    setAdminToken(null);
    // No cookie to recover a session from on mount: the ordinary "not logged in" start.
    installBackend({ 'POST /auth/refresh': () => refused('AUTH_004') });
    Object.defineProperty(window, 'location', {
      configurable: true, writable: true, value: { pathname: '/login', href: 'http://localhost/login' },
    });
  });

  afterEach(() => {
    api.defaults.adapter = originalAdapter;
    rawApi.defaults.adapter = originalRawAdapter;
    Object.defineProperty(window, 'location', { configurable: true, writable: true, value: realLocation });
  });

  it('an unenrolled admin who signs in is routed to the setup screen WITHOUT a hard page reload', async () => {
    installBackend({
      'POST /auth/refresh': () => refused('AUTH_004'),
      'POST /auth/login': () => envelope({
        token: 'tok', refreshToken: 'r', email: 'admin@example.test', fullName: 'Admin', phoneVerified: true }),
      'GET /users/me/access': () => refused('MFA_ENROLLMENT_REQUIRED'),
    });
    const user = userEvent.setup();
    renderApp('/login');
    await waitFor(() => expect(screen.getByTestId('state')).toBeInTheDocument());

    await user.click(screen.getByRole('button', { name: 'sign in' }));

    // The context already handles this 403 and ProtectedRoute routes within the app. A hard
    // navigation on top of that reloads the whole page mid-login (the access token lives in memory
    // only, so it is recovered from the refresh cookie) and races the in-app navigation.
    await waitFor(() => expect(screen.getByTestId('state')).toHaveTextContent('token=tok required=true'));
    expect(window.location.href).toBe('http://localhost/login');
  });

  it('a page reload on the setup screen keeps the session and shows the setup screen', async () => {
    installBackend({
      'POST /auth/refresh': () => envelope({ token: 'recovered', refreshToken: 'r' }),
      'GET /users/me/access': () => refused('MFA_ENROLLMENT_REQUIRED'),
    });
    window.location = { pathname: '/setup-mfa', href: 'http://localhost/setup-mfa' } as any;

    renderApp('/setup-mfa');

    expect(await screen.findByRole('heading', { name: /set up two-factor authentication/i })).toBeInTheDocument();
    expect(window.location.href).toBe('http://localhost/setup-mfa');
  });

  it('a mid-session call from any other page still redirects, since nothing else handles it there', async () => {
    installBackend({
      'POST /auth/refresh': () => refused('AUTH_004'),
      'GET /admin/users': () => refused('MFA_ENROLLMENT_REQUIRED'),
    });
    window.location = { pathname: '/users', href: 'http://localhost/users' } as any;

    await api.get('/admin/users').catch(() => {});

    expect(window.location.href).toBe('/setup-mfa');
  });
});
