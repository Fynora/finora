import { readFileSync } from 'fs';
import { join } from 'path';

// Google Play refuses to send a release for review when the bundle declares READ_MEDIA_IMAGES or
// READ_MEDIA_VIDEO ("Use alternative system pickers for photos / videos", seen on versionCodes 33
// and 34). expo-screen-capture's own AndroidManifest.xml adds READ_MEDIA_IMAGES and
// READ_EXTERNAL_STORAGE to the merged manifest, so app.config.ts has to remove them. This reads the
// config as text, the way screenCapture.test.ts reads sources, instead of evaluating it.
const configSource = readFileSync(join(__dirname, '..', '..', 'app.config.ts'), 'utf8');

function arrayAfter(key: string): string {
  const match = new RegExp(`\\b${key}:\\s*\\[([^\\]]*)\\]`).exec(configSource);
  if (!match) throw new Error(`app.config.ts has no ${key} array`);
  return match[1];
}

describe('Android photo and video permissions', () => {
  const blocked = arrayAfter('blockedPermissions');
  const declared = arrayAfter('permissions');

  it.each([
    'android.permission.READ_MEDIA_IMAGES',
    'android.permission.READ_MEDIA_VIDEO',
    'android.permission.READ_EXTERNAL_STORAGE',
  ])('removes %s from the merged manifest', (permission) => {
    expect(blocked).toContain(`'${permission}'`);
  });

  it('does not declare any of them itself', () => {
    expect(declared).not.toMatch(/READ_MEDIA_|READ_EXTERNAL_STORAGE/);
  });
});
