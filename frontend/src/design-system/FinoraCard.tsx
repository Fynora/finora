import type { ReactNode } from 'react';

const PADDING = {
  none: '',
  sm: 'p-4',
  md: 'p-5',
  lg: 'p-6',
} as const;

const TIER = {
  secondary: 'shadow-card border-border',
  // For the minority of sections that changed or need a decision (see Dashboard's Financial
  // Health Score / Next Actions / Detected Issues) -- reuses shadow-soft and the border-primary/NN
  // pattern the health-score insight card already established, rather than inventing a new visual
  // language for "this matters more."
  primary: 'shadow-soft border-primary/20',
} as const;

/**
 * The one card shell every page hand-rolled its own slightly-different version of --
 * `bg-card rounded-xl2 shadow-card border border-border` with a padding value nobody agreed on.
 * `padding="none"` is for cards with their own internal header/body padding split (e.g. a card
 * with a bordered header row and a differently-padded body) rather than one uniform inset.
 *
 * `tier="primary"` is the one intentional exception to "every FinoraCard looks the same" --
 * without it, a page with N sections gives all N identical visual weight regardless of whether
 * they're a stable reference list or something that actually needs the user's attention right
 * now. Defaults to `secondary`, i.e. today's unchanged look, so no existing call site changes
 * unless it opts in.
 */
export function FinoraCard({
  children, padding = 'md', tier = 'secondary', className = '',
}: {
  children: ReactNode; padding?: keyof typeof PADDING; tier?: keyof typeof TIER; className?: string;
}) {
  return (
    <div className={`bg-card rounded-xl2 border ${TIER[tier]} ${PADDING[padding]} ${className}`}>
      {children}
    </div>
  );
}
