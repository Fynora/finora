import { describe, it, expect, vi, beforeEach } from 'vitest';
import { importApi } from './endpoints';
import { api } from './client';

vi.mock('./client', () => ({
  api: { get: vi.fn().mockResolvedValue({ data: [] }) },
  rawApi: { get: vi.fn() },
}));

// eslint-disable-next-line @typescript-eslint/unbound-method
const get = vi.mocked(api.get);

/**
 * "Continue a previous import" lists statements covering more than one account only when the app
 * asks: the server keeps them from apps that cannot reopen them. This page can, so it has to ask.
 */
describe('importApi.listSessions', () => {
  beforeEach(() => get.mockClear().mockResolvedValue({ data: [] } as never));

  it('asks for statements covering more than one account', async () => {
    await importApi.listSessions();

    expect(get).toHaveBeenCalledWith('/import/sessions', { params: { includeMultiAccount: true } });
  });
});
