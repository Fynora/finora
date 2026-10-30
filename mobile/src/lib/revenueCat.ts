import { Platform } from 'react-native';
import Purchases, { type Price, type PurchasesPackage } from 'react-native-purchases';
import { fmtCurrency } from './format';

// Apple and Google issue separate "Platform Store API Keys" once real App Store Connect / Google
// Play Console apps are linked in the RevenueCat dashboard -- there is no single key that works
// for both, unlike this file's own earlier single-key design assumed. RevenueCat's own quickstart
// "Configure" snippet confirms the same split (iosApiKey / androidApiKey, branched on
// Platform.OS). A shared value across both env vars is fine during Test Store development, where
// RevenueCat issues one test_-prefixed key for both -- see their own docs: never ship that key to
// a real App Store/Play Store build.
const REVENUECAT_IOS_API_KEY = process.env.EXPO_PUBLIC_REVENUECAT_IOS_API_KEY;
const REVENUECAT_ANDROID_API_KEY = process.env.EXPO_PUBLIC_REVENUECAT_ANDROID_API_KEY;

// RevenueCat's own docs (identifying-customers.md): "You should configure the SDK only once in
// your code." AuthContext calls configureRevenueCat() from two convergence points (a cold-start
// restore of an already-signed-in session, and every fresh login/register/reactivate/Google/
// Apple) precisely because either one might be the first authenticated moment in this process --
// this guard is what makes calling it from both safe. Module-level, same pattern already
// established by GoogleSignInButton.tsx's own ensureConfigured().
let configured = false;

/** Subscription billing V4 (design spec §2/§6.1). appUserID is ALWAYS the real, authenticated
 *  Fynora user id -- never RevenueCat's own anonymous $RCAnonymousID. Called once at sign-in,
 *  mirroring how the backend's Razorpay integration embeds the raw user id (notes.fynoraUserId)
 *  rather than a separate mapping id. */
export function configureRevenueCat(fynoraUserId: string): void {
  if (configured) return;
  if (Platform.OS !== 'ios' && Platform.OS !== 'android') {
    throw new Error(`RevenueCat is not supported on platform "${Platform.OS}".`);
  }
  const apiKey = Platform.OS === 'ios' ? REVENUECAT_IOS_API_KEY : REVENUECAT_ANDROID_API_KEY;
  if (!apiKey) {
    throw new Error(`EXPO_PUBLIC_REVENUECAT_${Platform.OS.toUpperCase()}_API_KEY is not set.`);
  }
  Purchases.configure({ apiKey, appUserID: fynoraUserId });
  configured = true;
}

function packageIdentifierFor(planCode: string, billingCycle: string): string {
  return `${planCode.toLowerCase()}_${billingCycle.toLowerCase()}`;
}

// AuthContext catches configureRevenueCat()'s throw (missing key / unsupported platform) so a
// user can still reach the rest of the app -- but that leaves `configured` permanently false for
// the process. Without this guard, purchasePlan()/restorePurchases() would hit the native SDK
// directly and surface ITS OWN error (e.g. "Purchases has not been configured"), not this app's.
function assertConfigured(): void {
  if (!configured) {
    throw new Error('RevenueCat is not configured. Call configureRevenueCat() first.');
  }
}

/** Opens the OS's native purchase sheet for the given plan/cycle. Resolving does NOT mean the
 *  plan is active -- activation only ever comes from the backend's verified RevenueCat webhook
 *  (design spec §6.1 step 5), same rule as web's openRazorpayCheckout(). */
export async function purchasePlan(planCode: 'PLUS' | 'PREMIUM', billingCycle: 'MONTHLY' | 'YEARLY'): Promise<void> {
  assertConfigured();
  const offerings = await Purchases.getOfferings();
  const target = packageIdentifierFor(planCode, billingCycle);
  const pkg = offerings.current?.availablePackages.find(
    (p: PurchasesPackage) => p.identifier === target || p.product.identifier === target
  );
  if (!pkg) {
    throw new Error(`No RevenueCat offering package found for ${target}.`);
  }
  await Purchases.purchasePackage(pkg);
}

/** A first-period price Google Play will charge this customer for a plan, from a Play Console offer
 *  (e.g. Plus monthly's "diwali2026": ₹125 for the first month, then ₹249). */
export interface PlayIntroOffer {
  /** What the first month costs, e.g. "₹125". */
  offerPrice: string;
  /** What every month after it costs, e.g. "₹249". */
  regularPrice: string;
}

/** Whole-rupee INR amounts read like the rest of the app ("₹125"); anything else keeps Play's own
 *  formatting, so a price is never rounded into something Play does not charge. */
function playPrice(price: Price): string {
  return price.currencyCode === 'INR' && price.amountMicros % 1_000_000 === 0
    ? fmtCurrency(price.amountMicros / 1_000_000)
    : price.formatted;
}

/**
 * The introductory price Google Play would charge for this plan right now, or null.
 *
 * Reads the option purchasePlan() buys. RevenueCat's docs (subscription offers): passing a Package
 * to a purchase "automatically applies an available free trial or introductory offer", choosing
 * the longest free trial, else the cheapest introductory period the customer is eligible for, else
 * the base plan -- and `defaultOption` is that same choice. Play only lists a "new customer
 * acquisition" offer to a customer it finds eligible, so a null here also covers "has had Plus
 * before".
 *
 * Only a paid, one-month first period is reported -- the one shape this app can describe in words
 * ("₹125 for your first month"). A free trial, several discounted months, or anything else returns
 * null, and the screen keeps the regular price; Play's own purchase sheet still states the exact
 * terms before anyone pays.
 *
 * Never throws: an unconfigured SDK, iOS (no Play options), a network failure or a missing package
 * all mean "no offer to show".
 */
export async function playIntroOffer(
  planCode: 'PLUS' | 'PREMIUM', billingCycle: 'MONTHLY' | 'YEARLY',
): Promise<PlayIntroOffer | null> {
  if (!configured || Platform.OS !== 'android') return null;
  try {
    const offerings = await Purchases.getOfferings();
    const target = packageIdentifierFor(planCode, billingCycle);
    const pkg = offerings.current?.availablePackages.find(
      (p: PurchasesPackage) => p.identifier === target || p.product.identifier === target
    );
    const option = pkg?.product.defaultOption;
    if (!option || option.isBasePlan || option.freePhase) return null;
    const intro = option.introPhase;
    const regular = option.fullPricePhase;
    if (!intro || !regular) return null;
    const oneMonth = intro.billingPeriod.iso8601 === 'P1M' && (intro.billingCycleCount ?? 1) === 1;
    if (!oneMonth || intro.price.amountMicros >= regular.price.amountMicros) return null;
    return { offerPrice: playPrice(intro.price), regularPrice: playPrice(regular.price) };
  } catch {
    return null;
  }
}

export async function restorePurchases(): Promise<void> {
  assertConfigured();
  await Purchases.restorePurchases();
}
