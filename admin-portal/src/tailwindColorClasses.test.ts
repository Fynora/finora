import { describe, it, expect } from 'vitest';
import { readdirSync, readFileSync, statSync } from 'node:fs';
import { join, dirname } from 'node:path';
import { fileURLToPath, pathToFileURL } from 'node:url';
import postcss from 'postcss';
import tailwindcss from 'tailwindcss';
import defaultColours from 'tailwindcss/colors';
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
 * each text colour, on whatever background its element and its JSX parents in the same file give it
 * (translucent ones composited), to the real token values in both themes and holds it to WCAG AA
 * (4.5:1; 3:1 for an icon) -- which is also how the -600 status colours (2.86-3.95:1 as badge
 * text) and white on dark mode's light danger/success fills (2.28-2.77:1) were found.
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

/** One class list an expression can produce, with the outcome of each condition it took. */
interface Possible {
  text: string;
  when: Record<string, boolean>;
}

const MAX_VARIANTS = 256;
const NOTHING: Possible = { text: '', when: {} };

/**
 * Two choices made together -- or none, when they need opposite outcomes of the same condition:
 * a parent's `critical ? 'bg-danger-bg' : 'bg-warning-bg'` and a child's `critical ? 'text-danger'
 * : 'text-warning'` never render danger's wash under warning's text. (Conditions match by their
 * source text, so two different variables of the same name in one file would be treated as one.)
 */
function joined(left: Possible, right: Possible, separator: string): Possible | null {
  for (const [condition, value] of Object.entries(right.when)) {
    if (condition in left.when && left.when[condition] !== value) return null;
  }
  return { text: left.text + separator + right.text, when: { ...left.when, ...right.when } };
}
const combine = (left: Possible[], right: Possible[], separator: string) =>
  left.flatMap((l) => right.map((r) => joined(l, r, separator)))
    .filter((p): p is Possible => p !== null)
    .slice(0, MAX_VARIANTS);

function assuming(possibles: Possible[], condition: ts.Node, value: boolean): Possible[] {
  const key = condition.getText().replace(/\s+/g, ' ');
  return combine([{ text: '', when: { [key]: value } }], possibles, '');
}

/**
 * Every class list a className expression can produce: both arms of a conditional, with and
 * without an `&&` operand, each combination of a template's parts. Anything not built from
 * literals (a variable, a lookup) contributes nothing, as it cannot be read statically.
 */
function possibleClassStrings(node: ts.Node | undefined): Possible[] {
  if (!node) return [NOTHING];
  if (ts.isJsxExpression(node) || ts.isParenthesizedExpression(node)) return possibleClassStrings(node.expression);
  if (ts.isStringLiteral(node) || ts.isNoSubstitutionTemplateLiteral(node)) return [{ text: node.text, when: {} }];
  if (ts.isTemplateExpression(node)) {
    return node.templateSpans.reduce(
      (acc, span) => combine(combine(acc, possibleClassStrings(span.expression), ''), [{ text: span.literal.text, when: {} }], ''),
      [{ text: node.head.text, when: {} }]
    );
  }
  if (ts.isConditionalExpression(node)) {
    return [
      ...assuming(possibleClassStrings(node.whenTrue), node.condition, true),
      ...assuming(possibleClassStrings(node.whenFalse), node.condition, false),
    ];
  }
  if (ts.isBinaryExpression(node)) {
    const op = node.operatorToken.kind;
    if (op === ts.SyntaxKind.AmpersandAmpersandToken) {
      return [...assuming([NOTHING], node.left, false), ...assuming(possibleClassStrings(node.right), node.left, true)];
    }
    if (op === ts.SyntaxKind.BarBarToken || op === ts.SyntaxKind.QuestionQuestionToken) {
      return [...possibleClassStrings(node.left), ...possibleClassStrings(node.right)];
    }
    if (op === ts.SyntaxKind.PlusToken) return combine(possibleClassStrings(node.left), possibleClassStrings(node.right), '');
  }
  if (ts.isCallExpression(node)) {
    return node.arguments.reduce((acc, arg) => combine(acc, possibleClassStrings(arg), ' '), [NOTHING]);
  }
  return [NOTHING];
}

