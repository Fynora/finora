// EXPO_PUBLIC_REVENUECAT_IOS_API_KEY / _ANDROID_API_KEY are set globally in src/test/setup.ts --
// configureRevenueCat() throws when the key for the current Platform.OS is missing.
import { Platform } from 'react-native';
import Purchases from 'react-native-purchases';
import { configureRevenueCat, purchasePlan } from './revenueCat';

// __esModule: true is required here -- without it, TS's default-import interop for `import
// Purchases from 'react-native-purchases'` resolves to the whole mock object (with `default`
// nested inside it) rather than unwrapping to the object below, and every mocked method call
// throws "Cannot read properties of undefined".
jest.mock('react-native-purchases', () => ({
  __esModule: true,
  default: {
    configure: jest.fn(),
    getOfferings: jest.fn(),
    purchasePackage: jest.fn(),
    restorePurchases: jest.fn(),
  },
}));

const mockedPurchases = Purchases as jest.Mocked<typeof Purchases>;

describe('configureRevenueCat', () => {
  it('configures with the real Fynora user id as appUserID, never anonymous', () => {
    configureRevenueCat('11111111-1111-1111-1111-111111111111');

    expect(mockedPurchases.configure).toHaveBeenCalledWith(
      expect.objectContaining({ appUserID: '11111111-1111-1111-1111-111111111111' })
    );
  });

  // RevenueCat's own docs (identifying-customers.md, pulled via context7): "You should configure
  // the SDK only once in your code." AuthContext calls configureRevenueCat() from two different
  // convergence points (cold-start restore AND every fresh login/register/etc.) precisely because
  // either one might be the first time a session sees an authenticated user -- without this guard,
  // a user who was already signed in at cold start and then, say, completes phone verification
  // would have Purchases.configure() invoked a second time in the same process.
  it('configures only once per process even if called again for the same session', () => {
    configureRevenueCat('11111111-1111-1111-1111-111111111111');
    mockedPurchases.configure.mockClear();

    configureRevenueCat('11111111-1111-1111-1111-111111111111');

    expect(mockedPurchases.configure).not.toHaveBeenCalled();
  });
});

// `configured` in revenueCat.ts is a module-level, one-time latch -- once ANY test successfully
// calls configureRevenueCat(), every later call on that same module instance is a no-op. The
// `describe('configureRevenueCat', ...)` block above relies on that latch being shared across its
// own two tests (that's the whole point of its second test). Testing two DIFFERENT platform
// branches needs the opposite: each of the tests below needs its own fresh module instance with
// `configured` back at its initial `false`, so a call actually reaches Purchases.configure().
// jest.resetModules() + a dynamic re-import of both './revenueCat' and 'react-native-purchases'
// itself gets that -- a static top-level import (what the block above uses) would keep resolving
// to the module instance captured at file load, before any of these tests ever run. See
// queryCacheCipher.test.ts for the same pattern, in more detail, on the same class of problem.
describe('configureRevenueCat platform-specific keys', () => {
  const originalOS = Platform.OS;
  const originalIosKey = process.env.EXPO_PUBLIC_REVENUECAT_IOS_API_KEY;
  const originalAndroidKey = process.env.EXPO_PUBLIC_REVENUECAT_ANDROID_API_KEY;

  afterEach(() => {
    Platform.OS = originalOS;
    process.env.EXPO_PUBLIC_REVENUECAT_IOS_API_KEY = originalIosKey;
    process.env.EXPO_PUBLIC_REVENUECAT_ANDROID_API_KEY = originalAndroidKey;
  });

  it('configures with the iOS key on iOS', async () => {
    jest.resetModules();
    Platform.OS = 'ios';
    const freshPurchases = (require('react-native-purchases') as { default: typeof Purchases }).default;
    const { configureRevenueCat: freshConfigure } = await import('./revenueCat');

    freshConfigure('user-ios');

    expect(freshPurchases.configure).toHaveBeenCalledWith({
      apiKey: 'test-revenuecat-ios-api-key',
      appUserID: 'user-ios',
    });
  });

  it('configures with the Android key on Android', async () => {
    jest.resetModules();
    Platform.OS = 'android';
    const freshPurchases = (require('react-native-purchases') as { default: typeof Purchases }).default;
    const { configureRevenueCat: freshConfigure } = await import('./revenueCat');

    freshConfigure('user-android');

    expect(freshPurchases.configure).toHaveBeenCalledWith({
      apiKey: 'test-revenuecat-android-api-key',
      appUserID: 'user-android',
    });
  });

  it('names the actual missing env var for the current platform, not the old shared one', async () => {
    jest.resetModules();
    Platform.OS = 'ios';
    delete process.env.EXPO_PUBLIC_REVENUECAT_IOS_API_KEY;
    const { configureRevenueCat: freshConfigure } = await import('./revenueCat');

    expect(() => freshConfigure('user-x')).toThrow('EXPO_PUBLIC_REVENUECAT_IOS_API_KEY is not set.');
  });

  it('throws a clear error on an unsupported platform rather than silently misconfiguring', async () => {
    jest.resetModules();
    Platform.OS = 'web';
    const freshPurchases = (require('react-native-purchases') as { default: typeof Purchases }).default;
    const { configureRevenueCat: freshConfigure } = await import('./revenueCat');

    expect(() => freshConfigure('user-x')).toThrow(/not supported on platform "web"/);
    expect(freshPurchases.configure).not.toHaveBeenCalled();
  });
});

// Same fresh-module need as the platform-specific-keys block above: `configured` must start at
// its initial `false` to prove purchasePlan()/restorePurchases() guard against being called
// before configureRevenueCat() ever succeeded -- e.g. AuthContext caught its throw (missing key,
// unsupported platform) and the app carried on without billing configured for this process.
describe('purchasePlan / restorePurchases before configureRevenueCat', () => {
  const originalOS = Platform.OS;

  afterEach(() => {
    Platform.OS = originalOS;
  });

  it('purchasePlan throws a clear error instead of hitting the native SDK unconfigured', async () => {
    jest.resetModules();
    Platform.OS = 'ios';
    const { purchasePlan: freshPurchasePlan } = await import('./revenueCat');

    await expect(freshPurchasePlan('PLUS', 'MONTHLY')).rejects.toThrow(
      'RevenueCat is not configured. Call configureRevenueCat() first.'
    );
  });

  it('restorePurchases throws a clear error instead of hitting the native SDK unconfigured', async () => {
    jest.resetModules();
    Platform.OS = 'ios';
    const { restorePurchases: freshRestorePurchases } = await import('./revenueCat');

    await expect(freshRestorePurchases()).rejects.toThrow(
      'RevenueCat is not configured. Call configureRevenueCat() first.'
    );
  });
});

describe('purchasePlan', () => {
  beforeEach(() => mockedPurchases.getOfferings.mockReset());

  it('purchases the package matching the requested plan and cycle', async () => {
    const targetPackage = { identifier: 'plus_monthly', product: { identifier: 'plus_monthly' } };
    mockedPurchases.getOfferings.mockResolvedValue({
      current: { availablePackages: [targetPackage] },
    } as any);
    mockedPurchases.purchasePackage.mockResolvedValue({} as any);

    await purchasePlan('PLUS', 'MONTHLY');

    expect(mockedPurchases.purchasePackage).toHaveBeenCalledWith(targetPackage);
  });

  it('throws a clear error when no offering package matches the plan/cycle', async () => {
    mockedPurchases.getOfferings.mockResolvedValue({ current: { availablePackages: [] } } as any);

    await expect(purchasePlan('PREMIUM', 'YEARLY')).rejects.toThrow(/no.*offering/i);
  });
});
