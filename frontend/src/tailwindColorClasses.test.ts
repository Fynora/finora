import { describe, it, expect } from 'vitest';
import { readdirSync, readFileSync, statSync } from 'node:fs';
import { join, dirname } from 'node:path';
import { fileURLToPath, pathToFileURL } from 'node:url';
import postcss from 'postcss';
import tailwindcss from 'tailwindcss';
import ts from 'typescript';

/**
 * Tailwind generates nothing at all for a class it cannot resolve -- no warning, no build error.
 * Insights' Fyn narration shipped as `border-accent bg-accent/5 ... text-accent` when this app has
 * never had a plain `accent` colour, so it rendered with no border, fill or label colour; TopBar's
 * notification badge asked for `w-4.5 h-4.5`, a step Tailwind's spacing scale does not have. The
 * same silence hid every opacity modifier on a hex-valued token (`border-warning/30`,
 * `hover:bg-danger/90`), which produced no rule until tailwind.config.js taught those tokens to take
 * an alpha value. (The admin portal has its own copy of this guard, after an invisible Approve
 * button there.)
 *
 * So rather than keeping a list of valid class names, this asks Tailwind itself: classes are read
 * out of the source's string literals (with the TypeScript parser, so comments are never mistaken
 * for classes), compiled against the real config and index.css, and any class that produces no CSS
 * rule fails. Two kinds of literal are never class text: CSS inside a `style` attribute
 * (`transition: stroke-dasharray ...`) and prose (Help's "digital, text-based statements").
 *
 * A defined colour can still be invisible: `text-white` on `bg-primary` is fine in light mode and
 * near-white on near-white in dark mode, where primary is light paper. The last check resolves
 * each text/background pair that sits on one element to the real token values in both themes and
 * holds it to WCAG AA (4.5:1) -- which is also how the -600 status colours (2.86-3.95:1 as badge
 * text on their washes) and white on dark mode's light danger/success fills (2.28-2.77:1) were
 * found.
 */
const SRC = dirname(fileURLToPath(import.meta.url));
const ROOT = join(SRC, '..');
const SELF = 'tailwindColorClasses.test.ts';

const COLOR_UTILITY =
  /^(?:[a-z0-9-]+:)*(?:bg|text|border(?:-[xytrblse])?|ring(?:-offset)?|divide|outline|decoration|fill|stroke|from|via|to|placeholder|caret|accent|shadow)-[a-z][a-z0-9-]*(?:\/\d+)?$/;

/** WCAG AA for normal text. Every pair in this app clears it in both themes. */
const MIN_TEXT_CONTRAST = 4.5;
/** WCAG's minimum for an icon or other graphic that carries meaning (1.4.11, non-text contrast). */
const MIN_GRAPHIC_CONTRAST = 3;

interface Fragment {
  text: string;
  where: string;
  inClassName: boolean;
}

