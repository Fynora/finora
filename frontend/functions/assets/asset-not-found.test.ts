import { describe, it, expect, vi } from 'vitest';

import { isDocumentForMissingAsset, onRequest } from './[[path]]';

/**
 * This file is deployed straight to Cloudflare's edge and is not part of the app's Vite build, so
 * nothing else exercises it. A mistake here does not fail a build or a type check -- it changes the
 * response to every asset request in production. These tests are the only gate.
 *
 * Named `asset-not-found.test.ts` rather than matching its subject's `[[path]].ts`, because the
 * brackets are Pages' catch-all route syntax and a test file called `[[path]].test.ts` would itself
 * be picked up as a route by the Functions build.
 */

function assetResponse(contentType: string, status = 200): Response {
  return new Response('console.log(1)', { status, headers: { 'content-type': contentType } });
}

describe('isDocumentForMissingAsset', () => {
  /** The original bug: Pages answered a deleted chunk with index.html and a success status. */
  it('recognises the SPA fallback by its content type, not its status', () => {
    expect(isDocumentForMissingAsset(assetResponse('text/html; charset=utf-8'))).toBe(true);
  });

  /**
   * What Pages answers with since the build has a top-level 404.html: the not-found page, with a
   * 404. Measured on a Pages preview. The status is already right; the body is still a document.
   */
  it('recognises the not-found page Pages serves for a missing file', () => {
    expect(isDocumentForMissingAsset(assetResponse('text/html; charset=utf-8', 404))).toBe(true);
  });

  it('leaves a 404 that is not a document alone', () => {
    expect(isDocumentForMissingAsset(assetResponse('text/plain; charset=utf-8', 404))).toBe(false);
  });

  it.each([
    ['a script', 'application/javascript'],
    ['a module script', 'text/javascript; charset=utf-8'],
    ['a stylesheet', 'text/css; charset=utf-8'],
    ['a font', 'font/woff2'],
    ['an image', 'image/svg+xml'],
  ])('leaves %s alone', (_kind, contentType) => {
    expect(isDocumentForMissingAsset(assetResponse(contentType))).toBe(false);
  });

  /**
   * A 304 means the client already holds the file -- the opposite of it being missing -- and
   * carries no content type. Treating an absent header as "not HTML" happens to give the right
   * answer, but only by accident, so the status check comes first and this pins it.
   */
  it('passes a 304 through rather than reading its missing content type', () => {
    const notModified = new Response(null, { status: 304 });

    expect(isDocumentForMissingAsset(notModified)).toBe(false);
  });

  it('passes a genuine upstream error through untouched', () => {
    expect(isDocumentForMissingAsset(assetResponse('text/html', 500))).toBe(false);
  });

  it('does not assume a content-type header is present', () => {
    expect(isDocumentForMissingAsset(new Response('body', { status: 200 }))).toBe(false);
  });
});

describe('onRequest', () => {
  it('converts the SPA fallback into a real 404', async () => {
    const next = vi.fn().mockResolvedValue(assetResponse('text/html; charset=utf-8'));

    const response = await onRequest({ next });

    expect(response.status).toBe(404);
    expect(response.headers.get('content-type')).toContain('text/plain');
  });

  it('replaces the not-found page with the same plain 404, so a script request never gets a document', async () => {
    const page = new Response('<!doctype html><h1>Page not found</h1>', {
      status: 404,
      headers: { 'content-type': 'text/html; charset=utf-8' },
    });
    const next = vi.fn().mockResolvedValue(page);

    const response = await onRequest({ next });

    expect(response.status).toBe(404);
    expect(response.headers.get('content-type')).toContain('text/plain');
    expect(response.headers.get('cache-control')).toBe('no-store');
    expect(await response.text()).toBe('Not found\n');
  });

  it('passes an upstream error document through, status and body', async () => {
    const failure = new Response('<h1>Bad gateway</h1>', { status: 502, headers: { 'content-type': 'text/html' } });
    const next = vi.fn().mockResolvedValue(failure);

    const response = await onRequest({ next });

    expect(response).toBe(failure);
  });

  /** The whole point of the change: a browser asking for a module must get a 404 it understands,
   *  not HTML it will reject for the wrong reason. */
  it('does not return html for a missing asset', async () => {
    const next = vi.fn().mockResolvedValue(assetResponse('text/html; charset=utf-8'));

    const response = await onRequest({ next });

    expect(response.headers.get('content-type')).not.toContain('text/html');
  });

  /**
   * The 404 must not be cached. A hashed name is never reused, so a miss looks permanent -- but
   * only if the deploy that should have published the file has finished. A miss recorded
   * mid-propagation would otherwise outlive the condition that produced it.
   */
  it('refuses to let the 404 be cached', async () => {
    const next = vi.fn().mockResolvedValue(assetResponse('text/html'));

    const response = await onRequest({ next });

    expect(response.headers.get('cache-control')).toBe('no-store');
  });

  /**
   * The critical negative. This handler sits in front of every asset request, so passing real
   * files through byte-for-byte -- including the immutable cache headers `_headers` sets -- matters
   * more than the 404 does.
   */
  it('returns a real asset completely untouched', async () => {
    const asset = new Response('export const a = 1', {
      status: 200,
      headers: {
        'content-type': 'application/javascript',
        'cache-control': 'public, max-age=31536000, immutable',
        etag: '"abc123"',
      },
    });
    const next = vi.fn().mockResolvedValue(asset);

    const response = await onRequest({ next });

    expect(response).toBe(asset);
    expect(response.status).toBe(200);
    expect(response.headers.get('cache-control')).toBe('public, max-age=31536000, immutable');
    expect(response.headers.get('etag')).toBe('"abc123"');
    expect(await response.text()).toBe('export const a = 1');
  });

  it('calls through exactly once', async () => {
    const next = vi.fn().mockResolvedValue(assetResponse('application/javascript'));

    await onRequest({ next });

    expect(next).toHaveBeenCalledTimes(1);
  });
});
