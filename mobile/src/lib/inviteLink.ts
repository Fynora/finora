import { linkToShare } from './webUrl';

/**
 * What the invite-a-friend card (components/InviteFriendCard.tsx) shares: the person's own referral
 * link, tagged so the sign-ups and installs it brings are counted as referral traffic. The web app
 * builds the same message (frontend/src/lib/inviteLink.ts); keep the two in step.
 *
 *  - The sign-up link is the web app's /register with the referral code (`ref`) and campaign tags.
 *    In a browser the website keeps the tags for the sign-up (the activation page lists these
 *    accounts under utm_source friend_invite) and fills in the code. On a phone with Fynora
 *    installed, the OS hands this https link to the app (appLinks.config.js claims /register), and
 *    useReferralDeepLink opens Register with the code filled in.
 *  - The Android link is the Google Play listing with the same tags as its install referrer, so
 *    Play Console counts these installs under "Ads and referrals", campaign after_import. The
 *    code is not in it (an install referrer goes to Google, and the app does not read it); it is in
 *    the message instead, for the Register screen's referral field.
 *  - The iPhone link is the App Store listing, plain: Apple's campaign links need the developer
 *    account's provider token, which this code does not have. The code in the message serves the
 *    iPhone app's Register screen too.
 */

export const INVITE_UTM_SOURCE = 'friend_invite';
export const INVITE_UTM_MEDIUM = 'referral';
export const INVITE_UTM_CAMPAIGN = 'after_import';

/** The production Android app's package (app.config.ts `android.package`). */
export const ANDROID_PACKAGE = 'com.fynora.android';

/** The iOS app's App Store listing, India storefront: the same URL as the web app's APP_STORE_URL
 *  (frontend/src/lib/openInApp.ts), which explains why the storefront is in the path. */
export const APP_STORE_URL = 'https://apps.apple.com/in/app/fynora-personal-finance/id6809816093'; // synthetic-ok: the public App Store listing URL, not customer data

const TAGS = `utm_source=${INVITE_UTM_SOURCE}&utm_medium=${INVITE_UTM_MEDIUM}&utm_campaign=${INVITE_UTM_CAMPAIGN}`;

/** The web sign-up link with this person's referral code and the campaign tags. A link to share,
 *  not a page this app opens, so /register being a claimed path is intended (see linkToShare). */
export function inviteSignupLink(code: string): string {
  return linkToShare(`/register?ref=${encodeURIComponent(code)}&${TAGS}`);
}

/** The Google Play listing, with the campaign tags as its install referrer. */
export function invitePlayLink(): string {
  return `https://play.google.com/store/apps/details?id=${ANDROID_PACKAGE}&referrer=${encodeURIComponent(TAGS)}`;
}

/** The App Store listing. */
export function inviteAppStoreLink(): string {
  return APP_STORE_URL;
}

/** The whole message for the share sheet. */
export function inviteMessage(code: string): string {
  return [
    "I've been using Fynora to see where my money goes. It reads your bank statement and sorts everything for you.",
    '',
    `Sign up with my link: ${inviteSignupLink(code)}`,
    `Or get the Android app: ${invitePlayLink()}`,
    `Or the iPhone app: ${inviteAppStoreLink()}`,
    `My referral code: ${code}`,
  ].join('\n');
}
