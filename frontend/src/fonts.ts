/**
 * The web fonts, served from this origin instead of fonts.googleapis.com.
 *
 * index.html used to carry one Google Fonts <link rel="stylesheet"> for all three families. That
 * link was the single most expensive thing on the homepage's critical path: a render-blocking
 * request to a third host (DNS, TLS, then the CSS, then the font files from a fourth host,
 * fonts.gstatic.com) that Lighthouse put at 828 ms on a throttled mobile connection, with 176 KB
 * of font bytes behind it. Imported here, the @font-face rules land in the app's own hashed CSS
 * file and the WOFF2 files in /assets/, which Cloudflare already serves with a one-year immutable
 * cache (public/_headers). One origin, one connection, no third-party stylesheet to wait for.
 *
 * Only the latin subset of each family is imported. The @fontsource per-subset files carry no
 * unicode-range, so importing latin and latin-ext for the same weight would make the later one
 * win for every character. Glyphs outside latin (the rupee sign is one) fall back to the system
 * font exactly as they did with Google Fonts, whose latin subset does not include U+20B9 either.
 *
 * Which weights: the same ones index.html's Google Fonts URL requested. Inter carries the product
 * UI (tailwind.config.js `sans`); Manrope is headings-only, on the marketing page and the auth
 * flow (`display`); Caveat is Statement History's hand-drawn annotation and Billing (`handwriting`),
 * one weight. A weight nothing sets would be a download for nothing, so do not add one here
 * without a use.
 */
import '@fontsource/inter/latin-400.css';
import '@fontsource/inter/latin-500.css';
import '@fontsource/inter/latin-600.css';
import '@fontsource/inter/latin-700.css';
import '@fontsource/inter/latin-800.css';
import '@fontsource/manrope/latin-600.css';
import '@fontsource/manrope/latin-700.css';
import '@fontsource/manrope/latin-800.css';
import '@fontsource/caveat/latin-600.css';
