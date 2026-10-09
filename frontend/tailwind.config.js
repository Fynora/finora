/**
 * Hex-valued tokens (`--color-warning: #d97706`) have no `<alpha-value>` slot, so Tailwind silently
 * generates nothing at all for an opacity modifier on them -- `border-warning/30`,
 * `hover:bg-danger/90` and the premium Button's `hover:bg-premium/90` were all dead classes, leaving
 * those elements on the default border colour or with no hover change. This mixes the token with
 * transparent when a modifier is given, and returns the plain variable otherwise so every
 * unmodified class compiles exactly as before. (`ink` and `primary` are channel-valued and already
 * take modifiers natively.) Same helper as admin-portal/tailwind.config.js.
 *
 * An unmodified class still receives an opacity value -- Tailwind's own `var(--tw-bg-opacity, 1)`
 * and friends, always 1 since no `*-opacity-*` utility is used -- which is why that one shape is
 * matched exactly rather than every `var(...)`: an arbitrary `/[var(--x)]` modifier must still
 * apply. A modifier can be a fraction (`/20` arrives as `0.2`) or, written arbitrarily, a
 * percentage (`/[15%]`), which color-mix takes as-is.
 */
const TAILWIND_OPACITY_VARIABLE = /^var\(--tw-[a-z-]*opacity(?:,\s*1)?\)$/;
const token = (name) => ({ opacityValue }) => {
  const alpha = opacityValue === undefined ? '' : String(opacityValue).trim();
  if (alpha === '' || TAILWIND_OPACITY_VARIABLE.test(alpha)) return `var(--color-${name})`;
  const share = alpha.endsWith('%') ? alpha : `calc(${alpha} * 100%)`;
  return `color-mix(in srgb, var(--color-${name}) ${share}, transparent)`;
};

/** @type {import('tailwindcss').Config} */
export default {
  // Class-based (not media-query-based) so the ThemeContext's explicit Light/Dark/System
  // choice — not just the OS setting — controls which palette applies. See src/index.css
  // for the `:root` / `.dark` variable definitions these all resolve to.
  darkMode: 'class',
  content: ['./index.html', './src/**/*.{js,ts,jsx,tsx}'],
  theme: {
    extend: {
      colors: {
        // Sidebar / dark surfaces — intentionally not theme-dependent (see index.css comment)
        sidebar: token('sidebar'),
        'sidebar-hover': token('sidebar-hover'),
        // App background + cards
        bg: token('bg'),
        card: token('card'),
        border: token('border'),
        surface: token('surface'),
        // Text — rgb()/<alpha-value> form because their tokens are stored as channels; every
        // other colour goes through token() above for its opacity modifiers (e.g. text-ink/60
        // here, border-warning/30 there)
        ink: 'rgb(var(--color-ink) / <alpha-value>)',
        muted: token('muted'),
        // Brand
        primary: 'rgb(var(--color-primary) / <alpha-value>)',
        'primary-dark': token('primary-dark'),
        'primary-light': token('primary-light'),
        'on-primary': token('on-primary'),
        // Semantic
        success: token('success'),
        'success-bg': token('success-bg'),
        danger: token('danger'),
        'danger-bg': token('danger-bg'),
        warning: token('warning'),
        'warning-bg': token('warning-bg'),
        // Premium brand accent — separate from the decorative accent-* family below; see
        // index.css's comment on why it's gated to specific paid-tier moments only.
        premium: token('premium'),
        'premium-bg': token('premium-bg'),
        'on-premium': token('on-premium'),
        // Fixed (non-toggling) graphite/paper pair — see index.css's comment on these
        'fixed-dark': token('fixed-dark'),
        'fixed-light': token('fixed-light'),
        'fixed-ink': token('fixed-ink'),
        'fixed-ink-2': token('fixed-ink-2'),
        'fixed-ink-3': token('fixed-ink-3'),
        'fixed-ink-hover': token('fixed-ink-hover'),
        'premium-fixed': token('premium-fixed'),
        'on-premium-fixed': token('on-premium-fixed'),
        // Shared dark-surface pair for SiteFooter and the marketing surface's own dark
        // sections — see index.css's comment on these.
        'deep-surface': token('deep-surface'),
        'deep-ink': token('deep-ink'),
        // Decorative icon-chip accents — see index.css's comment on these
        'accent-blue': token('accent-blue'),
        'accent-blue-bg': token('accent-blue-bg'),
        'accent-green': token('accent-green'),
        'accent-green-bg': token('accent-green-bg'),
        'accent-red': token('accent-red'),
        'accent-red-bg': token('accent-red-bg'),
        'accent-purple': token('accent-purple'),
        'accent-purple-bg': token('accent-purple-bg'),
        'accent-orange': token('accent-orange'),
        'accent-orange-bg': token('accent-orange-bg'),
        'accent-teal': token('accent-teal'),
        'accent-teal-bg': token('accent-teal-bg'),
      },
      fontFamily: {
        sans: ['Inter', 'system-ui', 'sans-serif'],
        // Same pair the marketing page already loads (see index.css's `.m-display`/`.m-h2`
        // classes and index.html's Google Fonts link) -- this just makes it available as a
        // Tailwind utility for product-UI headings outside the marketing surface, starting with
        // the auth screens.
        display: ['Manrope', 'Inter', 'system-ui', 'sans-serif'],
        // Statement History's hero annotation only (index.html loads Caveat at weight 600, the
        // only one anything sets) -- a hand-drawn note reads as decoration, not product UI, so it
        // stays off `sans`/`display` rather than becoming a third general-purpose typeface choice.
        handwriting: ['Caveat', 'cursive'],
      },
      fontSize: {
        // The size the product app actually reaches for below `xs` (12px) -- 108 arbitrary
        // `text-[11px]` plus most of 36 `text-[10px]` uses (audited 2026-09-12) were both really
        // asking for this one missing step, not two: an uppercase micro-label (field labels,
        // section eyebrows) and a plain muted caption both want the same size, just a different
        // weight/case on top of it. One named token instead of two arbitrary pixel values that
        // happened to land a pixel apart.
        '2xs': ['0.6875rem', { lineHeight: '1rem' }],
        // The "big number" size four call sites already agreed on independently
        // (MetricCard's elevated variant, StatementHistory, Ledger, Dashboard's greeting) via
        // `text-[26px] font-display font-extrabold` -- formalizing an existing pattern, not
        // inventing a new one. Weight/tracking stay as each call site's own utilities; this token
        // is size + line-height only.
        'display-sm': ['1.625rem', { lineHeight: '2rem' }],
      },
      boxShadow: {
        card: '0 1px 2px rgba(16,24,40,0.04), 0 1px 3px rgba(16,24,40,0.06)',
        soft: '0 4px 24px rgba(16,24,40,0.08)',
      },
      borderRadius: {
        xl2: '1rem',
      },
    },
  },
  plugins: [],
};
