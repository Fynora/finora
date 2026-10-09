import { useSyncExternalStore } from 'react';
import { AccessibilityInfo } from 'react-native';

/**
 * iOS "Reduce Transparency" as an app-wide store. `null` = not known yet, and every glass
 * consumer renders the SOLID look for null -- so a user who has the setting on never sees a
 * frame of glass. primeReduceTransparency() runs at app start (App.tsx), so the answer normally
 * arrives under the launch animation. A live event always wins over a late initial query.
 * Android has no such setting; RN resolves false there.
 */
let value: boolean | null = null;
let primed = false;
let eventSeen = false;
const listeners = new Set<() => void>();
const set = (v: boolean) => { value = v; listeners.forEach((l) => l()); };

export function primeReduceTransparency(): void {
  if (primed) return;
  primed = true;
  // One subscription for the whole app, kept for its lifetime on purpose -- not one per component.
  AccessibilityInfo.addEventListener('reduceTransparencyChanged', (v: boolean) => { eventSeen = true; set(v); });
  AccessibilityInfo.isReduceTransparencyEnabled()
    .then((v) => { if (!eventSeen) set(v); })
    .catch(() => { if (!eventSeen) set(false); });
}

export function useReduceTransparency(): boolean | null {
  primeReduceTransparency();
  return useSyncExternalStore(
    (l) => { listeners.add(l); return () => listeners.delete(l); },
    () => value,
  );
}

export function __resetReduceTransparencyForTests(): void {
  value = null; primed = false; eventSeen = false; listeners.clear();
}
