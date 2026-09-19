import { readdirSync, readFileSync } from 'fs';
import { join, relative } from 'path';
import { renderHook } from '@testing-library/react-native';
import { usePreventScreenCapture as expoUsePreventScreenCapture } from 'expo-screen-capture';

// setup.ts replaces this module with a jest.fn() for the screen tests; these tests need the real one.
const real = jest.requireActual<typeof import('./screenCapture')>('./screenCapture');
const expoHook = expoUsePreventScreenCapture as jest.MockedFunction<typeof expoUsePreventScreenCapture>;

describe('chooseScreenCaptureGuard', () => {
  it('runs the real protection when capture is not allowed', () => {
    const protect = jest.fn();
    real.chooseScreenCaptureGuard(false, protect)();
    expect(protect).toHaveBeenCalledTimes(1);
  });

  it('does nothing when capture is allowed', () => {
    const protect = jest.fn();
    real.chooseScreenCaptureGuard(true, protect)();
    expect(protect).not.toHaveBeenCalled();
  });
});

describe('usePreventScreenCapture', () => {
  beforeEach(() => expoHook.mockClear());

  it('follows ALLOW_SCREEN_CAPTURE: the expo protection runs exactly when capture is not allowed', () => {
    renderHook(() => real.usePreventScreenCapture());
    expect(expoHook).toHaveBeenCalledTimes(real.ALLOW_SCREEN_CAPTURE ? 0 : 1);
  });
});

// One switch only works if nothing bypasses it. A screen that imported expo-screen-capture directly
// would keep blocking capture with the switch on, and the recording the switch exists for would
// silently fail on that screen.
describe('screen capture switch coverage', () => {
  const SRC = join(__dirname, '..');
  const OWNER = 'lib/screenCapture.ts';

  function sourceFiles(dir: string): string[] {
    return readdirSync(dir, { withFileTypes: true }).flatMap((entry) => {
      const path = join(dir, entry.name);
      if (entry.isDirectory()) return sourceFiles(path);
      return /\.tsx?$/.test(entry.name) && !/\.test\.tsx?$/.test(entry.name) ? [path] : [];
    });
  }

  it('is the only source file that imports expo-screen-capture', () => {
    const importers = sourceFiles(SRC)
      .map((file) => relative(SRC, file))
      .filter((file) => /from ['"]expo-screen-capture['"]/.test(readFileSync(join(SRC, file), 'utf8')));

    expect(importers).toEqual([OWNER]);
  });
});
