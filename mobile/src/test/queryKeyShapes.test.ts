import fs from 'fs';
import path from 'path';
import ts from 'typescript';

/**
 * Guards against the bug behind FYNORA-MOBILE-K: ImportScreen stored a list of category NAMES under
 * ['categories'] while the picker, Ledger, Reports and RecurringQuestion read the same key as full
 * category objects, so after a visit to Import they got strings and `cat.name.toLowerCase()` threw.
 * 'categories' is also persisted to disk, so the wrong shape survived an app restart.
 *
 * React Query keeps ONE cache entry per key, whoever asked for it. So every query that uses the same
 * key must fetch the same thing and cache it as the server returned it. Two rules, read straight
 * from the source (every object literal with a `queryKey` and a `queryFn`):
 *
 *  1. Queries with the same key (dynamic parts treated as wildcards) call the same API methods.
 *  2. A queryFn does not reshape its response (.then/.map/.filter...): the shape cached is the
 *     shape the other readers get. A per-screen view of the data belongs in `select`.
 *
 * If a rule fires on something deliberate, add it to KNOWN_EXCEPTIONS with the reason.
 */

const SRC = path.resolve(__dirname, '..');

/** "file:line" of a query that is allowed to break a rule, with why. Empty on purpose. */
const KNOWN_EXCEPTIONS: Record<string, string> = {};

interface QueryDef {
  where: string;
  keyShape: string;
  apis: string[];
  fnText: string;
}

function sourceFiles(dir: string): string[] {
  const out: string[] = [];
  for (const entry of fs.readdirSync(dir, { withFileTypes: true })) {
    const full = path.join(dir, entry.name);
    if (entry.isDirectory()) {
      if (entry.name === 'test' || entry.name === 'node_modules') continue;
      out.push(...sourceFiles(full));
    } else if (/\.tsx?$/.test(entry.name) && !/\.test\.tsx?$/.test(entry.name)) {
      out.push(full);
    }
  }
  return out;
}

/** `['people', 'pick', mode, id]` -> `['people','pick',*,*]`: string literals stay, anything else is a wildcard. */
function keyShape(key: ts.ArrayLiteralExpression): string | null {
  const parts = key.elements.map((el) => {
    if (ts.isStringLiteralLike(el)) return `'${el.text}'`;
    if (el.kind === ts.SyntaxKind.NullKeyword) return 'null';
    return '*';
  });
  // A key that does not start with a literal is not one this check can reason about.
  if (!parts[0] || !parts[0].startsWith("'")) return null;
  return `[${parts.join(',')}]`;
}

function collectQueries(): QueryDef[] {
  const defs: QueryDef[] = [];
  for (const file of sourceFiles(SRC)) {
    const text = fs.readFileSync(file, 'utf8');
    const sf = ts.createSourceFile(file, text, ts.ScriptTarget.Latest, true, file.endsWith('x') ? ts.ScriptKind.TSX : ts.ScriptKind.TS);
    const visit = (node: ts.Node) => {
      if (ts.isObjectLiteralExpression(node)) {
        const prop = (name: string) =>
          node.properties.find((p): p is ts.PropertyAssignment => ts.isPropertyAssignment(p) && p.name.getText() === name);
        const keyProp = prop('queryKey');
        const fnProp = prop('queryFn');
        if (keyProp && fnProp && ts.isArrayLiteralExpression(keyProp.initializer)) {
          const shape = keyShape(keyProp.initializer);
          if (shape) {
            const fnText = fnProp.initializer.getText();
            const apis = [...new Set(fnText.match(/\b[A-Za-z]+Api\.[A-Za-z]+/g) ?? [])].sort();
            const line = sf.getLineAndCharacterOfPosition(node.getStart()).line + 1;
            defs.push({ where: `${path.relative(SRC, file)}:${line}`, keyShape: shape, apis, fnText });
          }
        }
      }
      ts.forEachChild(node, visit);
    };
    visit(sf);
  }
  return defs;
}

describe('react-query keys keep one shape each', () => {
  const defs = collectQueries();

  it('finds the queries it is meant to check', () => {
    // A sanity floor, so a broken scan cannot pass by finding nothing.
    expect(defs.length).toBeGreaterThan(40);
    expect(defs.some((d) => d.keyShape === "['categories']")).toBe(true);
  });

  it('gives every query on the same key the same API call', () => {
    const byKey = new Map<string, QueryDef[]>();
    for (const d of defs) byKey.set(d.keyShape, [...(byKey.get(d.keyShape) ?? []), d]);

    const problems: string[] = [];
    for (const [key, group] of byKey) {
      const withApi = group.filter((d) => d.apis.length > 0 && !(d.where in KNOWN_EXCEPTIONS));
      const distinct = new Set(withApi.map((d) => d.apis.join('+')));
      if (distinct.size > 1) {
        problems.push(`${key}: ${withApi.map((d) => `${d.where} -> ${d.apis.join('+')}`).join(' | ')}`);
      }
    }
    expect(problems).toEqual([]);
  });

  it('never reshapes a response inside a queryFn (use `select` for a per-screen view)', () => {
    const reshaping = /\.(then|map|filter|reduce|flatMap|sort)\(/;
    const problems = defs
      .filter((d) => reshaping.test(d.fnText) && !(d.where in KNOWN_EXCEPTIONS))
      .map((d) => `${d.where} key=${d.keyShape}: ${d.fnText.replace(/\s+/g, ' ').slice(0, 120)}`);
    expect(problems).toEqual([]);
  });
});
