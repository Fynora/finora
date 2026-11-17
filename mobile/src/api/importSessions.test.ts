import { importApi } from './endpoints';
import { api } from './client';

jest.mock('./client', () => ({
  api: { get: jest.fn() },
  rawApi: { get: jest.fn() },
}));

const get = api.get as jest.Mock;

/**
 * The phone lists statements covering more than one account too, as ones to finish on the web
 * (Sid, 2026-11-17). The server keeps them from an app that does not ask, so this app has to ask.
 * Mirrors the web app's src/api/importSessions.test.ts.
 */
describe('importApi.listSessions', () => {
  beforeEach(() => get.mockReset().mockResolvedValue({ data: [] }));

  it('asks for statements covering more than one account', async () => {
    await importApi.listSessions();

    expect(get).toHaveBeenCalledWith('/import/sessions', { params: { includeMultiAccount: true } });
  });
});
