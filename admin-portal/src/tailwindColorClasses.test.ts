import { describe, it, expect } from 'vitest';
import { readdirSync, readFileSync, statSync } from 'node:fs';
import { join, dirname } from 'node:path';
import { fileURLToPath, pathToFileURL } from 'node:url';
import postcss from 'postcss';
import tailwindcss from 'tailwindcss';
import ts from 'typescript';

/**
 * Tailwind generates nothing at all for a class it cannot resolve -- no warning, no build error.
 * The held-statement Approve button shipped as `bg-accent ... text-white` when this app has never
 * had an `accent` colour, so it rendered as white text on a transparent background: invisible. The
 * same silence hid opacity modifiers on hex-valued tokens (`border-success/20`), which produced no
 * rule until tailwind.config.js taught those tokens to take an alpha value.
 *
 * So rather than keeping a list of valid class names, this asks Tailwind itself: classes are read
 * out of the source's string literals (with the TypeScript parser, so comments and prose are never
 * mistaken for classes), compiled against the real config and index.css, and any class that
 * produces no CSS rule fails.
 *
 * A defined colour can still be invisible: `text-white` on `bg-primary` is fine in light mode and
 * near-white on near-white in dark mode, where primary is light paper. The last check resolves
 * each text/background pair that sits on one element to the real token values in both themes.
 */
const SRC = dirname(fileURLToPath(import.meta.url));
const ROOT = join(SRC, '..');
const SELF = 'tailwindColorClasses.test.ts';

const COLOR_UTILITY =
  /^(?:[a-z0-9-]+:)*(?:bg|text|border(?:-[xytrblse])?|ring(?:-offset)?|divide|outline|decoration|fill|stroke|from|via|to|placeholder|caret|accent|shadow)-[a-z][a-z0-9-]*(?:\/\d+)?$/;

/** Below this, text is effectively invisible on its background (WCAG's floor for text is 4.5). */
const INVISIBLE_CONTRAST = 1.5;

interface Fragment {
  text: string;
  where: string;
  inClassName: boolean;
}

/** One complete class list an element can end up with. */
interface ClassString {
  text: string;
  where: string;
}

interface Parsed {
  fragments: Fragment[];
  classStrings: ClassString[];
}

function sourceFiles(dir: string): string[] {
  return readdirSync(dir).flatMap((entry) => {
    const full = join(dir, entry);
    if (statSync(full).isDirectory()) return sourceFiles(full);
    // Test files are skipped: they never render to an operator.
    return /\.tsx?$/.test(entry) && !/\.test\.tsx?$/.test(entry) && entry !== SELF ? [full] : [];
  });
}

const EQUALITY = new Set([
  ts.SyntaxKind.EqualsEqualsEqualsToken, ts.SyntaxKind.ExclamationEqualsEqualsToken,
  ts.SyntaxKind.EqualsEqualsToken, ts.SyntaxKind.ExclamationEqualsToken,
]);

/**
 * A literal that can never be class text: compared against (`toast.type === 'success'`), a type
 * (`Row['status']`), a key (`tone['OPEN']`, `{ 'OPEN': ... }`) or a module path.
 */
function isNeverClassText(node: ts.Node): boolean {
  const parent = node.parent;
  return (
    (ts.isBinaryExpression(parent) && EQUALITY.has(parent.operatorToken.kind)) ||
    ts.isCaseClause(parent) ||
    ts.isLiteralTypeNode(parent) ||
    (ts.isElementAccessExpression(parent) && parent.argumentExpression === node) ||
    (ts.isPropertyAssignment(parent) && parent.name === node) ||
    ts.isImportDeclaration(parent) ||
    ts.isExportDeclaration(parent)
  );
}

const MAX_VARIANTS = 256;
const combine = (left: string[], right: string[], separator: string) =>
  left.flatMap((l) => right.map((r) => l + separator + r)).slice(0, MAX_VARIANTS);

/**
 * Every class list a className expression can produce: both arms of a conditional, with and
 * without an `&&` operand, each combination of a template's parts. Anything not built from
 * literals (a variable, a lookup) contributes nothing, as it cannot be read statically.
 */
