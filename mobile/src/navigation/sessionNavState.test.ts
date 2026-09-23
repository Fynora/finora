import type { NavigationState } from '@react-navigation/native';
import AsyncStorage from '@react-native-async-storage/async-storage';
import { clearSessionNavState, getSessionNavState, saveSessionNavState } from './sessionNavState';

const state = { index: 1, routes: [{ name: 'Home' }, { name: 'Transactions' }] } as unknown as NavigationState;

afterEach(() => clearSessionNavState());

describe('sessionNavState', () => {
  it('starts empty, so a fresh process opens on the default route', () => {
    expect(getSessionNavState()).toBeUndefined();
  });

  it('returns what was saved, until it is cleared', () => {
    saveSessionNavState(state);
    expect(getSessionNavState()).toBe(state);

    clearSessionNavState();
    expect(getSessionNavState()).toBeUndefined();
  });

  it('never touches disk -- a killed process must not be able to restore it', async () => {
    const setItem = jest.spyOn(AsyncStorage, 'setItem');
    saveSessionNavState(state);
    expect(setItem).not.toHaveBeenCalled();
    setItem.mockRestore();
  });
});
