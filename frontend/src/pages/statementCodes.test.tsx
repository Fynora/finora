import fs from 'node:fs';
import path from 'node:path';
import { fireEvent, render, screen, within } from '@testing-library/react';
import { MemoryRouter, Route, Routes } from 'react-router-dom';
import { afterEach, beforeEach, describe, expect, it, vi, type MockInstance } from 'vitest';
import StatementCodePage from './StatementCodePage';
import StatementCodeIndex from './StatementCodeIndex';
import { NOT_FOUND_TITLE } from './NotFound';
import { PAYMENT_METHOD_LABELS } from './reportSections/methodBreakdown';
import {
  STATEMENT_CODES,
  STATEMENT_CODES_INDEX,
  STATEMENT_CODES_TITLE,
  STATEMENT_CODE_ROUTE,
  statementCodeBySlug,
  statementCodeFaqs,
  statementCodeForPath,
  statementCodePath,
} from './statementCodes';
import { breadcrumbs, structuredDataFor } from '../lib/structuredData';

const repoRoot = path.resolve(__dirname, '../../..');
const allText = (code: (typeof STATEMENT_CODES)[number]) =>
  [
    code.title, code.question, code.answer, code.description, code.fynora ?? '',
    ...code.sections.flatMap((s) => [s.heading, ...s.paragraphs]),
    ...code.examples.flatMap((e) => [e.line, e.note]),
    ...code.faqs.flat(),
  ].join('\n');

let scrollTo: MockInstance<typeof window.scrollTo>;
beforeEach(() => {
  scrollTo = vi.spyOn(window, 'scrollTo').mockImplementation(() => {});
});
afterEach(() => {
  vi.restoreAllMocks();
});

function renderAt(address: string) {
  return render(
    <MemoryRouter initialEntries={[address]}>
      <Routes>
        <Route path={STATEMENT_CODES_INDEX} element={<StatementCodeIndex />} />
        <Route path={STATEMENT_CODE_ROUTE} element={<StatementCodePage />} />
      </Routes>
    </MemoryRouter>
  );
}

describe('the code list', () => {
  it('has stable, unique, lower-case slugs, and every related slug is a real other code', () => {
    const slugs = STATEMENT_CODES.map((c) => c.slug);
    expect(new Set(slugs).size).toBe(slugs.length);
    for (const code of STATEMENT_CODES) {
      expect(code.slug).toMatch(/^[a-z0-9]+(-[a-z0-9]+)*$/);
      expect(code.related.length, code.slug).toBeGreaterThan(0);
      for (const slug of code.related) {
        expect(statementCodeBySlug(slug), `${code.slug} -> ${slug}`).toBeDefined();
        expect(slug, code.slug).not.toBe(code.slug);
      }
    }
  });

  it('covers the codes people search, each as its own page', () => {
    // From the 2026-10-31 keyword baseline: own pages for these; the rest share "other-codes".
    for (const slug of ['nach', 'imps', 'utr-number', 'ecs-return-charges', 'nach-return-charges', 'ecs', 'int-pd', 'neft',
      'pos-transaction', 'upi-transaction-id', 'ach-debit', 'atm-wdl', 'by-clg', 'tpt', 'other-codes']) {
      expect(statementCodeBySlug(slug), slug).toBeDefined();
    }
  });

  it('titles each page with the code people type, first', () => {
    for (const code of STATEMENT_CODES.filter((c) => c.slug !== 'other-codes')) {
      expect(code.title.toLowerCase().startsWith(code.code.toLowerCase()), code.slug).toBe(true);
    }
  });

  it('finds a code by slug and by address, and nothing else', () => {
    expect(statementCodeForPath('/bank-statement-codes/nach')?.slug).toBe('nach');
    expect(statementCodeForPath('/bank-statement-codes/nach/')?.slug).toBe('nach');
    expect(statementCodeForPath('/bank-statement-codes')).toBeUndefined();
    expect(statementCodeForPath('/bank-statement-codes/NACH')).toBeUndefined();
    expect(statementCodePath({ slug: 'nach' })).toBe('/bank-statement-codes/nach');
  });
});

