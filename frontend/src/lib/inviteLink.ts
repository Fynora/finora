import { PLAY_STORE_URL } from './openInApp';

/**
 * What the invite-a-friend card (components/InviteFriendCard.tsx) shares: the person's own referral
 * link, tagged so the sign-ups and installs it brings are counted as referral traffic.
 *
 *  - The sign-up link opens /register with the referral code (`ref`) and campaign tags. The website
 *    keeps the tags for the sign-up (lib/signupAttribution.ts), so the activation page lists these
 *    accounts under utm_source friend_invite, and the code credits the friend who shared it
 *    (ReferralService.redeemCode). On a phone with the Fynora app installed, the same https link
 *    opens the app's Register screen with the code filled in (mobile/appLinks.config.js claims
 *    /register).
 *  - The Android link is the Google Play listing with the same tags as its install referrer, so
 *    Play Console counts these installs under "Ads and referrals", campaign after_first_import
 *    (the format is explained in lib/playStoreLink.ts). The referral code is not in it: an install
 *    referrer goes to Google, and the app does not read it. The code is in the message instead, for
 *    the Register screen's referral field.
 *
 * The mobile app builds the same message (mobile/src/lib/inviteLink.ts); keep the two in step.
 */

export const INVITE_UTM_SOURCE = 'friend_invite';
export const INVITE_UTM_MEDIUM = 'referral';
export const INVITE_UTM_CAMPAIGN = 'after_first_import';

const TAGS = `utm_source=${INVITE_UTM_SOURCE}&utm_medium=${INVITE_UTM_MEDIUM}&utm_campaign=${INVITE_UTM_CAMPAIGN}`;

/** The web sign-up link with this person's referral code and the campaign tags. */
export function inviteSignupLink(origin: string, code: string): string {
  return `${origin.replace(/\/+$/, '')}/register?ref=${encodeURIComponent(code)}&${TAGS}`;
}

/** The Google Play listing, with the campaign tags as its install referrer. */
export function invitePlayLink(): string {
  return `${PLAY_STORE_URL}&referrer=${encodeURIComponent(TAGS)}`;
}

/** The whole message for a share sheet. */
export function inviteMessage(origin: string, code: string): string {
  return [
    "I've been using Fynora to see where my money goes. It reads your bank statement and sorts everything for you.",
    '',
    `Sign up with my link: ${inviteSignupLink(origin, code)}`,
    `Or get the Android app: ${invitePlayLink()}`,
    `My referral code: ${code}`,
  ].join('\n');
}
