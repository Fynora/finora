import { importApi } from './endpoints';
import { api } from './client';

jest.mock('./client', () => ({
  api: { delete: jest.fn() },
  rawApi: { delete: jest.fn() },
}));

const del = api.delete as jest.Mock;

/**
 * What a discard tells the server. The phone's own deletion of a statement it cannot import must
 * never say the customer confirmed it; the discard sheet always does. Mirrors the web's test.
 */
describe('importApi.discardSession', () => {
  beforeEach(() => del.mockReset().mockResolvedValue({ data: null }));

  it('sends the reason and that the customer confirmed it, from the discard sheet', async () => {
    await importApi.discardSession('s1', 'LATER', true);
    expect(del).toHaveBeenCalledWith('/import/sessions/s1', { params: { reason: 'LATER', confirmedByCustomer: true } });
  });

  it('sends neither for a deletion the app makes by itself', async () => {
    await importApi.discardSession('s1');
    expect(del).toHaveBeenCalledWith('/import/sessions/s1', { params: {} });
  });
});
