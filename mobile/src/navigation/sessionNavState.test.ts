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
    expect(getSessionNavState()).toEqual(state);

    clearSessionNavState();
    expect(getSessionNavState()).toBeUndefined();
  });

  it('never touches disk -- a killed process must not be able to restore it', async () => {
    const setItem = jest.spyOn(AsyncStorage, 'setItem');
    saveSessionNavState(state);
    expect(setItem).not.toHaveBeenCalled();
    setItem.mockRestore();
  });

  describe('params -- restored only for screens whose params are plain ids', () => {
    // A remount replays whatever params are restored: Import would re-run its shared-file import
    // and re-enter a password-carrying re-import, Home would re-open Add Transaction. So nothing is
    // restored unless a screen is explicitly listed as safe -- new screens are stripped by default.
    const deep = {
      index: 4,
      routes: [
        { name: 'Home', params: { openAddTransaction: true, nonce: 7 } },
        { name: 'Transactions', params: { filters: { month: '2026-09' } } },
        {
          name: 'Import',
          params: { reimport: { password: 'secret', nonce: 1 }, sharedFile: { file: 'x', nonce: 2 } },
        },
        { name: 'Insights' },
        {
          name: 'More',
          state: {
            index: 2,
            routes: [
              { name: 'MoreHome' },
              { name: 'VerifyEmailChange', params: { sessionId: 's', token: 'one-time' } },
              { name: 'SupportTicketDetail', params: { ticketId: 't-1' } },
              { name: 'SettingsBankSyncConfirm', params: { linkId: 'l-1' } },
            ],
          },
        },
      ],
    } as unknown as NavigationState;

    it('strips one-shot and sensitive params, at every depth', () => {
      saveSessionNavState(deep);
      const json = JSON.stringify(getSessionNavState());

      expect(json).not.toContain('secret');
      expect(json).not.toContain('one-time');
      expect(json).not.toContain('sharedFile');
      expect(json).not.toContain('openAddTransaction');
      expect(json).not.toContain('2026-09');
    });

    it('keeps the id params of the two detail screens that cannot render without them', () => {
      saveSessionNavState(deep);
      const more = (getSessionNavState() as unknown as { routes: { name: string; state?: { routes: { name: string; params?: unknown }[] } }[] }).routes[4];

      expect(more.state?.routes[2]).toEqual({ name: 'SupportTicketDetail', params: { ticketId: 't-1' } });
      expect(more.state?.routes[3]).toEqual({ name: 'SettingsBankSyncConfirm', params: { linkId: 'l-1' } });
    });

    it('keeps the navigator position itself (indexes and route names)', () => {
      saveSessionNavState(deep);
      const saved = getSessionNavState() as unknown as { index: number; routes: { name: string }[] };

      expect(saved.index).toBe(4);
      expect(saved.routes.map((r) => r.name)).toEqual(['Home', 'Transactions', 'Import', 'Insights', 'More']);
    });

    it('does not mutate the live state React Navigation owns', () => {
      saveSessionNavState(deep);
      expect(JSON.stringify(deep)).toContain('secret');
    });
  });
});
