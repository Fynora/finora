/**
 * Hex-valued tokens (`--color-success: #16a34a`) have no `<alpha-value>` slot, so Tailwind silently
 * generates nothing at all for an opacity modifier on them -- `border-success/20`, `bg-warning/10`
 * and `bg-bg/50` were all dead classes, leaving those elements on the default border colour or
 * with no background. This mixes the token with transparent when a modifier is given, and returns
 * the plain variable otherwise so every unmodified class compiles exactly as before.
 * (`ink` and `primary` are channel-valued and already take modifiers natively.)
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
  // Same class-based dark mode / semantic color token approach as the user frontend (see
  // src/index.css) -- kept visually consistent as "the same product family," not a re-skin.
  darkMode: 'class',
  content: ['./index.html', './src/**/*.{js,ts,jsx,tsx}'],
  theme: {
    extend: {
      colors: {
        sidebar: token('sidebar'),
        'sidebar-hover': token('sidebar-hover'),
        bg: token('bg'),
        card: token('card'),
        border: token('border'),
        surface: token('surface'),
        ink: 'rgb(var(--color-ink) / <alpha-value>)',
        muted: token('muted'),
        primary: 'rgb(var(--color-primary) / <alpha-value>)',
        'primary-dark': token('primary-dark'),
        'primary-light': token('primary-light'),
        'on-primary': token('on-primary'),
        success: token('success'),
        'success-bg': token('success-bg'),
        danger: token('danger'),
        'danger-bg': token('danger-bg'),
        warning: token('warning'),
        'warning-bg': token('warning-bg'),
        info: token('info'),
        'info-bg': token('info-bg'),
      },
      fontFamily: {
        sans: ['Inter', 'system-ui', 'sans-serif'],
        // Reserved for numerals, timestamps, and status codes on data-dense screens (the
        // Operational Dashboard) -- gives figures a fixed-width "instrument panel" feel that
        // sets them apart from prose, without touching the sans-everywhere shared identity
        // elsewhere in the portal (Sidebar, forms, etc. are untouched).
        mono: ['"JetBrains Mono"', 'ui-monospace', 'SFMono-Regular', 'monospace'],
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