function possibleClassStrings(node: ts.Node | undefined): string[] {
  if (!node) return [''];
  if (ts.isJsxExpression(node) || ts.isParenthesizedExpression(node)) return possibleClassStrings(node.expression);
  if (ts.isStringLiteral(node) || ts.isNoSubstitutionTemplateLiteral(node)) return [node.text];
  if (ts.isTemplateExpression(node)) {
    return node.templateSpans.reduce(
      (acc, span) => combine(combine(acc, possibleClassStrings(span.expression), ''), [span.literal.text], ''),
      [node.head.text]
    );
  }
  if (ts.isConditionalExpression(node)) {
    return [...possibleClassStrings(node.whenTrue), ...possibleClassStrings(node.whenFalse)];
  }
  if (ts.isBinaryExpression(node)) {
    const op = node.operatorToken.kind;
    if (op === ts.SyntaxKind.AmpersandAmpersandToken) return ['', ...possibleClassStrings(node.right)];
    if (op === ts.SyntaxKind.BarBarToken || op === ts.SyntaxKind.QuestionQuestionToken) {
      return [...possibleClassStrings(node.left), ...possibleClassStrings(node.right)];
    }
    if (op === ts.SyntaxKind.PlusToken) return combine(possibleClassStrings(node.left), possibleClassStrings(node.right), '');
  }
  if (ts.isCallExpression(node)) {
    return node.arguments.reduce((acc, arg) => combine(acc, possibleClassStrings(arg), ' '), ['']);
  }
  return [''];
}

function parse(fileName: string, source: string): Parsed {
  const kind = fileName.endsWith('.tsx') ? ts.ScriptKind.TSX : ts.ScriptKind.TS;
  const file = ts.createSourceFile(fileName, source, ts.ScriptTarget.Latest, true, kind);
  const where = (node: ts.Node) => `${fileName}:${file.getLineAndCharacterOfPosition(node.getStart(file)).line + 1}`;
  const parsed: Parsed = { fragments: [], classStrings: [] };
  const visit = (node: ts.Node, inClassName: boolean) => {
    if (ts.isJsxAttribute(node) && node.name.getText(file) === 'className') {
      inClassName = true;
      for (const text of possibleClassStrings(node.initializer)) parsed.classStrings.push({ text, where: where(node) });
    }
    if (
      (ts.isStringLiteral(node) || ts.isNoSubstitutionTemplateLiteral(node) || ts.isTemplateHead(node) ||
        ts.isTemplateMiddle(node) || ts.isTemplateTail(node)) &&
      !isNeverClassText(node)
    ) {
      parsed.fragments.push({ text: node.text, where: where(node), inClassName });
      // A class list kept outside JSX (a status-tone map, a shared button style) is whole as written.
      if (!inClassName) parsed.classStrings.push({ text: node.text, where: where(node) });
    }
    ts.forEachChild(node, (child) => visit(child, inClassName));
  };
  visit(file, false);
  return parsed;
}

function parseSource(): Parsed {
  const all: Parsed = { fragments: [], classStrings: [] };
  for (const file of sourceFiles(SRC)) {
    const parsed = parse(file.slice(SRC.length + 1), readFileSync(file, 'utf8'));
    all.fragments.push(...parsed.fragments);
    all.classStrings.push(...parsed.classStrings);
  }
  return all;
}

const tokensOf = (text: string) => text.split(/\s+/).filter(Boolean);

/** Every className token, plus anything colour-utility-shaped anywhere (status-tone maps etc.). */
function classCandidates(fragments: Fragment[]): Map<string, string> {
  const firstSeenAt = new Map<string, string>();
  for (const fragment of fragments) {
    for (const token of tokensOf(fragment.text)) {
      if ((fragment.inClassName || COLOR_UTILITY.test(token)) && !firstSeenAt.has(token)) {
        firstSeenAt.set(token, fragment.where);
      }
    }
  }
  return firstSeenAt;
}

async function loadConfig() {
  const configUrl = pathToFileURL(join(ROOT, 'tailwind.config.js')).href;
  return (await import(/* @vite-ignore */ configUrl)).default;
}

async function generatedClassNames(candidates: string[]): Promise<Set<string>> {
  const config = await loadConfig();
  const indexCss = join(SRC, 'index.css');
  const result = await postcss([
    tailwindcss({ ...config, content: [{ raw: candidates.join(' '), extension: 'html' }] }),
  ]).process(readFileSync(indexCss, 'utf8'), { from: indexCss });

  const names = new Set<string>();
  result.root.walkRules((rule) => {
    for (const match of rule.selector.matchAll(/\.((?:\\.|[\w-])+)/g)) {
      names.add(match[1].replace(/\\(.)/g, '$1'));
    }
  });
  return names;
}

