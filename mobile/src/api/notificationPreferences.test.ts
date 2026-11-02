import { notificationPreferencesApi } from './endpoints';
import { api } from './client';

jest.mock('./client', () => ({
  api: { get: jest.fn().mockResolvedValue({ data: [] }), put: jest.fn().mockResolvedValue({ data: [] }) },
  rawApi: {},
}));

const get = (api.get as jest.Mock);
const put = (api.put as jest.Mock);

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

    expect(get).toHaveBeenCalledWith('/notification-preferences', { params: { categories: 'FINANCIAL,REMINDERS,MARKETING' } });
  });

  it('sends the category with a change, and asks for both categories back', async () => {
    await notificationPreferencesApi.set('REMINDERS', 'PUSH', false);

    expect(put).toHaveBeenCalledWith('/notification-preferences',
      { category: 'REMINDERS', channel: 'PUSH', enabled: false },
      { params: { categories: 'FINANCIAL,REMINDERS,MARKETING' } });
  });
});
