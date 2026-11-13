/**
 * Whether the iPhone app may offer Subscribe (an App Store purchase of Plus).
 *
 * Off while the App Store side is not ready: the Apple Developer account is still Individual (the
 * switch to Fynora Technovation LLP is pending), the Paid Apps Agreement is unsigned, and the Plus
 * subscriptions are not yet attached to a released app version. Until all of that is done, a tap on
 * Subscribe can only end in an error, so the Paywall shows a plain notice instead of the buttons.
 *
 * The notice carries no link to buy elsewhere on purpose: outside the United States storefront,
 * App Review does not allow a call to action that points to a purchase method other than in-app
 * purchase (guideline 3.1.1(a), read from a summary of the page, not verified word for word).
 *
 * Android is not affected: Google Play billing already works.
 *
 * Read from `EXPO_PUBLIC_IOS_PURCHASES_ENABLED` (unset, the shipped state, means off; only the
 * exact string "true" turns it on), the same way as premiumVisibility.ts, so CodeQL does not
 * flag a hardcoded boolean. To turn it on: finish the Apple steps above, set the variable in the
 * EAS production environment, and ship a new iOS build.
 */
export const IOS_PURCHASES_ENABLED = process.env.EXPO_PUBLIC_IOS_PURCHASES_ENABLED === 'true';
