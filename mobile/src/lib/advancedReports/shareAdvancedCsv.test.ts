import { advancedCsv } from './shareAdvancedCsv';
import { fixtureReport } from './testFixtures';

jest.mock('expo-file-system', () => ({ File: jest.fn(), Paths: { cache: 'cache' } }));
jest.mock('../shareFile', () => ({ shareFileAndCleanUp: jest.fn() }));

describe('advancedCsv', () => {
  it('writes every row, with every cell quoted', () => {
    const lines = advancedCsv(fixtureReport()).split('\n');
    expect(lines[0]).toBe('"Period","Apr 2026 – Sep 2026"');
    expect(lines).toContain('"Income","600000"');
  });

  it('guards a user-controlled name that starts like a formula, and doubles quotes', () => {
    const base = fixtureReport();
    const csv = advancedCsv(fixtureReport({
      categories: { ...base.categories, rows: [{ ...base.categories.rows[0], name: '=HYPERLINK("x")' }] },
    }));
    expect(csv).toContain(`"'=HYPERLINK(""x"")"`);
  });

  it('leaves an empty cell empty rather than writing "null"', () => {
    const base = fixtureReport();
    const csv = advancedCsv(fixtureReport({
      categories: { ...base.categories, rows: base.categories.rows.map((c) => ({ ...c, previousAmount: null, changeAmount: null })) },
    }));
    expect(csv).toContain('"Rent","120000","50","",""');
    expect(csv).not.toContain('null');
  });
});
