import { readdirSync, readFileSync, statSync } from 'fs';
import { join, relative } from 'path';

/** Each of these native touch points has one door, so the haptics switch and the haptic that goes
 *  with it cannot be bypassed by a new screen. */
const SRC = join(__dirname, '..');
const files = (d: string): string[] => readdirSync(d).flatMap((f) => {
  const p = join(d, f);
  return statSync(p).isDirectory() ? files(p) : /\.(ts|tsx)$/.test(f) && !/\.test\.tsx?$/.test(f) && !p.includes('/test/') ? [p] : [];
});
const importers = (re: RegExp) => files(SRC).filter((p) => re.test(readFileSync(p, 'utf8'))).map((p) => relative(SRC, p)).sort();

it('only lib/haptics.ts imports expo-haptics', () => {
  expect(importers(/from 'expo-haptics'/)).toEqual(['lib/haptics.ts']);
});

it('only AppSwitch imports Switch from react-native', () => {
  expect(importers(/import \{[^}]*\bSwitch\b[^}]*\} from 'react-native'/)).toEqual(['components/AppSwitch.tsx']);
});
