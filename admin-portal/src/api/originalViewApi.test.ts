import { beforeEach, describe, expect, it, vi } from 'vitest';
import { api } from './client';
import { adminOriginalViewApi } from './endpoints';

vi.mock('./client', () => ({
  api: { get: vi.fn(), post: vi.fn() },
  rawApi: { post: vi.fn() },
}));

const get = vi.mocked(api.get);
const post = vi.mocked(api.post);

function blobError(status: number, body: string) {
  return { response: { status, data: new Blob([body], { type: 'application/json' }) } };
}

beforeEach(() => {
  get.mockReset();
  post.mockReset();
});

describe('adminOriginalViewApi', () => {
  it('sends the reason when opening, on the queue the item belongs to', async () => {
    post.mockResolvedValue({ data: { viewId: 'v1' } });

    await adminOriginalViewApi.openImport('job-1', 'Rows on page 2 are missing');
    await adminOriginalViewApi.openTrust('HLD-1', 'Rows on page 2 are missing');

    expect(post).toHaveBeenNthCalledWith(1, '/admin/held-imports/job-1/original-view', { reason: 'Rows on page 2 are missing' });
    expect(post).toHaveBeenNthCalledWith(2, '/admin/held-statements/HLD-1/original-view', { reason: 'Rows on page 2 are missing' });
  });

  it("reads the server's message out of a page's blob error", async () => {
    get.mockRejectedValue(blobError(409, JSON.stringify({ message: 'Only a held import can be opened.' })));

    const err = await adminOriginalViewApi.pageBlob('v1', 1).catch((e: unknown) => e);

    expect((err as { response: { status: number; data: { message: string } } }).response.status).toBe(409);
    expect((err as { response: { data: { message: string } } }).response.data.message)
      .toBe('Only a held import can be opened.');
  });

  it('says a page could not be shown, not that a download failed, when the server gives no message', async () => {
    get.mockRejectedValue(blobError(500, 'not json'));

    const err = await adminOriginalViewApi.pageBlob('v1', 1).catch((e: unknown) => e);

    expect((err as { response: { data: { message: string } } }).response.data.message)
      .toBe('This page could not be shown, and the server did not explain why.');
  });
});