describe('what the pages claim', () => {
  it('cites only the regulators\' own sites as sources', () => {
    for (const code of STATEMENT_CODES) {
      for (const source of code.sources) {
        expect(new URL(source.url).hostname, `${code.slug}: ${source.url}`).toMatch(/^(www\.)?(npci\.org\.in|rbi\.org\.in)$/);
      }
    }
  });

  it('quotes no charge amount: the only rupee figures are the two RBI limits the NEFT and RTGS FAQs state', () => {
    for (const code of STATEMENT_CODES) {
      for (const amount of allText(code).match(/₹[\d,]+/g) ?? []) {
        expect(['₹2,00,000', '₹50,000'], `${code.slug}: ${amount}`).toContain(amount);
      }
    }
  });

  it('uses only invented placeholder numbers and IFSCs in example lines, never anything real-looking', () => {
    for (const code of STATEMENT_CODES) {
      for (const { line } of code.examples) {
        for (const run of line.match(/\d{7,}/g) ?? []) {
          expect(run, `${code.slug}: ${line}`).toMatch(/^[12]0{6,}\d$/);
        }
        for (const ifsc of line.match(/\b[A-Z]{4}0[A-Z0-9]{6}\b/g) ?? []) {
          expect(ifsc, `${code.slug}: ${line}`).toMatch(/^[A-Z]{4}0X{6}$/);
        }
      }
    }
  });

  it('says how Fynora reads a code only with words the backend classifier really maps to that method', () => {
    // The classifier is the source of truth for "How Fynora reads it": every rail word a page names
    // must be in its RAIL_WORDS, mapped to the method whose label the page quotes.
    const java = fs.readFileSync(
      path.join(repoRoot, 'backend/src/main/java/com/finora/service/PaymentMethodClassifier.java'), 'utf-8');
    const railWords = new Map([...java.matchAll(/Map\.entry\("(\w+)", PaymentMethod\.(\w+)\)/g)].map((m) => [m[1], m[2]]));
    expect(railWords.get('nach')).toBe('AUTO_DEBIT');
    const checked: string[] = [];
    for (const code of STATEMENT_CODES) {
      const match = /^When (.+) starts a line on a bank account statement you import, Fynora counts the payment as "([^"]+)"/.exec(code.fynora ?? '');
      if (!match) continue;
      const method = Object.entries(PAYMENT_METHOD_LABELS).find(([, label]) => label === match[2])?.[0];
      expect(method, `${code.slug}: label ${match[2]}`).toBeDefined();
      for (const word of match[1].split(/, | or /)) {
        const tokens = word.toLowerCase().split(/\s+/);
        expect(tokens.some((t) => railWords.get(t) === method), `${code.slug}: "${word}" is not a ${method} word in the classifier`).toBe(true);
      }
      checked.push(code.slug);
    }
    expect(checked.length).toBeGreaterThanOrEqual(10);
  });

  it('names a cash withdrawal only by words the classifier reads as cash', () => {
    const atm = statementCodeBySlug('atm-wdl')!;
    expect(allText(atm)).toContain('Fynora reads ATM, ATW, NWD and CWDR');
  });
});

describe('a code page', () => {
  const nach = statementCodeBySlug('nach')!;

  it('has the title as its only <h1>, and the full answer before anything else', () => {
    renderAt(statementCodePath(nach));
    expect(screen.getAllByRole('heading', { level: 1 }).map((h) => h.textContent)).toEqual([nach.title]);
    expect(screen.getByRole('main').querySelector('p')?.textContent).toBe(nach.answer);
    expect(document.title).toBe(`${nach.title} — Fynora`);
  });

  it('shows its sources, example lines and questions, and links the index and related codes', () => {
    renderAt(statementCodePath(nach));
    for (const source of nach.sources) expect(screen.getByRole('link', { name: source.label })).toHaveAttribute('href', source.url);
    for (const example of nach.examples) expect(screen.getByText(example.line)).toBeInTheDocument();
    for (const [question] of nach.faqs) expect(screen.getByRole('heading', { level: 3, name: question })).toBeInTheDocument();
    const crumb = screen.getByRole('navigation', { name: 'Breadcrumb' });
    expect(within(crumb).getByRole('link', { name: 'Bank statement codes' })).toHaveAttribute('href', STATEMENT_CODES_INDEX);
    for (const slug of nach.related) {
      const related = statementCodeBySlug(slug)!;
      expect(screen.getByRole('link', { name: related.title })).toHaveAttribute('href', statementCodePath(related));
    }
  });

  it('scrolls to the top when a related code is followed', () => {
    renderAt(statementCodePath(nach));
    scrollTo.mockClear();
    const next = statementCodeBySlug(nach.related[0])!;
    fireEvent.click(screen.getByRole('link', { name: next.title }));
    expect(screen.getByRole('heading', { level: 1 })).toHaveTextContent(next.title);
    expect(scrollTo).toHaveBeenCalledWith(0, 0);
  });

  it('is the not-found page for a slug no code has', () => {
    renderAt('/bank-statement-codes/no-such-code');
    expect(screen.getByRole('heading', { level: 1 })).toHaveTextContent(NOT_FOUND_TITLE);
  });
});

describe('the index page', () => {
  it('links every code by its title, under the heading the breadcrumbs name', () => {
    renderAt(STATEMENT_CODES_INDEX);
    expect(screen.getByRole('heading', { level: 1 })).toHaveTextContent(STATEMENT_CODES_TITLE);
    for (const code of STATEMENT_CODES) {
      expect(screen.getByRole('link', { name: code.title })).toHaveAttribute('href', statementCodePath(code));
    }
  });
});

describe('structured data', () => {
  it('gives each code page Home > Bank statement codes > code, and its questions as a FAQPage', () => {
    for (const code of STATEMENT_CODES) {
      const route = statementCodePath(code);
      const blocks = structuredDataFor(route, code.title);
      expect(blocks.map((b) => b['@type']), code.slug).toEqual(['BreadcrumbList', 'FAQPage']);
      expect(blocks[0]).toEqual(breadcrumbs(route, code.title, [{ route: STATEMENT_CODES_INDEX, name: STATEMENT_CODES_TITLE }]));
      const questions = blocks[1].mainEntity as { name: string; acceptedAnswer: { text: string } }[];
      expect(questions.map((q) => [q.name, q.acceptedAnswer.text]), code.slug).toEqual(statementCodeFaqs(code));
      expect(questions[0].name).toBe(code.question);
    }
  });

  it('gives the index a breadcrumb only, and refuses an unknown code or a wrong heading', () => {
    expect(structuredDataFor(STATEMENT_CODES_INDEX, STATEMENT_CODES_TITLE).map((b) => b['@type'])).toEqual(['BreadcrumbList']);
    expect(() => structuredDataFor('/bank-statement-codes/no-such-code', 'X')).toThrow(/no code has that slug/);
    expect(() => structuredDataFor('/bank-statement-codes/nach', 'Some other heading')).toThrow(/not its code's title/);
  });
});
