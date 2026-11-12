import { describe, expect, it } from 'vitest';
import jsQR from 'jsqr';
import { qrSvg } from './playQr.mjs';
import { playQrLinkForFile, playQrPath } from '../src/lib/playStoreLink';
import { BANK_PAGES, bankPagePath } from '../src/pages/bankPages';

/**
 * Reads the module grid back out of the SVG `qrcode` writes: a white square, then one stroked path
 * of horizontal runs ("M4 4.5h7m3 0h1 ..."), one unit per module, each run on a row's centre line.
 * Decoding that grid checks the file the site serves, not just the library's input.
 */
function modulesFromSvg(svg: string): { size: number; dark: boolean[][] } {
  const size = Number(/viewBox="0 0 (\d+) \1"/.exec(svg)?.[1]);
  const d = /<path stroke="#0F172A" d="([^"]+)"/.exec(svg)?.[1];
  if (!size || !d) throw new Error('not a qrcode SVG');
  const dark = Array.from({ length: size }, () => new Array<boolean>(size).fill(false));
  let x = 0;
  let y = 0;
  for (const [, op, args] of d.matchAll(/([MmhH])([^MmhH]*)/g)) {
    const nums = args.trim().split(/[\s,]+/).filter(Boolean).map(Number);
    if (op === 'M') [x, y] = nums;
    else if (op === 'm') { x += nums[0]; y += nums[1]; }
    else if (op === 'h') {
      for (let i = 0; i < nums[0]; i++) dark[Math.floor(y)][x + i] = true;
      x += nums[0];
    } else throw new Error(`unexpected ${op}`);
  }
  return { size, dark };
}

function decode(svg: string): string | null {
  const { size, dark } = modulesFromSvg(svg);
  const scale = 4;
  const px = size * scale;
  const data = new Uint8ClampedArray(px * px * 4);
  for (let row = 0; row < px; row++) {
    for (let col = 0; col < px; col++) {
      const v = dark[Math.floor(row / scale)][Math.floor(col / scale)] ? 0 : 255;
      data.set([v, v, v, 255], (row * px + col) * 4);
    }
  }
  return jsQR(data, px, px)?.data ?? null;
}

describe('Google Play QR codes', () => {
  it('decode to the exact Play link for the page, referrer included', async () => {
    const pages = ['/', '/bank-statement-to-excel', '/help', ...BANK_PAGES.map(bankPagePath)];
    for (const page of pages) {
      const link = playQrLinkForFile(playQrPath(page))!;
      expect(link, page).toContain('&referrer=utm_source%3Dfynora_web%26');
      expect(decode(await qrSvg(link)), page).toBe(link);
    }
  });

  it('have the 4-module quiet zone a scanner needs', async () => {
    const { size, dark } = modulesFromSvg(await qrSvg(playQrLinkForFile(playQrPath('/banks'))!));
    for (let i = 0; i < size; i++) {
      for (const j of [0, 1, 2, 3, size - 4, size - 3, size - 2, size - 1]) {
        expect(dark[i][j] || dark[j][i]).toBe(false);
      }
    }
  });
});
