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
 * These are @fontsource's per-weight files (`400.css`), NOT the per-subset ones (`latin-400.css`).
 * The per-weight file declares one @font-face per subset (latin, latin-ext, cyrillic, greek,
 * vietnamese), each with the same unicode-range Google Fonts sends, so a browser downloads only the
 * subsets the page's text needs. The per-subset files carry no unicode-range at all, and that
 * matters here: the rupee sign U+20B9 is in latin-ext, not latin (checked in the font files: the
 * latin WOFF2 has no glyph for it, the latin-ext one does). With latin only, every amount in a
 * finance app would have drawn its ₹ in the system fallback font next to Inter digits. Google
 * Fonts never did that, because its latin-ext face covered it; this keeps that behaviour.
 *
 * Which weights: the same ones index.html's Google Fonts URL requested. Inter carries the product
 * UI (tailwind.config.js `sans`); Manrope is headings-only, on the marketing page and the auth
 * flow (`display`); Caveat is Statement History's hand-drawn annotation and Billing (`handwriting`),
 * one weight. A weight nothing sets would be a download for nothing, so do not add one here
 * without a use.
 */
import '@fontsource/inter/400.css';
import '@fontsource/inter/500.css';
import '@fontsource/inter/600.css';
import '@fontsource/inter/700.css';
import '@fontsource/inter/800.css';
import '@fontsource/manrope/600.css';
import '@fontsource/manrope/700.css';
import '@fontsource/manrope/800.css';
import '@fontsource/caveat/600.css';
