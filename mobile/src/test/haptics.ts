import * as Haptics from 'expo-haptics';

type Kind = 'success' | 'warning' | 'error' | 'selection' | 'impact';

/** Counts every haptic the app asked for since the last jest.clearAllMocks (setup.ts runs one
 *  before each test), by kind. */
export function hapticCalls(): Record<Kind, number> {
  const notes = jest.mocked(Haptics.notificationAsync).mock.calls.map(([t]) => t);
  return {
    success: notes.filter((t) => t === Haptics.NotificationFeedbackType.Success).length,
    warning: notes.filter((t) => t === Haptics.NotificationFeedbackType.Warning).length,
    error: notes.filter((t) => t === Haptics.NotificationFeedbackType.Error).length,
    selection: jest.mocked(Haptics.selectionAsync).mock.calls.length,
    impact: jest.mocked(Haptics.impactAsync).mock.calls.length,
  };
}

/** Forgets the haptics asked for so far, for a test that has to walk a flow (one that fires its
 *  own) before reaching the control it is about. Only the expo-haptics mocks are cleared. */
export function resetHapticCalls(): void {
  jest.mocked(Haptics.notificationAsync).mockClear();
  jest.mocked(Haptics.selectionAsync).mockClear();
  jest.mocked(Haptics.impactAsync).mockClear();
}

/** Exactly these haptics and no others: kind and count, every kind checked. */
export function expectHaptics(expected: Partial<Record<Kind, number>>): void {
  expect(hapticCalls()).toEqual({ success: 0, warning: 0, error: 0, selection: 0, impact: 0, ...expected });
}
