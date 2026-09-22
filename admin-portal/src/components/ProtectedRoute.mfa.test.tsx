import { describe, it, expect, vi, beforeEach } from 'vitest';
import { render, screen } from '@testing-library/react';
import { MemoryRouter, Routes, Route } from 'react-router-dom';
import { ProtectedRoute } from './ProtectedRoute';
import { useAdminAuth } from '../context/AdminAuthContext';
import { mockAdminAuthState } from '../test/mockAdminAuth';

vi.mock('../context/AdminAuthContext', () => ({
  useAdminAuth: vi.fn(),
}));

// CASA 3.3.1: an admin who still has to set up two-factor authentication is sent there from every
// protected route, because the backend refuses everything else until they do.
describe('ProtectedRoute: two-factor enrolment required', () => {
  beforeEach(() => {
    vi.mocked(useAdminAuth).mockReset();
  });

  it('sends an admin who still has to enrol to /setup-mfa', () => {
    vi.mocked(useAdminAuth).mockReturnValue(
      mockAdminAuthState({ token: 'tok', loading: false, mfaEnrollmentRequired: true }));

    render(
      <MemoryRouter initialEntries={['/users']}>
        <Routes>
          <Route path="/users" element={<ProtectedRoute><div>Protected content</div></ProtectedRoute>} />
          <Route path="/setup-mfa" element={<div>Setup page</div>} />
        </Routes>
      </MemoryRouter>
    );

    expect(screen.getByText('Setup page')).toBeInTheDocument();
    expect(screen.queryByText('Protected content')).not.toBeInTheDocument();
  });

  it('lets the setup screen itself render while enrolment is required (allowMfaSetup)', () => {
    vi.mocked(useAdminAuth).mockReturnValue(
      mockAdminAuthState({ token: 'tok', loading: false, mfaEnrollmentRequired: true }));

    render(
      <MemoryRouter initialEntries={['/setup-mfa']}>
        <Routes>
          <Route path="/setup-mfa" element={<ProtectedRoute allowMfaSetup><div>Setup page</div></ProtectedRoute>} />
        </Routes>
      </MemoryRouter>
    );

    expect(screen.getByText('Setup page')).toBeInTheDocument();
  });

  it('checks phone verification before two-factor setup, matching the order the backend applies them', () => {
    vi.mocked(useAdminAuth).mockReturnValue(mockAdminAuthState({
      token: 'tok', loading: false, phoneVerified: false, mfaEnrollmentRequired: true }));

    render(
      <MemoryRouter initialEntries={['/users']}>
        <Routes>
          <Route path="/users" element={<ProtectedRoute><div>Protected content</div></ProtectedRoute>} />
          <Route path="/verify-phone" element={<div>Verify phone page</div>} />
          <Route path="/setup-mfa" element={<div>Setup page</div>} />
        </Routes>
      </MemoryRouter>
    );

    expect(screen.getByText('Verify phone page')).toBeInTheDocument();
  });

  it('does not touch an admin who has already enrolled', () => {
    vi.mocked(useAdminAuth).mockReturnValue(
      mockAdminAuthState({ token: 'tok', loading: false, mfaEnrollmentRequired: false }));

    render(
      <MemoryRouter initialEntries={['/users']}>
        <Routes>
          <Route path="/users" element={<ProtectedRoute><div>Protected content</div></ProtectedRoute>} />
          <Route path="/setup-mfa" element={<div>Setup page</div>} />
        </Routes>
      </MemoryRouter>
    );

    expect(screen.getByText('Protected content')).toBeInTheDocument();
  });
});
