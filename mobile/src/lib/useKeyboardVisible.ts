import { useEffect, useState } from 'react';
import { Keyboard, Platform } from 'react-native';

/**
 * Whether the on-screen keyboard is up. iOS announces the keyboard before it moves (will-show),
 * so anything following it can move with it; Android only reports it once it is up (did-show).
 */
export function useKeyboardVisible(): boolean {
  const [visible, setVisible] = useState(false);
  useEffect(() => {
    const ios = Platform.OS === 'ios';
    const show = Keyboard.addListener(ios ? 'keyboardWillShow' : 'keyboardDidShow', () => setVisible(true));
    const hide = Keyboard.addListener(ios ? 'keyboardWillHide' : 'keyboardDidHide', () => setVisible(false));
    return () => {
      show.remove();
      hide.remove();
    };
  }, []);
  return visible;
}
