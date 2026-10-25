import { describe, it, expect, vi, beforeEach } from 'vitest';
import { notificationPreferencesApi } from './endpoints';
import { api } from './client';

vi.mock('./client', () => ({
  api: { get: vi.fn().mockResolvedValue({ data: [] }), put: vi.fn().mockResolvedValue({ data: [] }) },
  rawApi: {},
}));

/* eslint-disable @typescript-eslint/unbound-method -- the mock factory's object literal, no `this` */
const get = vi.mocked(api.get);
const put = vi.mocked(api.put);
/* eslint-enable @typescript-eslint/unbound-method */

/**
 * Gate 2b PR 5: the server lists REMINDERS only for a client that asks for it, because the 1.0.0 app
 * draws one switch per channel. This build draws the reminders switch, so it must ask, on the list
 * and on the save (whose answer replaces the list on screen).
 */
describe('notificationPreferencesApi', () => {
  beforeEach(() => {
    get.mockClear();
    put.mockClear();
  });

  it('asks for FINANCIAL and REMINDERS when listing', async () => {
    await notificationPreferencesApi.list();

    expect(get).toHaveBeenCalledWith('/notification-preferences', { params: { categories: 'FINANCIAL,REMINDERS' } });
  });

  it('sends the category with a change, and asks for both categories back', async () => {
    await notificationPreferencesApi.set('REMINDERS', 'PUSH', false);

    expect(put).toHaveBeenCalledWith('/notification-preferences',
      { category: 'REMINDERS', channel: 'PUSH', enabled: false },
      { params: { categories: 'FINANCIAL,REMINDERS' } });
  });
});