function parse(fileName: string, source: string): Parsed {
  const kind = fileName.endsWith('.tsx') ? ts.ScriptKind.TSX : ts.ScriptKind.TS;
  const file = ts.createSourceFile(fileName, source, ts.ScriptTarget.Latest, true, kind);
  const where = (node: ts.Node) => `${fileName}:${file.getLineAndCharacterOfPosition(node.getStart(file)).line + 1}`;
  const parsed: Parsed = { fragments: [], classStrings: [] };
  const visit = (node: ts.Node, inClassName: boolean) => {
    if (ts.isJsxAttribute(node) && node.name.getText(file) === 'className') inClassName = true;
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
  const walk = (node: ts.Node, backdrops: Possible[]) => {
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
    // A control that is always disabled is inactive, which WCAG exempts from contrast too.
    const disabled = attribute('disabled');
    if (disabled && (!disabled.initializer || disabled.initializer.getText(file) === '{true}')) return;
    const minimum = isIcon(opening.tagName.getText(file)) ? MIN_GRAPHIC_CONTRAST : undefined;
    const className = attribute('className');
    const own = className ? possibleClassStrings(className.initializer) : [NOTHING];
    if (own.length >= MAX_VARIANTS) throw new Error(`${where(className!)}: too many class combinations to check`);
    const style = styleClasses(attribute('style'), file);
    const next = new Map<string, Possible>();
    for (const backdrop of backdrops) {
      for (const classes of own) {
        const full = joined(backdrop, { text: [style, classes.text].filter(Boolean).join(' '), when: classes.when }, ' ');
        if (!full) continue;
        if (className || style) out.push({ text: full.text, where: where(className ?? opening), minimum });
        const under = { text: backdropOf(full.text), when: full.when };
        next.set(JSON.stringify(under), under);
      }
    }
    opening.attributes.properties.forEach((a) => walk(a, backdrops));
    // Inside role="img" (Landing's product mock) everything is one picture, described by its
    // label; ARIA makes the children presentational, so their text is part of an image.
    const image = attribute('role')?.initializer?.getText(file) === '"img"';
    if (ts.isJsxElement(node) && !image) node.children.forEach((child) => walk(child, [...next.values()]));
  };
  walk(file, [NOTHING]);
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
  if (arbitrary === undefined) {
    // A raw Tailwind palette class (`text-gray-500`, `text-slate-400`) is the same in both themes.
    const [, family, shade] = name.match(/^([a-z]+)-(\d{2,3})$/) ?? [];
    const raw = family && (defaultColours as unknown as Record<string, Record<string, string> | undefined>)[family]?.[shade];
    const hex = raw ? parseColor(raw) : null;
    return palette.get(name) ?? (hex ? { light: hex, dark: hex } : undefined);
  }
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
    // `opacity-70` fades the element's text along with it. Applied to the text only: an
    // approximation that is exact for an element without a background of its own.
    const faded = Number(tokensOf(classes).find((token) => /^opacity-\d+$/.test(token))?.slice(8) ?? 100) / 100;
    for (const token of tokensOf(classes)) {
      const match = token.match(COLOUR_CLASS);
      // `dark:` classes would need per-theme handling; this app does not use them.
      if (!match || match[1].split(':').includes('dark')) continue;
      const colour = resolveColour(match[3], palette, variables);
      if (colour === undefined) continue;
      const modifier = match[4]?.match(/\d+/)?.[0];
      const layer = { colour, alpha: modifier === undefined ? 1 : Number(modifier) / 100, label: token };
      if (match[2] === 'text') text.set(match[1], { ...layer, alpha: layer.alpha * faded });
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
    ].join('\n'));
    const candidates = classCandidates(fragments);

    expect(candidates.get('text-accent')).toEqual(['Example.tsx:3']);
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
    const generated = await generatedClassNames([
      'bg-accent', 'bg-primary', 'border-success/20', 'rounded-xl2', 'transition-[transform,box-shadow]', 'hover:bg-bg',
    ]);
    expect(generated.has('bg-primary')).toBe(true);
    expect(generated.has('transition-[transform,box-shadow]')).toBe(true);
    expect(generated.has('hover:bg-bg')).toBe(true);
    expect(generated.has('border-success/20')).toBe(true);
    expect(generated.has('rounded-xl2')).toBe(true);
    expect(generated.has('bg-accent')).toBe(false);
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
      "const b = <b className={`text-xs ${danger ? 'bg-danger text-on-danger' : 'bg-primary text-on-primary'}`} />;",
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
      'Example.tsx:3: hover:text-white on hover:bg-primary-dark is 1.46:1 in dark mode',
      'Example.tsx:5: hover:text-primary on hover:bg-white is 1.13:1 in dark mode',
      'Example.tsx:6: text-white on bg-ink is 1.17:1 in dark mode',
    ]);
  });

  it('follows backgrounds through parents and opacity, and keeps one condition to one outcome', async () => {
    const offenders = await lowContrastPairs(parse('Example.tsx', [
      "import { Check } from 'lucide-react';",
      'const a = <div className="bg-ink"><Check className="text-success" /></div>;',
      'const b = <div className="bg-card"><span className="bg-warning/10 text-warning">x</span></div>;',
      "const c = <div className={on ? 'bg-danger-bg' : 'bg-warning-bg'}><p className={on ? 'text-danger' : 'text-warning'}>x</p></div>;",
      "const d = <div className={on ? 'bg-danger-bg' : 'bg-warning-bg'}><p className={off ? 'text-danger' : 'text-warning'}>x</p></div>;",
      'const e = <div aria-hidden="true" className="bg-card"><span className="text-border">·</span></div>;',
      'const f = <aside className="bg-sidebar"><button className="text-gray-500">Section</button></aside>;',
      'const g = <div className="bg-card"><span className="text-muted opacity-60">Upload</span></div>;',
      'const h = <div className="bg-card"><button disabled className="text-border">Off</button></div>;',
    ].join('\n')).classStrings);

    // Measured by hand from index.css (light / dark):
    // a: an icon, so 3:1 -- success on ink is 3.56 / 1.94 (ink is near-white in dark mode).
    // b: warning over 10% of itself on card is 4.38 in light mode.
    // c: one condition, so danger's wash is never under warning's text; d: two conditions can mix,
    //    and warning on danger's wash is 4.11. e: decorative.
    // f: a raw palette class on the fixed sidebar, #6b7280 on #1f1420, is 3.68 in both themes.
    // g: opacity-60 fades muted text on card to 2.30 / 2.68. h: always disabled, so exempt.
    expect(offenders).toEqual([
      'Example.tsx:2: text-success on bg-ink is 1.94:1 in dark mode',
      'Example.tsx:3: text-warning on bg-card > bg-warning/10 is 4.38:1 in light mode',
      'Example.tsx:5: text-warning on bg-danger-bg is 4.11:1 in light mode',
      'Example.tsx:7: text-gray-500 on bg-sidebar is 3.68:1 in dark mode',
      'Example.tsx:7: text-gray-500 on bg-sidebar is 3.68:1 in light mode',
      'Example.tsx:8: text-muted on bg-card is 2.30:1 in light mode',
      'Example.tsx:8: text-muted on bg-card is 2.68:1 in dark mode',
    ]);
  });

  it('all text in the source meets WCAG AA contrast on its own background in both themes', async () => {
    expect(await lowContrastPairs(parseSource().classStrings)).toEqual([]);
  });
});
