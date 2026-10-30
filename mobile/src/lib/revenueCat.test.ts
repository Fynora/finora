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

// Shaped like Google Play's answer for Plus monthly with the Play Console offer "diwali2026": one
// single payment of ₹125 for a month, then the ₹249 base plan. Synthetic values.
function phase(amountRupees: number, iso8601: string, cycles: number | null) {
  return {
    billingPeriod: { iso8601, unit: 'MONTH', value: 1 },
    recurrenceMode: cycles == null ? 1 : 3,
    billingCycleCount: cycles,
    price: { formatted: `₹${amountRupees.toFixed(2)}`, amountMicros: Math.round(amountRupees * 1_000_000), currencyCode: 'INR' },
    offerPaymentMode: cycles == null ? null : 'SINGLE_PAYMENT',
  };
}

function diwaliOption(overrides: Record<string, unknown> = {}) {
  const intro = phase(125, 'P1M', 1);
  const full = phase(249, 'P1M', null);
  return {
    id: 'monthly:diwali2026', storeProductId: 'plus:monthly', productId: 'plus', tags: ['diwali2026'],
    isBasePlan: false, pricingPhases: [intro, full], introPhase: intro, fullPricePhase: full, freePhase: null,
    ...overrides,
  };
}

describe('playIntroOffer', () => {
  const originalOS = Platform.OS;

  afterEach(() => {
    Platform.OS = originalOS;
  });

  /** A fresh, configured module on the given platform whose offerings hold one Plus package. */
  async function configuredOn(os: 'android' | 'ios', defaultOption: unknown) {
    jest.resetModules();
    Platform.OS = os;
    const purchases = (require('react-native-purchases') as { default: jest.Mocked<typeof Purchases> }).default;
    purchases.getOfferings.mockReset().mockResolvedValue({
      current: { availablePackages: [{ identifier: 'plus_monthly', product: { identifier: 'plus:monthly', defaultOption } }] },
    } as any);
    const mod = await import('./revenueCat');
    mod.configureRevenueCat('11111111-1111-1111-1111-111111111111');
    return { mod, purchases };
  }

  it("reads Play's first-month price and the regular price that follows", async () => {
    const { mod } = await configuredOn('android', diwaliOption());

    await expect(mod.playIntroOffer('PLUS', 'MONTHLY')).resolves.toEqual({ offerPrice: '₹125', regularPrice: '₹249' });
  });

  it("keeps Play's own formatting for a price that is not whole rupees, rather than rounding it", async () => {
    const intro = phase(124.5, 'P1M', 1);
    const { mod } = await configuredOn('android', diwaliOption({ introPhase: intro, pricingPhases: [intro] }));

    await expect(mod.playIntroOffer('PLUS', 'MONTHLY')).resolves.toEqual({ offerPrice: '₹124.50', regularPrice: '₹249' });
  });

  it.each([
    ['the base plan (no offer this customer is eligible for)', { isBasePlan: true, introPhase: null }],
    ['a free trial', { freePhase: phase(0, 'P7D', 1) }],
    ['a discount over several months', { introPhase: phase(125, 'P1M', 3) }],
    ['a first period longer than a month', { introPhase: phase(125, 'P3M', 1) }],
    ['an "offer" that costs no less than the regular price', { introPhase: phase(249, 'P1M', 1) }],
  ])('reports nothing for %s', async (_what, overrides) => {
    const { mod } = await configuredOn('android', diwaliOption(overrides));

    await expect(mod.playIntroOffer('PLUS', 'MONTHLY')).resolves.toBeNull();
  });

  it('reports nothing on iOS, without asking the store', async () => {
    const { mod, purchases } = await configuredOn('ios', diwaliOption());

    await expect(mod.playIntroOffer('PLUS', 'MONTHLY')).resolves.toBeNull();
    expect(purchases.getOfferings).not.toHaveBeenCalled();
  });

  it('reports nothing, rather than throwing, when the store cannot be reached', async () => {
    const { mod, purchases } = await configuredOn('android', diwaliOption());
    purchases.getOfferings.mockRejectedValue(new Error('network down'));

    await expect(mod.playIntroOffer('PLUS', 'MONTHLY')).resolves.toBeNull();
  });

  it('reports nothing before RevenueCat is configured', async () => {
    jest.resetModules();
    Platform.OS = 'android';
    const { playIntroOffer: fresh } = await import('./revenueCat');

    await expect(fresh('PLUS', 'MONTHLY')).resolves.toBeNull();
  });
});
