import { act, renderHook } from '@testing-library/react-native';
import * as Haptics from 'expo-haptics';
import * as SecureStore from 'expo-secure-store';
import {
  __resetHapticsForTests, __setHapticsStateForTests, hapticError, hapticImpact, hapticSelection,
  hapticSelectionIfChanged, hapticSuccess, hapticWarning, primeHapticsSetting, setHapticsEnabled,
  useHapticsEnabled,
} from './haptics';
import { expectHaptics } from '../test/haptics';

const haptics = Haptics as jest.Mocked<typeof Haptics>;
const store = SecureStore as jest.Mocked<typeof SecureStore> & { __store: Map<string, string> };
const flush = () => act(async () => {});

describe('haptics', () => {
  it('fires a success notification', () => {
    hapticSuccess();
    expect(haptics.notificationAsync).toHaveBeenCalledWith(Haptics.NotificationFeedbackType.Success);
  });

  it('fires a warning notification', () => {
    hapticWarning();
    expect(haptics.notificationAsync).toHaveBeenCalledWith(Haptics.NotificationFeedbackType.Warning);
  });

  it('fires an error notification, distinct from a warning', () => {
    hapticError();
    expect(haptics.notificationAsync).toHaveBeenCalledWith(Haptics.NotificationFeedbackType.Error);
  });

  it('fires a selection change', () => {
    hapticSelection();
    expect(haptics.selectionAsync).toHaveBeenCalledTimes(1);
  });

  it('fires a medium impact', () => {
    hapticImpact();
    expect(haptics.impactAsync).toHaveBeenCalledWith(Haptics.ImpactFeedbackStyle.Medium);
  });

  // A device/OS where the native call actually rejects (vibration hardware unavailable, a
  // permission quirk on some Android OEM build) must not leave the rejection unhandled -- these
  // are fire-and-forget by design, and an unhandled rejection would otherwise surface as noise
  // (a dev-mode warning, or crash-adjacent reporting once Sentry is wired up) for a failure no
  // caller was ever going to react to.
  it('does not produce an unhandled rejection when the native call rejects', async () => {
    haptics.notificationAsync.mockRejectedValueOnce(new Error('native call failed'));
    expect(() => hapticSuccess()).not.toThrow();
    // Let the rejected promise's own microtask (and this function's .catch) settle before the
    // test ends -- otherwise a rejection surfacing after the test completes wouldn't be caught by
    // anything, defeating the point of the assertion above.
    await Promise.resolve().then().then();
  });
});

describe('the haptics switch', () => {
  beforeEach(() => __resetHapticsForTests());

  it('fires nothing before the stored setting has been read', () => {
    hapticSuccess();
    hapticSelection();
    hapticImpact();
    expectHaptics({});
  });

  it('a missing value means on', async () => {
    primeHapticsSetting();
    await flush();
    hapticSuccess();
    expectHaptics({ success: 1 });
  });

  it('a failed read means on', async () => {
    store.getItemAsync.mockRejectedValueOnce(new Error('keychain'));
    primeHapticsSetting();
    await flush();
    hapticSelection();
    expectHaptics({ selection: 1 });
  });

  it('a stored "off" silences every kind', async () => {
    store.__store.set('finora_haptics', 'off');
    primeHapticsSetting();
    await flush();
    hapticSuccess(); hapticWarning(); hapticError(); hapticSelection(); hapticImpact();
    expectHaptics({});
  });

  it('turning it on fires exactly one selection, after the state is on, and saves "on"', async () => {
    __setHapticsStateForTests('off');
    setHapticsEnabled(true);
    expectHaptics({ selection: 1 });
    await flush();
    expect(store.__store.get('finora_haptics')).toBe('on');
  });

  it('turning it off fires nothing and saves "off"', async () => {
    __setHapticsStateForTests('on');
    setHapticsEnabled(false);
    expectHaptics({});
    await flush();
    expect(store.__store.get('finora_haptics')).toBe('off');
  });

  it('a choice made before the read finishes is not overwritten by it', async () => {
    store.__store.set('finora_haptics', 'on');
    primeHapticsSetting();
    setHapticsEnabled(false);
    await flush();
    hapticSuccess();
    expectHaptics({});
  });

  it('fast toggling leaves the last position in memory and in storage', async () => {
    __setHapticsStateForTests('on');
    setHapticsEnabled(false);
    setHapticsEnabled(true);
    setHapticsEnabled(false);
    await flush();
    expect(store.__store.get('finora_haptics')).toBe('off');
    hapticSuccess();
    expectHaptics({ selection: 1 });
  });

  it('a failed save does not move the switch back', async () => {
    __setHapticsStateForTests('on');
    store.setItemAsync.mockRejectedValueOnce(new Error('keychain'));
    setHapticsEnabled(false);
    await flush();
    hapticSuccess();
    expectHaptics({});
  });

  it('survives a restart: a fresh read finds the saved value', async () => {
    __setHapticsStateForTests('on');
    setHapticsEnabled(false);
    await flush();
    __resetHapticsForTests();
    primeHapticsSetting();
    await flush();
    hapticSuccess();
    expectHaptics({});
  });

  it('a rejecting native call does not throw', async () => {
    __setHapticsStateForTests('on');
    jest.mocked(Haptics.notificationAsync).mockRejectedValueOnce(new Error('no vibrator'));
    expect(() => hapticSuccess()).not.toThrow();
    await flush();
  });

  it('useHapticsEnabled follows the switch', () => {
    __setHapticsStateForTests('on');
    const { result } = renderHook(() => useHapticsEnabled());
    expect(result.current).toBe(true);
    act(() => setHapticsEnabled(false));
    expect(result.current).toBe(false);
  });

  it('hapticSelectionIfChanged fires only when the value differs', () => {
    __setHapticsStateForTests('on');
    hapticSelectionIfChanged('ALL', 'ALL');
    expectHaptics({});
    hapticSelectionIfChanged('ALL', 'INCOME');
    expectHaptics({ selection: 1 });
  });
});
