// The words for a card instalment row (Transaction.cardInstalment). One place, so the badge and
// the explanation behind it cannot drift apart. Identical file in mobile/src/lib.
//
// Why the row is marked at all: an instalment repeats part of a purchase the card already billed.
// When the statement with that purchase was imported too, every spending total counts both. The
// mark changes no figure; it tells the user what the row is, so a total that looks too high has an
// explanation they can find.

/** The short mark shown on the row. */
export const CARD_INSTALMENT_LABEL = 'EMI instalment';

/** What the mark means, in full. A screen reader hears this; a tooltip and the detail view show it. */
export const CARD_INSTALMENT_NOTE =
  'A monthly instalment on an earlier card purchase. If that purchase is also in Fynora, your spending totals count both the purchase and its instalments.';
