import type { ComponentProps } from 'react';
import type Ionicons from '@expo/vector-icons/Ionicons';

type IoniconName = ComponentProps<typeof Ionicons>['name'];

// Maps the curated icon-token vocabulary CategoryPalette.ICONS defines server-side (backend/src/
// main/java/com/finora/util/CategoryPalette.java) to an Ionicons glyph -- mirrors
// frontend/src/lib/categoryIcons.ts's identical ICON_COMPONENTS map, one level removed: web looks
// up a lucide-react component per token, this looks up an Ionicons name per token. Every name
// below was checked against the installed glyph map (@expo/vector-icons's Ionicons.json), not
// guessed from memory -- an icon name that doesn't exist renders nothing, silently.
//
// Every default category (the V118 migration's backfill) and every user-created one
// (CategoryPalette's own validation) draws its icon token from exactly this set, so nothing here
// should ever miss -- ICON_NAMES.tag is the fallback for the (should-never-happen) case where the
// backend ships a token this map hasn't been updated for yet.
export const ICON_NAMES: Record<string, IoniconName> = {
  tag: 'pricetag-outline',
  home: 'home-outline',
  'shopping-cart': 'cart-outline',
  utensils: 'restaurant-outline',
  car: 'car-outline',
  zap: 'flash-outline',
  'shopping-bag': 'bag-outline',
  'heart-pulse': 'pulse-outline',
  film: 'film-outline',
  'trending-up': 'trending-up-outline',
  percent: 'calculator-outline',
  repeat: 'repeat-outline',
  users: 'people-outline',
  landmark: 'business-outline',
  shield: 'shield-checkmark-outline',
  'graduation-cap': 'school-outline',
  'refresh-cw': 'refresh-outline',
  plane: 'airplane-outline',
  gift: 'gift-outline',
  'paw-print': 'paw-outline',
  sofa: 'bed-outline',
  receipt: 'receipt-outline',
  banknote: 'cash-outline',
  briefcase: 'briefcase-outline',
  'arrow-down-circle': 'arrow-down-circle-outline',
};

export function iconNameFor(token: string): IoniconName {
  return ICON_NAMES[token] ?? ICON_NAMES.tag;
}

// Same 9 hex values as CategoryPalette.COLORS server-side -- mirrors web's identical COLOR_HEX.
// A category's own `color` field (from /categories, or stored on a transaction) is the bare
// token, not a ready-to-use hex string; /categories/options' own `colors[].label` IS already hex
// (see CategoryOptions' doc comment in api/endpoints.ts) but this static copy is what lets a
// category badge render its color without an extra options fetch, same reason web keeps one too.
export const COLOR_HEX: Record<string, string> = {
  gray: '#6b7280', blue: '#2563eb', green: '#16a34a', red: '#dc2626', orange: '#ea580c',
  yellow: '#d97706', purple: '#7c3aed', pink: '#db2777', teal: '#0d9488',
};

export function colorHexFor(token: string): string {
  return COLOR_HEX[token] ?? COLOR_HEX.gray;
}
