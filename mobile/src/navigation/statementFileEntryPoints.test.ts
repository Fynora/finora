import { existsSync, readFileSync } from 'fs';
import path from 'path';
import type { ExpoConfig } from 'expo/config';

// Android has two ways to hand a statement file to Fynora: "Share" (ACTION_SEND) and "Open with"
// (ACTION_VIEW on a content:// URI). Gmail's attachment viewer and the Files app use "Open with",
// so both must be declared, for the same file types, and expo-share-intent must be patched to read
// the second one (out of the box it rejects ACTION_VIEW for files).

const MOBILE = path.join(__dirname, '../..');
const STATEMENT_TYPES = ['application/pdf', 'text/csv', 'text/comma-separated-values'];

function loadConfig(): ExpoConfig {
  let config: ExpoConfig | undefined;
  jest.isolateModules(() => {
    config = require('../../app.config').default as ExpoConfig;
  });
  return config as ExpoConfig;
}

const asArray = <T,>(v: T | T[] | undefined): T[] => (v === undefined ? [] : Array.isArray(v) ? v : [v]);

describe('statement file entry points on Android', () => {
  const config = loadConfig();

  it('declares "Open with" for statement files on content:// URIs only, not reachable from a web page', () => {
    const fileFilters = (config.android?.intentFilters ?? []).filter((f) =>
      asArray(f.data).some((d) => d?.scheme === 'content'),
    );
    expect(fileFilters).toHaveLength(1);
    const [filter] = fileFilters;
    expect(filter.action).toBe('VIEW');
    expect(asArray(filter.category)).toEqual(['DEFAULT']);
    expect(filter.autoVerify).toBeFalsy();
    expect(asArray(filter.data)).toEqual(STATEMENT_TYPES.map((mimeType) => ({ scheme: 'content', mimeType })));
  });

  it('declares "Share" for the same file types', () => {
    const entry = (config.plugins ?? []).find((p) => Array.isArray(p) && p[0] === 'expo-share-intent');
    expect(entry).toBeDefined();
    const options = (entry as [string, { androidIntentFilters?: string[] }])[1];
    expect(options.androidIntentFilters).toEqual(STATEMENT_TYPES);
  });

  it('carries the expo-share-intent patch for the installed version, which reads "Open with"', () => {
    const { version } = JSON.parse(
      readFileSync(path.join(MOBILE, 'node_modules/expo-share-intent/package.json'), 'utf8'),
    ) as { version: string };
    const patch = path.join(MOBILE, `patches/expo-share-intent+${version}.patch`);
    expect(existsSync(patch)).toBe(true);
    expect(readFileSync(patch, 'utf8')).toContain('intent.action == Intent.ACTION_VIEW');
  });
});
