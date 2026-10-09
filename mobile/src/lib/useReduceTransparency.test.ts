import { act, renderHook } from '@testing-library/react-native';
import { AccessibilityInfo } from 'react-native';
import {
  __resetReduceTransparencyForTests, primeReduceTransparency, useReduceTransparency,
} from './useReduceTransparency';

let emit: ((v: boolean) => void) | undefined;
let resolveQuery!: (v: boolean) => void;
let rejectQuery!: (e: Error) => void;

beforeEach(() => {
  __resetReduceTransparencyForTests();
  emit = undefined;
  jest.spyOn(AccessibilityInfo, 'addEventListener').mockImplementation((_e: any, cb: any) => {
    emit = cb;
    return { remove: jest.fn() } as any;
  });
  jest.spyOn(AccessibilityInfo, 'isReduceTransparencyEnabled').mockReturnValue(
    new Promise((res, rej) => { resolveQuery = res; rejectQuery = rej; }),
  );
});
afterEach(() => jest.restoreAllMocks());

it('is null (unknown, render solid) until the OS answers', () => {
  primeReduceTransparency();
  const { result } = renderHook(() => useReduceTransparency());
  expect(result.current).toBeNull();
});

it.each([true, false])('reports the OS answer %s', async (v) => {
  primeReduceTransparency();
  const { result } = renderHook(() => useReduceTransparency());
  await act(async () => resolveQuery(v));
  expect(result.current).toBe(v);
});

it('follows live changes both ways without restart', async () => {
  primeReduceTransparency();
  const { result } = renderHook(() => useReduceTransparency());
  await act(async () => resolveQuery(false));
  act(() => emit!(true));
  expect(result.current).toBe(true);
  act(() => emit!(false));
  expect(result.current).toBe(false);
});

it('a late initial query never overwrites a newer event', async () => {
  primeReduceTransparency();
  const { result } = renderHook(() => useReduceTransparency());
  act(() => emit!(true));
  await act(async () => resolveQuery(false));
  expect(result.current).toBe(true);
});

it('a rejected query resolves to false (glass), not stuck on null', async () => {
  primeReduceTransparency();
  const { result } = renderHook(() => useReduceTransparency());
  await act(async () => rejectQuery(new Error('x')));
  expect(result.current).toBe(false);
});

it('priming twice queries the OS once', () => {
  primeReduceTransparency();
  primeReduceTransparency();
  expect(AccessibilityInfo.isReduceTransparencyEnabled).toHaveBeenCalledTimes(1);
});

it('the hook primes on its own if App.tsx did not', () => {
  renderHook(() => useReduceTransparency());
  expect(AccessibilityInfo.isReduceTransparencyEnabled).toHaveBeenCalledTimes(1);
});

it('unmounting stops updates to that hook (no act() warning on a later event)', async () => {
  primeReduceTransparency();
  const { unmount } = renderHook(() => useReduceTransparency());
  unmount();
  await act(async () => resolveQuery(true));
  act(() => emit!(false));
});
