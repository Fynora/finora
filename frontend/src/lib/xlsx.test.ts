import { describe, expect, it } from 'vitest';
import { columnName, crc32, escapeXml, excelSerial, sheetNames, workbookParts, xlsx, zipStored } from './xlsx';

/** Reads back a stored zip: name -> UTF-8 content, checking each entry's CRC on the way. */
function unzipStored(bytes: Uint8Array): Record<string, string> {
  const view = new DataView(bytes.buffer, bytes.byteOffset, bytes.byteLength);
  const decoder = new TextDecoder();
  const files: Record<string, string> = {};
  let at = 0;
  while (view.getUint32(at, true) === 0x04034b50) {
    expect(view.getUint16(at + 8, true), 'compression method').toBe(0);
    const crc = view.getUint32(at + 14, true);
    const size = view.getUint32(at + 18, true);
    const nameLength = view.getUint16(at + 26, true);
    const name = decoder.decode(bytes.subarray(at + 30, at + 30 + nameLength));
    const data = bytes.subarray(at + 30 + nameLength, at + 30 + nameLength + size);
    expect(crc32(data), `${name} crc`).toBe(crc);
    files[name] = decoder.decode(data);
    at += 30 + nameLength + size;
  }
  expect(view.getUint32(bytes.length - 22, true), 'end of central directory').toBe(0x06054b50);
  expect(view.getUint16(bytes.length - 22 + 10, true), 'entry count').toBe(Object.keys(files).length);
  return files;
}

describe('xlsx helpers', () => {
  it('names columns the way spreadsheets do', () => {
    expect([0, 1, 25, 26, 27, 51, 52, 701, 702].map(columnName)).toEqual(['A', 'B', 'Z', 'AA', 'AB', 'AZ', 'BA', 'ZZ', 'AAA']);
  });

  it('turns an ISO date into Excel’s day number', () => {
    expect(excelSerial('2000-01-01')).toBe(36526);
    expect(excelSerial('2026-09-03')).toBe(46268);
    expect(() => excelSerial('03-09-2026')).toThrow();
  });

  it('escapes XML and drops characters XML cannot carry', () => {
    expect(escapeXml('A & B <c> "d"\u0001')).toBe('A &amp; B &lt;c&gt; &quot;d&quot;');
  });

  it('computes the standard CRC-32', () => {
    expect(crc32(new TextEncoder().encode('123456789'))).toBe(0xcbf43926);
  });

  it('cleans, shortens and de-duplicates sheet names', () => {
    expect(sheetNames(['A/B', 'a/b', 'x'.repeat(40), ''])).toEqual(['A B', 'a b (2)', 'x'.repeat(31), 'Sheet 4']);
  });
});

describe('zipStored', () => {
  it('writes entries a reader gets back byte for byte, UTF-8 included', () => {
    const files = { 'a.txt': 'hello', 'dir/b.xml': '<x>₹ 1,234.50</x>' };
    expect(unzipStored(zipStored(files))).toEqual(files);
  });

  it('is deterministic', () => {
    expect(zipStored({ a: 'b' })).toEqual(zipStored({ a: 'b' }));
  });
});

describe('xlsx', () => {
  const book = xlsx([
    {
      name: 'Transactions',
      rows: [
        ['Date', 'Description', 'Debit'],
        [{ date: '2026-09-03' }, '=HYPERLINK("http://x")', 1234.5],
        [{ date: '2026-09-04' }, 'A & B', null],
      ],
    },
    { name: 'About this file', rows: [['Made with Fynora']] },
  ]);
  const parts = unzipStored(book);

  it('holds every part a spreadsheet reader needs, one worksheet per sheet', () => {
    expect(Object.keys(parts).sort()).toEqual([
      '[Content_Types].xml', '_rels/.rels', 'xl/_rels/workbook.xml.rels', 'xl/styles.xml',
      'xl/workbook.xml', 'xl/worksheets/sheet1.xml', 'xl/worksheets/sheet2.xml',
    ]);
    expect(parts['xl/workbook.xml']).toContain('<sheet name="Transactions" sheetId="1" r:id="rId1"/>');
    expect(parts['xl/workbook.xml']).toContain('<sheet name="About this file" sheetId="2" r:id="rId2"/>');
  });

  it('writes text as inline text, so a description can never run as a formula', () => {
    const sheet = parts['xl/worksheets/sheet1.xml'];
    expect(sheet).toContain('<c r="B2" t="inlineStr"><is><t xml:space="preserve">=HYPERLINK(&quot;http://x&quot;)</t></is></c>');
    expect(sheet).not.toContain('<f>');
  });

  it('writes amounts as numbers and dates as dated numbers, and leaves empty cells out', () => {
    const sheet = parts['xl/worksheets/sheet1.xml'];
    expect(sheet).toContain('<c r="C2"><v>1234.5</v></c>');
    expect(sheet).toContain('<c r="A2" s="1"><v>46268</v></c>');
    expect(sheet).not.toContain('r="C3"');
    expect(parts['xl/styles.xml']).toContain('formatCode="dd\\-mm\\-yyyy"');
  });

  it('refuses a workbook with no sheets', () => {
    expect(() => workbookParts([])).toThrow();
  });
});
