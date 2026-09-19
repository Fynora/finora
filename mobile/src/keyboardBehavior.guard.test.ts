import { readdirSync, readFileSync } from 'fs';
import { join, relative } from 'path';

// This mistake has now cost three separate fixes (#1641 Ask Fyn, #1642 auth screens, and the account
// and form sheets): `behavior={Platform.OS === 'ios' ? 'padding' : undefined}` turns
// KeyboardAvoidingView into a no-op on Android and relies on the OS resizing the window. Under this
// app's forced edge-to-edge display that resize does not happen -- including for Modal windows,
// measured on a real Galaxy A50 (Android 11): the Modal reported adjust=resize yet kept its full-height
// frame, and the whole Change Password sheet sat below the keyboard. jest-expo runs as iOS by default,
// so a component test only catches this if it forces Platform.OS, which is easy to forget for the next
// sheet. This scans the source instead.
const SRC = join(__dirname);

// Files that may keep the iOS-only expression, each with the reason. Add to this only for a
// component that cannot show a keyboard at all.
const ALLOWED: Record<string, string> = {
  'components/CategoryDeleteSheet.tsx': 'confirmation sheet with no text input',
};

const IOS_ONLY_BEHAVIOR = /behavior=\{\s*Platform\.OS\s*===\s*['"]ios['"]\s*\?[^}]*:\s*undefined\s*\}/;

// Comments explaining the old pattern (FynScreen's does) must not count as using it.
function withoutComments(code: string): string {
  return code.replace(/\/\*[\s\S]*?\*\//g, '').replace(/\/\/.*$/gm, '');
}

function usesIosOnlyBehavior(file: string): boolean {
  return IOS_ONLY_BEHAVIOR.test(withoutComments(readFileSync(join(SRC, file), 'utf8')));
}

function sourceFiles(dir: string): string[] {
  return readdirSync(dir, { withFileTypes: true }).flatMap((entry) => {
    const path = join(dir, entry.name);
    if (entry.isDirectory()) return sourceFiles(path);
    return /\.tsx$/.test(entry.name) && !/\.test\.tsx$/.test(entry.name) ? [path] : [];
  });
}

describe('KeyboardAvoidingView behavior', () => {
  it('is never iOS-only outside the documented exceptions', () => {
    const offenders = sourceFiles(SRC)
      .map((file) => relative(SRC, file))
      .filter((file) => !(file in ALLOWED))
      .filter(usesIosOnlyBehavior);

    expect(offenders).toEqual([]);
  });

  it('keeps every allowed exception real: it still exists and still uses the expression', () => {
    for (const file of Object.keys(ALLOWED)) {
      expect(usesIosOnlyBehavior(file)).toBe(true);
    }
  });
});
