import { readFileSync } from 'fs';
import path from 'path';
import type { ExpoConfig } from 'expo/config';
import { APP_LINK_EXACT_PATHS, APP_LINK_HOSTS, APP_LINK_PATH_PREFIXES } from './appLinks';

// Three things have to agree for an emailed link to open the app instead of the browser, and each
// lives somewhere different: app.config.ts (what the binary claims), the association file the web
// app hosts (what the domain says), and the hosts/paths in appLinks.config.js. Nothing else
// notices when one drifts -- iOS/Android just quietly keep the link in the browser -- so this reads
// all of them and compares.

const WELL_KNOWN = path.join(__dirname, '../../../frontend/public/.well-known');

function loadConfig(variant: 'development' | 'production'): ExpoConfig {
  const previous = process.env.APP_VARIANT;
  process.env.APP_VARIANT = variant;
  try {
    let config: ExpoConfig | undefined;
    jest.isolateModules(() => {
      config = require('../../app.config').default as ExpoConfig;
    });
    return config as ExpoConfig;
  } finally {
    if (previous === undefined) delete process.env.APP_VARIANT;
    else process.env.APP_VARIANT = previous;
  }
}

const variants = [
  { name: 'production', host: APP_LINK_HOSTS.production },
  { name: 'development', host: APP_LINK_HOSTS.development },
] as const;

describe.each(variants)('app.config.ts, $name variant', ({ name, host }) => {
  const config = loadConfig(name);

  it('claims exactly its own web origin on iOS', () => {
    expect(config.ios?.associatedDomains).toEqual([`applinks:${host}`]);
  });

  it('claims exactly the shared path list on Android, verified, for the same origin', () => {
    const filters = config.android?.intentFilters ?? [];
    expect(filters).toHaveLength(1);
    const [filter] = filters;
    expect(filter.autoVerify).toBe(true);
    expect(filter.action).toBe('VIEW');
    expect(filter.category).toEqual(expect.arrayContaining(['BROWSABLE', 'DEFAULT']));
    expect(filter.data).toEqual([
      ...APP_LINK_EXACT_PATHS.map((p) => ({ scheme: 'https', host, path: p })),
      ...APP_LINK_PATH_PREFIXES.map((pathPrefix) => ({ scheme: 'https', host, pathPrefix })),
    ]);
  });
});

describe('apple-app-site-association', () => {
  const aasa = JSON.parse(readFileSync(path.join(WELL_KNOWN, 'apple-app-site-association'), 'utf8'));
  const detail = aasa.applinks.details[0];

  it('names the production and the development iOS app under one Apple Team ID', () => {
    const prod = loadConfig('production').ios?.bundleIdentifier;
    const dev = loadConfig('development').ios?.bundleIdentifier;
    expect(prod).toBeTruthy();
    expect(dev).toBeTruthy();
    expect(prod).not.toBe(dev);
    const teamIds = new Set((detail.appIDs as string[]).map((id) => id.split('.')[0]));
    expect(teamIds.size).toBe(1);
    expect([...teamIds][0]).toMatch(/^[A-Z0-9]{10}$/);
    expect(detail.appIDs.map((id: string) => id.slice(id.indexOf('.') + 1)).sort()).toEqual([prod, dev].sort());
  });

  it('lists every claimed path and its sub-paths, and nothing else', () => {
    const listed = (detail.components as { '/': string }[]).map((c) => c['/']).sort();
    const expected = [...APP_LINK_EXACT_PATHS, ...APP_LINK_PATH_PREFIXES.flatMap((p) => [p, `${p}/*`])].sort();
    expect(listed).toEqual(expected);
  });
});
