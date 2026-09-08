import { Platform } from 'react-native';
import Purchases, { type PurchasesPackage } from 'react-native-purchases';

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

/** Opens the OS's native purchase sheet for the given plan/cycle. Resolving does NOT mean the
 *  plan is active -- activation only ever comes from the backend's verified RevenueCat webhook
 *  (design spec §6.1 step 5), same rule as web's openRazorpayCheckout(). */
export async function purchasePlan(planCode: 'PLUS' | 'PREMIUM', billingCycle: 'MONTHLY' | 'YEARLY'): Promise<void> {
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

export async function restorePurchases(): Promise<void> {
  await Purchases.restorePurchases();
}
