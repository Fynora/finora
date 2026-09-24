import { describe, it, expect } from 'vitest';
import { safeReturnTo, returnToFromState } from './returnTo';

describe('safeReturnTo', () => {
  it.each([
    ['/app', '/app'],
    ['/app/imports/job-123', '/app/imports/job-123'],
    ['/app/settings', '/app/settings'],
    ['/app/settings?tab=notifications', '/app/settings?tab=notifications'],
    ['/app/transactions?month=2026-09&q=a%20b', '/app/transactions?month=2026-09&q=a%20b'],
  ])('accepts the in-app path %s', (input, expected) => {
    expect(safeReturnTo(input)).toBe(expected);
  });

  it('drops a #fragment, keeping only path + search', () => {
    expect(safeReturnTo('/app/settings?tab=notifications#email')).toBe('/app/settings?tab=notifications');
  });

  it.each([
    ['absolute https URL', 'https://evil.example/app'],
    ['absolute same-host URL', `${window.location.origin}/app`],
    ['protocol-relative', '//evil.example/app'],
    ['protocol-relative to a path that looks in-app', '//evil.example'],
    ['backslash protocol-relative', '/\\evil.example'],
    ['backslash inside an /app path', '/app\\..\\..\\evil'],
    ['javascript: scheme', 'javascript:alert(1)'],
    ['data: scheme', 'data:text/html,hi'],
    ['relative without leading slash', 'app/settings'],
    ['public page', '/terms'],
    ['auth page', '/auth'],
    ['prefix look-alike', '/application'],
    ['prefix look-alike 2', '/app-evil'],
    ['dot-segment escape', '/app/../evil'],
    ['encoded dot-segment escape', '/app/%2e%2e/evil'],
    ['tab-smuggled protocol-relative', '/\t/evil.example'],
    ['newline', '/app/settings\n'],
    ['empty string', ''],
    ['root', '/'],
  ])('rejects %s', (_label, input) => {
    expect(safeReturnTo(input)).toBeNull();
  });

  it.each([[undefined], [null], [42], [{ pathname: '/app' }], [['/app']]])('rejects the non-string %j', (input) => {
    expect(safeReturnTo(input)).toBeNull();
  });

  it('rejects an absurdly long value', () => {
    expect(safeReturnTo(`/app/${'a'.repeat(5000)}`)).toBeNull();
  });
});

describe('returnToFromState', () => {
  it('reads a valid from', () => {
    expect(returnToFromState({ from: '/app/imports/x' })).toBe('/app/imports/x');
  });

  it('returns null for missing/invalid state', () => {
    expect(returnToFromState(null)).toBeNull();
    expect(returnToFromState(undefined)).toBeNull();
    expect(returnToFromState('/app')).toBeNull();
    expect(returnToFromState({ fromLogin: true })).toBeNull();
    expect(returnToFromState({ from: '//evil.example' })).toBeNull();
  });
});
