import { describe, it, expect } from 'vitest';
import { loginPathFor, safeReturnPath } from './returnTo';

describe('safeReturnPath', () => {
  it('keeps a page on this site, with its query', () => {
    expect(safeReturnPath('/held-statements/HLD-2026-100001')).toBe('/held-statements/HLD-2026-100001');
    expect(safeReturnPath('/held-imports?job=abc')).toBe('/held-imports?job=abc');
  });

  it('refuses anything that could leave the site', () => {
    expect(safeReturnPath('//evil.example/x')).toBeNull();
    expect(safeReturnPath('/\\evil.example')).toBeNull();
    expect(safeReturnPath('https://evil.example')).toBeNull();
    expect(safeReturnPath('javascript:alert(1)')).toBeNull();
    expect(safeReturnPath('held-statements')).toBeNull();
  });

  it('refuses the sign-in screens and the dashboard, so nothing loops or carries a pointless next', () => {
    for (const path of ['/login', '/login?next=%2Fusers', '/setup', '/verify-phone', '/setup-mfa', '/reset-password', '/forgot-password', '/']) {
      expect(safeReturnPath(path)).toBeNull();
    }
    expect(safeReturnPath('/login-history')).toBe('/login-history');
  });

  it('refuses nothing at all', () => {
    expect(safeReturnPath(null)).toBeNull();
    expect(safeReturnPath(undefined)).toBeNull();
    expect(safeReturnPath('')).toBeNull();
  });
});

describe('loginPathFor', () => {
  it('carries the page in next, encoded', () => {
    expect(loginPathFor('/held-imports?job=abc')).toBe('/login?next=%2Fheld-imports%3Fjob%3Dabc');
  });

  it('is plain /login when there is nothing worth returning to', () => {
    expect(loginPathFor('/')).toBe('/login');
    expect(loginPathFor('/login')).toBe('/login');
  });
});
