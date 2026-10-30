import { execFileSync } from 'child_process';
import { readFileSync } from 'fs';
import { join } from 'path';

/**
 * runtimeVersion.policy is 'appVersion' (app.config.ts): an OTA update reaches every installed
 * binary with the same `version`. A new Expo SDK changes the native side of nearly every module
 * and moves React Native itself, so JS built against it would crash a binary built on the old SDK.
 * The commit that moves the Expo SDK major must therefore also move `version`.
 *
 * Same shape as the glass-module guard in theme/glassMigration.test.ts, keyed on the SDK major
 * instead of one package. Vacuous once main carries the same SDK.
 */
const MOBILE = join(__dirname, '..');

const onMain = (file: string) => {
  try {
    return execFileSync('git', ['show', `origin/main:mobile/${file}`], { encoding: 'utf8', stdio: ['ignore', 'pipe', 'ignore'] });
  } catch {
    throw new Error(`origin/main is not available in this checkout, so ${file} cannot be compared with main's. Run: git fetch --no-tags --depth=1 origin +refs/heads/main:refs/remotes/origin/main`);
  }
};

/** The SDK major from an `expo` range such as `~58.0.21`. */
const sdkMajor = (range: string) => /(\d+)\./.exec(range)?.[1];
const appVersion = (config: string) => /\bversion:\s*'([^']+)'/.exec(config)?.[1];

it('app version differs from origin/main when the Expo SDK major changes (OTA must not reach old binaries)', () => {
  const ours = JSON.parse(readFileSync(join(MOBILE, 'package.json'), 'utf8'));
  const main = JSON.parse(onMain('package.json'));
  const ourSdk = sdkMajor(ours.dependencies.expo);
  expect(ourSdk).toBeDefined();
  if (ourSdk === sdkMajor(main.dependencies.expo)) return;

  const ourVersion = appVersion(readFileSync(join(MOBILE, 'app.config.ts'), 'utf8'));
  expect(ourVersion).toBeDefined();
  expect(ourVersion).not.toBe(appVersion(onMain('app.config.ts')));
});
