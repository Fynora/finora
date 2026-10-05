// The wrapper must never throw: it runs on every cold start, including on iOS, on binaries built
// before the native module existed, and under the test runner.
describe('launch-system-bars', () => {
  afterEach(() => {
    jest.resetModules();
    jest.dontMock('expo');
  });

  function load(platform: 'android' | 'ios', native: unknown) {
    jest.resetModules();
    jest.doMock('expo', () => ({ requireOptionalNativeModule: jest.fn(() => native) }));
    const { Platform } = require('react-native');
    Platform.OS = platform;
    return require('./index') as typeof import('./index');
  }

  it('calls the native module on Android', () => {
    const native = { enterDarkField: jest.fn(() => Promise.resolve()), restore: jest.fn(() => Promise.resolve()) };
    const bars = load('android', native);

    bars.enterDarkField();
    bars.restoreSystemBars();

    expect(native.enterDarkField).toHaveBeenCalledTimes(1);
    expect(native.restore).toHaveBeenCalledTimes(1);
  });

  it('does nothing, without throwing, when the binary has no native module', () => {
    const bars = load('android', null);

    expect(() => {
      bars.enterDarkField();
      bars.restoreSystemBars();
    }).not.toThrow();
  });

  it('does not reach for the native module on iOS', () => {
    const native = { enterDarkField: jest.fn(() => Promise.resolve()), restore: jest.fn(() => Promise.resolve()) };
    const bars = load('ios', native);

    bars.enterDarkField();

    expect(native.enterDarkField).not.toHaveBeenCalled();
  });

  it('swallows a failed native call rather than leaving an unhandled rejection', async () => {
    const native = { enterDarkField: jest.fn(() => Promise.reject(new Error('no window'))), restore: jest.fn() };
    const bars = load('android', native);

    bars.enterDarkField();
    await Promise.resolve();

    expect(native.enterDarkField).toHaveBeenCalledTimes(1);
  });
});
