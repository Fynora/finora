import { Keyboard, Platform } from 'react-native';
import { act, renderHook } from '@testing-library/react-native';
import { useKeyboardVisible } from './useKeyboardVisible';

function captureListeners() {
  const handlers: Record<string, () => void> = {};
  const removed: string[] = [];
  jest.spyOn(Keyboard, 'addListener').mockImplementation(((event: string, handler: () => void) => {
    handlers[event] = handler;
    return { remove: () => removed.push(event) };
  }) as never);
  return { handlers, removed };
}

afterEach(() => jest.restoreAllMocks());

describe('useKeyboardVisible', () => {
  it('on iOS follows the will-show and will-hide events, so the pill moves with the keyboard', () => {
    jest.replaceProperty(Platform, 'OS', 'ios');
    const { handlers } = captureListeners();
    const { result } = renderHook(() => useKeyboardVisible());
    expect(result.current).toBe(false);
    act(() => handlers.keyboardWillShow());
    expect(result.current).toBe(true);
    act(() => handlers.keyboardWillHide());
    expect(result.current).toBe(false);
  });

  it('on Android follows the did-show and did-hide events, which are the only ones Android sends', () => {
    jest.replaceProperty(Platform, 'OS', 'android');
    const { handlers } = captureListeners();
    const { result } = renderHook(() => useKeyboardVisible());
    act(() => handlers.keyboardDidShow());
    expect(result.current).toBe(true);
    act(() => handlers.keyboardDidHide());
    expect(result.current).toBe(false);
  });

  it('stops listening when unmounted', () => {
    jest.replaceProperty(Platform, 'OS', 'ios');
    const { removed } = captureListeners();
    const { unmount } = renderHook(() => useKeyboardVisible());
    unmount();
    expect(removed.sort()).toEqual(['keyboardWillHide', 'keyboardWillShow']);
  });
});
