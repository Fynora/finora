// The Google Play QR codes on the public pages (src/components/GetTheApp.tsx PlayQr and the landing
// footer). Each is a static SVG at /qr/play-<page type>-<slug>.svg that encodes that page's Play
// link with its install referrer (src/lib/playStoreLink.ts playQrLinkForFile). scripts/prerender.mjs
// writes one for every prerendered page; vite.config.ts answers the same paths in `vite dev`.
//
// The same settings the footer's original code was made with: error correction M, a 4-module quiet
// zone (the white border a scanner needs), the site's ink colour on white. `qrcode` is a dev
// dependency: it runs at build time only and ships nothing to the browser.
import QRCode from 'qrcode';

export const QR_OPTIONS = Object.freeze({
  type: 'svg',
  errorCorrectionLevel: 'M',
  margin: 4,
  color: { dark: '#0F172A', light: '#FFFFFF' },
});

/** The SVG for one link. */
export function qrSvg(text) {
  return QRCode.toString(text, QR_OPTIONS);
}
