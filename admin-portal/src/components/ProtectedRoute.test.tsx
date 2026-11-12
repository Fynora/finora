import { describe, it, expect, vi, beforeEach } from 'vitest';
import { render, screen } from '@testing-library/react';
import { MemoryRouter, Routes, Route, useLocation } from 'react-router-dom';
import { ProtectedRoute, RequirePermission } from './ProtectedRoute';
import { useAdminAuth } from '../context/AdminAuthContext';
import { mockAdminAuthState } from '../test/mockAdminAuth';

vi.mock('../context/AdminAuthContext', () => ({
  useAdminAuth: vi.fn(),
}));

function renderProtected() {
  return render(
    <MemoryRouter initialEntries={['/users']}>
      <Routes>
        <Route
          path="/users"
          element={
            <ProtectedRoute>
              <div>Protected content</div>
            </ProtectedRoute>
          }
        />
        <Route path="/login" element={<div>Login page</div>} />
      </Routes>
    </MemoryRouter>
  );
}

describe('ProtectedRoute', () => {
  beforeEach(() => {
    vi.mocked(useAdminAuth).mockReset();
  });

  it('shows a loading state instead of redirecting while the access check is in flight', () => {
    vi.mocked(useAdminAuth).mockReturnValue(mockAdminAuthState({ token: 'tok', loading: true }));

    renderProtected();

    expect(screen.getByText('Loading…')).toBeInTheDocument();
    expect(screen.queryByText('Login page')).not.toBeInTheDocument();
    expect(screen.queryByText('Protected content')).not.toBeInTheDocument();
  });

  it('redirects to /login once loading has finished and there is no token', () => {
    vi.mocked(useAdminAuth).mockReturnValue(mockAdminAuthState({ token: null, loading: false }));

    renderProtected();

    expect(screen.getByText('Login page')).toBeInTheDocument();
  });

  it('remembers the page being opened, so signing in lands on it', () => {
    vi.mocked(useAdminAuth).mockReturnValue(mockAdminAuthState({ token: null, loading: false }));
    function LoginProbe() {
      const location = useLocation();
      return <div>Login at {location.search}</div>;
    }

    render(
      <MemoryRouter initialEntries={['/held-imports?job=job-1']}>
        <Routes>
          <Route path="/held-imports" element={<ProtectedRoute><div>Held imports</div></ProtectedRoute>} />
          <Route path="/login" element={<LoginProbe />} />
        </Routes>
      </MemoryRouter>
    );

    expect(screen.getByText('Login at ?next=%2Fheld-imports%3Fjob%3Djob-1')).toBeInTheDocument();
  });

  it('renders the protected content once loading has finished and a token is present', () => {
    vi.mocked(useAdminAuth).mockReturnValue(mockAdminAuthState({ token: 'tok', loading: false }));

    renderProtected();

    expect(screen.getByText('Protected content')).toBeInTheDocument();
  });
});

describe('RequirePermission', () => {
  beforeEach(() => {
    vi.mocked(useAdminAuth).mockReset();
  });

  it('shows an access-denied message when the account lacks the required permission', () => {
    vi.mocked(useAdminAuth).mockReturnValue(mockAdminAuthState({
      hasPermission: (p: string) => p !== 'USER_VIEW',
    }));

    render(
      <RequirePermission permission="USER_VIEW">
        <div>Section content</div>
      </RequirePermission>
    );

    expect(screen.getByText("You don't have access to this section")).toBeInTheDocument();
    expect(screen.queryByText('Section content')).not.toBeInTheDocument();
  });

  it('renders the section content when the account holds the required permission', () => {
    vi.mocked(useAdminAuth).mockReturnValue(mockAdminAuthState({
      hasPermission: (p: string) => p === 'USER_VIEW',
    }));

    render(
      <RequirePermission permission="USER_VIEW">
        <div>Section content</div>
      </RequirePermission>
    );

    expect(screen.getByText('Section content')).toBeInTheDocument();
  });
});
