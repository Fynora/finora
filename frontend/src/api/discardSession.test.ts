import { describe, it, expect, vi, beforeEach } from 'vitest';
import { importApi } from './endpoints';
import { api } from './client';

vi.mock('./client', () => ({
  api: { delete: vi.fn().mockResolvedValue({ data: null }) },
  rawApi: { delete: vi.fn() },
}));

// eslint-disable-next-line @typescript-eslint/unbound-method
const del = vi.mocked(api.delete);

/** What a discard tells the server: the optional reason, and whether the customer answered a dialog. */
describe('importApi.discardSession', () => {
  beforeEach(() => del.mockClear().mockResolvedValue({ data: null } as never));

  it('sends the reason and that the customer confirmed it, from a dialog', async () => {
    await importApi.discardSession('s1', 'WRONG_FILE', true);
    expect(del).toHaveBeenCalledWith('/import/sessions/s1', { params: { reason: 'WRONG_FILE', confirmedByCustomer: true } });
  });

  it('sends neither when there is no reason and no dialog', async () => {
    await importApi.discardSession('s1');
    expect(del).toHaveBeenCalledWith('/import/sessions/s1', { params: {} });
  });
});