// ---- contrast ----

type Rgb = [number, number, number];
type Theme = 'light' | 'dark';

function parseColor(value: string): Rgb | null {
  const hex = value.trim().match(/^#([0-9a-f]{3}|[0-9a-f]{6})$/i);
  if (hex) {
    const digits = hex[1].length === 3 ? [...hex[1]].map((d) => d + d).join('') : hex[1];
    return [0, 2, 4].map((i) => parseInt(digits.slice(i, i + 2), 16)) as Rgb;
  }
  const channels = value.trim().match(/^(\d+) (\d+) (\d+)$/);
  return channels ? (channels.slice(1).map(Number) as Rgb) : null;
}

function themeVariables(): Record<Theme, Map<string, Rgb>> {
  const themes = { light: new Map<string, Rgb>(), dark: new Map<string, Rgb>() };
  postcss.parse(readFileSync(join(SRC, 'index.css'), 'utf8')).walkRules((rule) => {
    const theme = rule.selector === ':root' ? 'light' : rule.selector === '.dark' ? 'dark' : null;
    if (!theme) return;
    rule.walkDecls(/^--color-/, (decl) => {
      const rgb = parseColor(decl.value);
      if (rgb) themes[theme].set(decl.prop, rgb);
    });
  });
  return themes;
}

/** Colour name (as in `bg-<name>`) to its value in each theme, read through the real config. */
async function colorPalette(): Promise<Map<string, Record<Theme, Rgb>>> {
  const colors: Record<string, unknown> = (await loadConfig()).theme.extend.colors;
  const variables = themeVariables();
  const palette = new Map<string, Record<Theme, Rgb>>([
    ['white', { light: [255, 255, 255], dark: [255, 255, 255] }],
    ['black', { light: [0, 0, 0], dark: [0, 0, 0] }],
  ]);
  for (const [name, value] of Object.entries(colors)) {
    const css = typeof value === 'function' ? String(value({})) : String(value);
    const variable = css.match(/--color-[a-z-]+/)?.[0];
    const light = variable && variables.light.get(variable);
    const dark = variable && variables.dark.get(variable);
    if (light && dark) palette.set(name, { light, dark });
  }
  return palette;
}

function luminance([r, g, b]: Rgb): number {
  const linear = (c: number) => (c / 255 <= 0.03928 ? c / 255 / 12.92 : ((c / 255 + 0.055) / 1.055) ** 2.4);
  return 0.2126 * linear(r) + 0.7152 * linear(g) + 0.0722 * linear(b);
}

function contrast(a: Rgb, b: Rgb): number {
  const [hi, lo] = [luminance(a), luminance(b)].sort((x, y) => y - x);
  return (hi + 0.05) / (lo + 0.05);
}

/**
 * Text and background colours one class list sets, keyed by variant chain ('' = always,
 * 'hover:' = on hover). A variant without its own text or background colour inherits the base one,
 * so `text-white hover:bg-primary` is checked as white on primary while hovered.
 */
function colourPairs(classStrings: ClassString[], palette: Map<string, unknown>) {
  const pairs: { text: string; bg: string; variant: string; where: string }[] = [];
  for (const { text: classes, where } of classStrings) {
    const text = new Map<string, string>();
    const bg = new Map<string, string>();
    for (const token of tokensOf(classes)) {
      const match = token.match(/^((?:[a-z0-9-]+:)*)(text|bg)-([a-z-]+)$/);
      // `dark:` classes would need per-theme handling; this app does not use them.
      if (!match || !palette.has(match[3]) || match[1].split(':').includes('dark')) continue;
      (match[2] === 'text' ? text : bg).set(match[1], match[3]);
    }
    for (const variant of new Set([...text.keys(), ...bg.keys()])) {
      const textColour = text.get(variant) ?? text.get('');
      const bgColour = bg.get(variant) ?? bg.get('');
      if (textColour && bgColour) pairs.push({ text: textColour, bg: bgColour, variant, where });
    }
  }
  return pairs;
}

async function invisiblePairs(classStrings: ClassString[]): Promise<string[]> {
  const palette = await colorPalette();
  const unique = new Set<string>();
  return colourPairs(classStrings, palette).flatMap(({ text, bg, variant, where }) =>
    (['light', 'dark'] as const)
      .map((theme) => ({ theme, ratio: contrast(palette.get(text)![theme], palette.get(bg)![theme]) }))
      .filter(({ ratio }) => ratio < INVISIBLE_CONTRAST)
      .map(({ theme, ratio }) => `${where}: ${variant}text-${text} on ${variant}bg-${bg} is ${ratio.toFixed(2)}:1 in ${theme} mode`)
      .filter((line) => !unique.has(line) && unique.add(line))
  );
}

describe('Tailwind colour classes', () => {
  it('reads classes from code only, not from comments or look-alike strings', () => {
    const { fragments } = parse('Example.tsx', [
      'const accept = "*/*"; // a dead bg-gone in a comment',
      '/* text-gone */',
      "const tone = { OPEN: 'text-accent', 'bg-key': 'x' } as Record<'text-type', string>;",
      'const accept2 = "*/*";',
      "export const A = (p: { kind: string }) => <b className={p.kind === 'bg-compared' ? 'bg-primary' : ''} />;",
    ].join('\n'));
    const candidates = classCandidates(fragments);

    expect(candidates.get('text-accent')).toBe('Example.tsx:3');
    expect(candidates.has('bg-primary')).toBe(true);
    expect(candidates.has('bg-gone')).toBe(false);
    expect(candidates.has('text-gone')).toBe(false);
    expect(candidates.has('bg-compared')).toBe(false);
    expect(candidates.has('bg-key')).toBe(false);
    expect(candidates.has('text-type')).toBe(false);
  });

  it('finds the classes it is meant to check in the real source', () => {
    const candidates = classCandidates(parseSource().fragments);
    expect(candidates.has('bg-primary')).toBe(true);
    expect(candidates.has('hover:bg-primary-dark')).toBe(true);
    expect(candidates.has('border-success/20')).toBe(true);
  });

  it('flags a class that produces no CSS', async () => {
    const generated = await generatedClassNames(['bg-accent', 'bg-primary', 'border-success/20', 'rounded-xl2']);
    expect(generated.has('bg-primary')).toBe(true);
    expect(generated.has('border-success/20')).toBe(true);
    expect(generated.has('rounded-xl2')).toBe(true);
    expect(generated.has('bg-accent')).toBe(false);
  });

  it('every class in the source compiles to a real CSS rule', async () => {
    const candidates = classCandidates(parseSource().fragments);
    const generated = await generatedClassNames([...candidates.keys()]);
    const dead = [...candidates]
      .filter(([cls]) => !generated.has(cls))
      .map(([cls, where]) => `${cls} (${where})`);

    expect(dead).toEqual([]);
  });

  it('flags text that disappears into its background in either theme', async () => {
    const offenders = await invisiblePairs(parse('Example.tsx', [
      "const a = <b className={`text-xs text-white ${danger ? 'bg-danger' : 'bg-primary'}`} />;",
      "const b = <b className={`text-xs ${danger ? 'bg-danger text-white' : 'bg-primary text-on-primary'}`} />;",
      'const c = <b className="bg-border hover:bg-primary-dark hover:text-white" />;',
      "const d = <b className={`px-3 ${on ? 'bg-primary text-on-primary' : 'text-muted hover:text-ink'}`} />;",
      "const e = <b className={`bg-card ${ok && 'text-primary hover:bg-white'}`} />;",
      "const f = { OPEN: 'bg-ink text-white' };",
    ].join('\n')).classStrings);

    // White on primary/ink and on primary-dark measured by hand from index.css's dark values:
    // #F4F1EC gives 1.13:1, #DAD5C9 1.46:1, rgb(237 237 234) 1.17:1.
    expect(offenders).toEqual([
      'Example.tsx:1: text-white on bg-primary is 1.13:1 in dark mode',
      'Example.tsx:3: hover:text-white on hover:bg-primary-dark is 1.46:1 in dark mode',
      'Example.tsx:5: hover:text-primary on hover:bg-white is 1.13:1 in dark mode',
      'Example.tsx:6: text-white on bg-ink is 1.17:1 in dark mode',
    ]);
  });

  it('no text in the source disappears into its own background in either theme', async () => {
    expect(await invisiblePairs(parseSource().classStrings)).toEqual([]);
  });
});