/** One complete class list an element can end up with. */
interface ClassString {
  text: string;
  where: string;
  /** Lowest contrast its text colour may have: MIN_GRAPHIC_CONTRAST on an icon. */
  minimum?: number;
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

/**
 * A capitalised word or one ending in punctuation: prose (an FAQ answer, a toast), never a class
 * list. Only applied outside className, where a literal is otherwise checked just for looking like
 * a colour utility -- "text-based" in a sentence would read as `text-based`.
 */
const PROSE_WORD = /^[A-Z]|[.,;:?!]$/;
const isProse = (text: string) => tokensOf(text).some((word) => PROSE_WORD.test(word));

function parse(fileName: string, source: string): Parsed {
  const kind = fileName.endsWith('.tsx') ? ts.ScriptKind.TSX : ts.ScriptKind.TS;
  const file = ts.createSourceFile(fileName, source, ts.ScriptTarget.Latest, true, kind);
  const where = (node: ts.Node) => `${fileName}:${file.getLineAndCharacterOfPosition(node.getStart(file)).line + 1}`;
  const parsed: Parsed = { fragments: [], classStrings: [] };
  const visit = (node: ts.Node, inClassName: boolean) => {
    // Inline styles are CSS (`transition: stroke-dasharray 1s`), not class names.
    if (ts.isJsxAttribute(node) && node.name.getText(file) === 'style') return;
    if (ts.isJsxAttribute(node) && node.name.getText(file) === 'className') inClassName = true;
    if (
      (ts.isStringLiteral(node) || ts.isNoSubstitutionTemplateLiteral(node) || ts.isTemplateHead(node) ||
        ts.isTemplateMiddle(node) || ts.isTemplateTail(node)) &&
      !isNeverClassText(node) &&
      (inClassName || !isProse(node.text))
    ) {
      parsed.fragments.push({ text: node.text, where: where(node), inClassName });
      // A class list kept outside JSX (a status-tone map, a shared button style) is whole as written.
      if (!inClassName) parsed.classStrings.push({ text: node.text, where: where(node) });
    }
    ts.forEachChild(node, (child) => visit(child, inClassName));
  };
  visit(file, false);
  parsed.classStrings.push(...elementClassStrings(file, where));
  return parsed;
}

/** A background set at runtime (an inline style built from a variable): nothing on it can be checked. */
const UNKNOWN = '?';

/**
 * An inline style's colours as class-shaped tokens (`text-[#d97706]`, `bg-[var(--color-card)]`),
 * so they are checked exactly like classes. A background that is not a literal -- a variable, a
 * gradient, a spread -- is `bg-?`, and text on it is not judged.
 */
function styleClasses(attribute: ts.JsxAttribute | undefined, file: ts.SourceFile): string {
  const initializer = attribute?.initializer;
  if (!initializer) return '';
  const object = initializer && ts.isJsxExpression(initializer) ? initializer.expression : undefined;
  if (!object || !ts.isObjectLiteralExpression(object)) return `bg-${UNKNOWN}`;
  const tokens: string[] = [];
  for (const property of object.properties) {
    if (!ts.isPropertyAssignment(property)) {
      tokens.push(`bg-${UNKNOWN}`, `text-${UNKNOWN}`);
      continue;
    }
    const name = property.name.getText(file).replace(/['"]/g, '');
    const kind = name === 'color' ? 'text' : name === 'background' || name === 'backgroundColor' ? 'bg' : null;
    if (!kind) continue;
    const value = ts.isStringLiteral(property.initializer) || ts.isNoSubstitutionTemplateLiteral(property.initializer)
      ? property.initializer.text.trim()
      : null;
    if (value === 'none' || value === 'transparent') continue;
    tokens.push(value && /^(#[0-9a-f]{3}|#[0-9a-f]{6}|var\(--color-[a-z0-9-]+\))$/i.test(value)
      ? `${kind}-[${value}]`
      : `${kind}-${UNKNOWN}`);
  }
  return tokens.join(' ');
}

/** The part of a class list a child sits on: its unconditional backgrounds, from the last opaque one. */
function backdropOf(classes: string): string {
  const backgrounds = tokensOf(classes).filter((token) => /^bg-/.test(token));
  let start = 0;
  backgrounds.forEach((token, i) => {
    if (!token.includes('/')) start = i;
  });
  return backgrounds.slice(start).join(' ');
}

/**
 * Every class list a JSX element can end up with, each prefixed with every background its JSX
 * ancestors in the same file can give it -- so `<div className="bg-ink"><Check className="text-success"
 * /></div>` is checked as success on ink, and `bg-danger/10` is composited over whatever is behind
 * it. An inline style counts as classes (see styleClasses).
 */
function elementClassStrings(file: ts.SourceFile, where: (node: ts.Node) => string): ClassString[] {
  const out: ClassString[] = [];
  const icons = new Set(file.statements.flatMap((statement) =>
    ts.isImportDeclaration(statement) && (statement.moduleSpecifier as ts.StringLiteral).text === 'lucide-react'
      ? (statement.importClause?.namedBindings as ts.NamedImports | undefined)?.elements.map((e) => e.name.text) ?? []
      : []));
  // An icon: an svg, a lucide import, or a component held in a variable named for one (`<Icon />`,
  // `<item.icon />`). It is a graphic, so 3:1 applies to it rather than text's 4.5:1.
  const isIcon = (tag: string) => tag === 'svg' || icons.has(tag) || /(?:^|\.)icon$|Icon$/i.test(tag);
  const walk = (node: ts.Node, backdrops: string[]) => {
    if (!ts.isJsxElement(node) && !ts.isJsxSelfClosingElement(node)) {
      ts.forEachChild(node, (child) => walk(child, backdrops));
      return;
    }
    const opening = ts.isJsxElement(node) ? node.openingElement : node;
    const attribute = (name: string) => opening.attributes.properties.find(
      (a): a is ts.JsxAttribute => ts.isJsxAttribute(a) && a.name.getText(file) === name);
    // Hidden from assistive technology means decorative (a separator dot, an illustration), and
    // decorative content has no contrast requirement -- nor does anything inside it.
    const hidden = attribute('aria-hidden');
    if (hidden && (!hidden.initializer || /^["'{]*true["'}]*$/.test(hidden.initializer.getText(file)))) return;
    const minimum = isIcon(opening.tagName.getText(file)) ? MIN_GRAPHIC_CONTRAST : undefined;
    const className = attribute('className');
    const own = className ? possibleClassStrings(className.initializer) : [''];
    if (own.length >= MAX_VARIANTS) throw new Error(`${where(className!)}: too many class combinations to check`);
    const style = styleClasses(attribute('style'), file);
    const next = new Set<string>();
    for (const backdrop of backdrops) {
      for (const classes of own) {
        const full = [backdrop, style, classes].filter(Boolean).join(' ');
        if (className || style) out.push({ text: full, where: where(className ?? opening), minimum });
        next.add(backdropOf(full));
      }
    }
    opening.attributes.properties.forEach((a) => walk(a, backdrops));
    // Inside role="img" (Landing's product mock) everything is one picture, described by its
    // label; ARIA makes the children presentational, so their text is part of an image.
    const image = attribute('role')?.initializer?.getText(file) === '"img"';
    if (ts.isJsxElement(node) && !image) node.children.forEach((child) => walk(child, [...next]));
  };
  walk(file, ['']);
  return out;
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

/** Every className token, plus anything colour-utility-shaped anywhere (status-tone maps etc.),
 *  with every place it is used. */
function classCandidates(fragments: Fragment[]): Map<string, string[]> {
  const usedAt = new Map<string, string[]>();
  for (const fragment of fragments) {
    for (const token of tokensOf(fragment.text)) {
      if (fragment.inClassName || COLOR_UTILITY.test(token)) {
        usedAt.set(token, [...new Set([...(usedAt.get(token) ?? []), fragment.where])]);
      }
    }
  }
  return usedAt;
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
    for (const match of rule.selector.matchAll(/\.((?:\\[0-9a-f]{1,6} ?|\\.|[\w-])+)/gi)) {
      // CSS escapes: `\:` for most characters, but `\2c ` (hex, optional space) for a comma.
      names.add(match[1].replace(/\\([0-9a-f]{1,6}) ?|\\(.)/gi, (_, hex, char) =>
        hex ? String.fromCodePoint(parseInt(hex, 16)) : char));
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
    // A fixed token (`--color-sidebar`, `--color-premium-fixed`) is only set on :root, so it
    // keeps that value in dark mode too.
    const dark = (variable && variables.dark.get(variable)) || light;
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

interface Layer {
  colour: Record<Theme, Rgb> | null;
  alpha: number;
  label: string;
}

const COLOUR_CLASS = /^((?:[a-z0-9-]+:)*)(text|bg)-(\[[^\]]+\]|\?|[a-z][a-z0-9-]*?)(?:\/(\d+|\[\d+%\]))?$/;

/** A colour class's value in each theme; null for one set at runtime, undefined for a non-colour. */
function resolveColour(name: string, palette: Map<string, Record<Theme, Rgb>>, variables: Record<Theme, Map<string, Rgb>>) {
  if (name === UNKNOWN) return null;
  const arbitrary = name.match(/^\[(.*)\]$/)?.[1];
  if (arbitrary === undefined) return palette.get(name);
  const hex = parseColor(arbitrary);
  if (hex) return { light: hex, dark: hex };
  const variable = arbitrary.match(/^var\((--color-[a-z0-9-]+)\)$/)?.[1];
  const light = variable && variables.light.get(variable);
  return light ? { light, dark: variables.dark.get(variable!) ?? light } : undefined;
}

/**
 * Text and background colours one class list sets, keyed by variant chain ('' = always,
 * 'hover:' = on hover). A variant without its own text or background colour inherits the base one,
 * so `text-white hover:bg-primary` is checked as white on primary while hovered. Backgrounds stack:
 * a translucent one (`bg-danger/10`, `hover:bg-black/5`) is composited over the one under it.
 */
function colourPairs(classStrings: ClassString[], palette: Map<string, Record<Theme, Rgb>>, variables: Record<Theme, Map<string, Rgb>>) {
  const pairs: { text: Layer; layers: Layer[]; variant: string; where: string; minimum: number }[] = [];
  for (const { text: classes, where, minimum = MIN_TEXT_CONTRAST } of classStrings) {
    const text = new Map<string, Layer>();
    const bg = new Map<string, Layer[]>();
    for (const token of tokensOf(classes)) {
      const match = token.match(COLOUR_CLASS);
      // `dark:` classes would need per-theme handling; this app does not use them.
      if (!match || match[1].split(':').includes('dark')) continue;
      const colour = resolveColour(match[3], palette, variables);
      if (colour === undefined) continue;
      const modifier = match[4]?.match(/\d+/)?.[0];
      const layer = { colour, alpha: modifier === undefined ? 1 : Number(modifier) / 100, label: token };
      if (match[2] === 'text') text.set(match[1], layer);
      else bg.set(match[1], layer.alpha === 1 ? [layer] : [...(bg.get(match[1]) ?? []), layer]);
    }
    for (const variant of new Set([...text.keys(), ...bg.keys()])) {
      const own = bg.get(variant) ?? [];
      const base = variant === '' ? [] : bg.get('') ?? [];
      const layers = own[0]?.alpha === 1 ? own : [...base, ...own];
      const textLayer = text.get(variant) ?? text.get('');
      if (textLayer && layers.length) pairs.push({ text: textLayer, layers, variant, where, minimum });
    }
  }
  return pairs;
}

const over = (top: Rgb, alpha: number, under: Rgb) => top.map((c, i) => c * alpha + under[i] * (1 - alpha)) as Rgb;

/**
 * The contrast of one pair in one theme. A stack that starts translucent sits on whatever page
 * surface is behind it, so it is measured over both card and bg and the worse one counts. Null when
 * a colour is only known at runtime.
 */
function pairContrast(text: Layer, layers: Layer[], theme: Theme, palette: Map<string, Record<Theme, Rgb>>) {
  if (text.colour === null || layers.some((layer) => layer.colour === null)) return null;
  // A translucent white or black tint (`bg-white/10`) is drawn for one particular surface, light or
  // dark; with that surface unknown here, there is nothing to measure it against.
  if (layers[0].alpha < 1 && layers.every((layer) => /-(white|black)\//.test(layer.label))) return null;
  const backdrops = layers[0].alpha === 1 ? [layers[0].colour!] : [palette.get('card')!, palette.get('bg')!];
  return Math.min(...backdrops.map((backdrop) => {
    const surface = layers.reduce((under, layer) => over(layer.colour![theme], layer.alpha, under), backdrop[theme]);
    return contrast(over(text.colour![theme], text.alpha, surface), surface);
  }));
}

async function lowContrastPairs(classStrings: ClassString[]): Promise<string[]> {
  const palette = await colorPalette();
  const variables = themeVariables();
  const unique = new Set<string>();
  const strip = (label: string) => label.replace(/^(?:[a-z0-9-]+:)*/, '');
  return colourPairs(classStrings, palette, variables).flatMap(({ text, layers, variant, where, minimum }) =>
    (['light', 'dark'] as const)
      .map((theme) => ({ theme, ratio: pairContrast(text, layers, theme, palette) }))
      .filter((r): r is { theme: Theme; ratio: number } => r.ratio !== null && r.ratio < minimum)
      .map(({ theme, ratio }) => {
        const background = layers.length === 1 && layers[0].label.startsWith(variant)
          ? `${variant}${strip(layers[0].label)}`
          : layers.map((layer) => layer.label).join(' > ');
        return `${where}: ${variant}${strip(text.label)} on ${background} is ${ratio.toFixed(2)}:1 in ${theme} mode`;
      })
      .filter((line) => !unique.has(line) && unique.add(line))
  ).sort((a, b) => a.localeCompare(b, 'en', { numeric: true }));
}

describe('Tailwind colour classes', () => {
  it('reads classes from code only, not from comments or look-alike strings', () => {
    const { fragments } = parse('Example.tsx', [
      'const accept = "*/*"; // a dead bg-gone in a comment',
      '/* text-gone */',
      "const tone = { OPEN: 'text-accent', 'bg-key': 'x' } as Record<'text-type', string>;",
      'const accept2 = "*/*";',
      "export const A = (p: { kind: string }) => <b className={p.kind === 'bg-compared' ? 'bg-primary' : ''} />;",
      'export const B = () => <svg style={{ transition: `stroke-dasharray ${ms}ms ease` }} />;',
      "const faq = { answer: 'PDF support covers digital, text-based statements.' };",
      "export const C = () => <p className={`text-ink ${'bg-card'}`}>Text-only, honest.</p>;",
    ].join('\n'));
    const candidates = classCandidates(fragments);

    expect(candidates.get('text-accent')).toEqual(['Example.tsx:3']);
    expect(candidates.has('bg-primary')).toBe(true);
    expect(candidates.has('bg-gone')).toBe(false);
    expect(candidates.has('text-gone')).toBe(false);
    expect(candidates.has('bg-compared')).toBe(false);
    expect(candidates.has('bg-key')).toBe(false);
    expect(candidates.has('text-type')).toBe(false);
    expect(candidates.has('stroke-dasharray')).toBe(false);
    expect(candidates.has('text-based')).toBe(false);
    expect(candidates.get('text-ink')).toEqual(['Example.tsx:8']);
    expect(candidates.has('bg-card')).toBe(true);
  });

  it('finds the classes it is meant to check in the real source', () => {
    const candidates = classCandidates(parseSource().fragments);
    expect(candidates.has('bg-primary')).toBe(true);
    expect(candidates.has('hover:bg-primary-dark')).toBe(true);
    expect(candidates.has('border-warning/30')).toBe(true);
    expect(candidates.has('hover:bg-premium/90')).toBe(true);
  });

  it('flags a class that produces no CSS', async () => {
    const generated = await generatedClassNames([
      'bg-accent', 'bg-primary', 'border-success/20', 'rounded-xl2', 'transition-[transform,box-shadow]', 'hover:bg-bg',
      'w-4.5', 'h-[18px]',
    ]);
    expect(generated.has('bg-primary')).toBe(true);
    expect(generated.has('transition-[transform,box-shadow]')).toBe(true);
    expect(generated.has('hover:bg-bg')).toBe(true);
    expect(generated.has('border-success/20')).toBe(true);
    expect(generated.has('rounded-xl2')).toBe(true);
    expect(generated.has('bg-accent')).toBe(false);
    expect(generated.has('h-[18px]')).toBe(true);
    expect(generated.has('w-4.5')).toBe(false);
  });

  it('keeps unmodified colours exactly as before and mixes modified ones with transparent', async () => {
    const config = await loadConfig();
    const result = await postcss([
      tailwindcss({ ...config, content: [{ raw: 'bg-warning border-warning/30 text-premium/[15%] bg-primary/10', extension: 'html' }] }),
    ]).process('@tailwind utilities;', { from: undefined });
    const declarations = new Map<string, string>();
    result.root.walkDecls(/color$/, (decl) => {
      declarations.set((decl.parent as postcss.Rule).selector, decl.value);
    });

    expect(declarations.get('.bg-warning')).toBe('var(--color-warning)');
    expect(declarations.get('.border-warning\\/30')).toBe(
      'color-mix(in srgb, var(--color-warning) calc(0.3 * 100%), transparent)'
    );
    expect(declarations.get('.text-premium\\/\\[15\\%\\]')).toBe(
      'color-mix(in srgb, var(--color-premium) 15%, transparent)'
    );
    // Channel-valued, so Tailwind's own alpha slot still handles it.
    expect(declarations.get('.bg-primary\\/10')).toBe('rgb(var(--color-primary) / 0.1)');
  });

  it('every class in the source compiles to a real CSS rule', async () => {
    const candidates = classCandidates(parseSource().fragments);
    const generated = await generatedClassNames([...candidates.keys()]);
    const dead = [...candidates]
      .filter(([cls]) => !generated.has(cls))
      .map(([cls, where]) => `${cls} (${where.join(', ')})`);

    expect(dead).toEqual([]);
  });

  it('flags text below WCAG AA contrast on its own background in either theme', async () => {
    const offenders = await lowContrastPairs(parse('Example.tsx', [
      "const a = <b className={`text-xs text-white ${danger ? 'bg-danger' : 'bg-primary'}`} />;",
      "const b = <b className={`text-xs ${danger ? 'bg-danger text-white' : 'bg-primary text-on-primary'}`} />;",
      'const c = <b className="bg-border hover:bg-primary-dark hover:text-white" />;',
      "const d = <b className={`px-3 ${on ? 'bg-primary text-on-primary' : 'text-muted hover:text-ink'}`} />;",
      "const e = <b className={`bg-card ${ok && 'text-primary hover:bg-white'}`} />;",
      "const f = { OPEN: 'bg-ink text-white' };",
    ].join('\n')).classStrings);

    // Measured by hand from index.css's dark values: white on #f87171 (danger) gives 2.77:1, on
    // #F4F1EC (primary) 1.13:1, on #DAD5C9 1.46:1, on rgb(237 237 234) (ink) 1.17:1.
    expect(offenders).toEqual([
      'Example.tsx:1: text-white on bg-danger is 2.77:1 in dark mode',
      'Example.tsx:1: text-white on bg-primary is 1.13:1 in dark mode',
      'Example.tsx:2: text-white on bg-danger is 2.77:1 in dark mode',
      'Example.tsx:3: hover:text-white on hover:bg-primary-dark is 1.46:1 in dark mode',
      'Example.tsx:5: hover:text-primary on hover:bg-white is 1.13:1 in dark mode',
      'Example.tsx:6: text-white on bg-ink is 1.17:1 in dark mode',
    ]);
  });

  it('holds labels on a filled danger or success surface to WCAG AA in both themes', async () => {
    const offenders = await lowContrastPairs(parse('Example.tsx', [
      'const a = <b className="bg-danger text-white" />;',
      'const b = <b className="bg-success text-white" />;',
      'const c = <b className="bg-border hover:bg-danger hover:text-white" />;',
      'const d = <b className="bg-danger text-on-danger" />;',
      'const e = <b className="bg-success text-on-success" />;',
      'const f = <b className="bg-border hover:bg-danger hover:text-on-danger" />;',
      "const g = { FAILED: 'bg-danger-bg text-danger', DONE: 'bg-success-bg text-success' };",
      'const h = <b className="bg-accent-blue-bg text-accent-blue" />;',
      'const i = <b className="bg-surface text-success" />;',
    ].join('\n')).classStrings);

    // Measured by hand from index.css. White clears light mode's fills (#b91c1c 6.47:1, #147b3a
    // 5.35:1) but not dark mode's light ones (#f87171 2.77:1, #22c55e 2.28:1); the on-* tokens
    // clear both themes, lowest 5.35:1. Status text clears AA on its own wash (lowest is warning,
    // 4.80:1) and on surface (4.61:1). Accent text on its wash (light accent-blue, 4.24:1) does not.
    expect(offenders).toEqual([
      'Example.tsx:1: text-white on bg-danger is 2.77:1 in dark mode',
      'Example.tsx:2: text-white on bg-success is 2.28:1 in dark mode',
      'Example.tsx:3: hover:text-white on hover:bg-danger is 2.77:1 in dark mode',
      'Example.tsx:8: text-accent-blue on bg-accent-blue-bg is 4.24:1 in light mode',
    ]);
  });

  it('follows backgrounds through parents, opacity, arbitrary values and inline styles', async () => {
    const offenders = await lowContrastPairs(parse('Example.tsx', [
      "import { Check, Minus, Shield } from 'lucide-react';",
      'const a = <div className="bg-ink"><Check className="text-success" /></div>;',
      'const b = <div className="bg-success-bg"><div className="bg-white/60"><Shield className="text-success" /></div></div>;',
      'const c = <div className="bg-success-bg"><div className="bg-card/60"><Shield className="text-success" /></div></div>;',
      "const d = <div className=\"bg-card\"><p style={{ color: '#d97706' }}>3 matches</p></div>;",
      'const e = <div className="bg-card"><Minus className="text-border" /><Check className="text-success" /></div>;',
      'const f = <div className="bg-sidebar"><b className="text-warning">Plan</b></div>;',
      'const g = <div aria-hidden="true"><span className="bg-card text-border">·</span></div>;',
      'const h = <div style={{ background: colour }}><span className="text-white">AB</span></div>;',
      'const i = <b className="bg-white/15 text-white" />;',
      'const j = <b className="bg-[#2E2D2A] text-white" />;',
      'const k = <div className="bg-card"><b className="text-ink/50">signed</b></div>;',
      'const l = <div role="img" aria-label="Product mock" className="bg-card"><b className="text-border">₹480</b></div>;',
    ].join('\n')).classStrings);

    // Measured by hand from index.css (light / dark):
    // a: an icon, so 3:1 -- success on ink is 3.41 / 1.94 (ink is near-white in dark mode).
    // b: 60% white over the dark success wash is near-white, and dark success on it is 1.15.
    // c: the same chip in card instead stays dark, and passes.
    // d: an inline #d97706 on card is 3.19 in light mode.
    // e: an icon in the border colour is 1.30 / 1.55, under 3:1; success on card passes.
    // f: the sidebar is fixed-dark in both themes, and light warning (#ad5008) on it is 3.25.
    // g-i, l: decorative, set at runtime, a white tint on an unknown surface, or part of a picture
    // -- none are judged.
    // j: white on #2E2D2A passes; k: ink at 50% on card is 3.45 / 4.23.
    expect(offenders).toEqual([
      'Example.tsx:2: text-success on bg-ink is 1.94:1 in dark mode',
      'Example.tsx:3: text-success on bg-success-bg > bg-white/60 is 1.15:1 in dark mode',
      'Example.tsx:5: text-[#d97706] on bg-card is 3.19:1 in light mode',
      'Example.tsx:6: text-border on bg-card is 1.30:1 in light mode',
      'Example.tsx:6: text-border on bg-card is 1.55:1 in dark mode',
      'Example.tsx:7: text-warning on bg-sidebar is 3.25:1 in light mode',
      'Example.tsx:12: text-ink/50 on bg-card is 3.45:1 in light mode',
      'Example.tsx:12: text-ink/50 on bg-card is 4.23:1 in dark mode',
    ]);
  });

  it('all text in the source meets WCAG AA contrast on its own background in both themes', async () => {
    expect(await lowContrastPairs(parseSource().classStrings)).toEqual([]);
  });
});
