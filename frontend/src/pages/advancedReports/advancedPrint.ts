import type { AdvancedReport } from '../../api/endpoints';
import { fmt } from '../reportSections/reportFormat';
import { methodBreakdown } from '../reportSections/methodBreakdown';
import { advancedExportName, withheldRate } from './advancedExport';
import {
  againstName, compactAmount, coverageNote, gateNote, incompleteNote, leftOutNote, monthShort, monthYear, percentChange,
  periodSpan, pointsChange, signedAmount,
} from './advancedFormat';

/**
 * The period as a printable page, for "Export PDF" (mobile) and "Print / PDF" (web). The same file lives at
 * mobile/src/lib/advancedReports/advancedPrint.ts and frontend/src/pages/advancedReports/advancedPrint.ts,
 * differing only in where the currency formatter is imported from; keep the two in step.
 *
 * Laid out as a statement of one person's money rather than a table dump: a graphite cover with the period
 * and what was kept, the headline figures, a month-by-month chart, then where the money went, who was paid,
 * how, where income came from, and what stood out. It holds everything the CSV holds. The screen spreads
 * this over five tabs and charts, so a print of the screen would carry only the open tab. Every caveat the
 * page shows is printed, so the paper never reads more certain than the app; every figure, name and
 * sentence is the server's or the page's own wording.
 *
 * Printing rules this follows:
 * - Every coloured fill (the cover, chart bars, share bars) is drawn as SVG, never as a CSS background.
 *   Browsers and iOS drop background colours when printing unless told otherwise; the cover's light text
 *   would then sit on white paper.
 * - Self-contained: styles inline, no images, scripts or links. expo-print renders it in a WebView with no
 *   network, and the web page drops it into its own document for the browser's print dialog. Fonts name
 *   the app's (Manrope, Inter), which the web page has loaded, then each platform's system face.
 * - Category, merchant and income-source names are user-controlled, so every value is escaped.
 */

const GRAPHITE = '#262A33';
const PAPER = '#F4F1EC';
const FOREST = '#0F4C3F';
const INCOME = '#147B3A';
const SPEND = '#B91C1C';
const TRACK = '#ECEAE5';
const RULE = '#E3E2DE';
/** Category and method segments, largest first: graphite, then the premium forest and its tints. */
const RAMP = ['#262A33', '#0F4C3F', '#3F6B5E', '#7C978C', '#AFC0B8', '#CFD8D3'];
const REST = '#DAD8D2';

function esc(value: string | number): string {
  return String(value)
    .replace(/&/g, '&amp;')
    .replace(/</g, '&lt;')
    .replace(/>/g, '&gt;')
    .replace(/"/g, '&quot;');
}

/** One decimal, so 12.34 reads as 12.3 and a whole number stays whole. */
const pct = (n: number) => `${Math.round(n * 10) / 10}%`;
/** A finite number for an SVG attribute, rounded to two places; anything else is 0. */
const num = (n: number) => (Number.isFinite(n) ? Math.round(n * 100) / 100 : 0);

/** "2026-09-03" -> "3 Sep 2026". Built on monthYear (en-US month names) because Intl's en-GB and
 *  en-IN write September as "Sept"; the day is read straight from the string, never through a Date. */
function dayLabel(iso: string): string {
  return `${Number(iso.slice(8, 10))} ${monthYear(iso.slice(0, 7))}`;
}

/** "23 Oct 2026" for the footer, from a Date in local time. */
function dateLabel(d: Date): string {
  const month = `${d.getFullYear()}-${String(d.getMonth() + 1).padStart(2, '0')}`;
  return `${d.getDate()} ${monthYear(month)}`;
}

/** "USD 95.00": the same format as the screen's (frontend lib/foreignAmount.ts, mobile fmtForeignAmount). */
const foreign = (currency: string, amount: number) =>
  `${currency} ${amount.toLocaleString('en-US', { minimumFractionDigits: 2, maximumFractionDigits: 2 })}`;

function table(head: string[], rows: string[][], numeric: number[]): string {
  const cell = (tag: 'th' | 'td', i: number, v: string) => `<${tag}${numeric.includes(i) ? ' class="num"' : ''}>${v}</${tag}>`;
  return `<table><thead><tr>${head.map((h, i) => cell('th', i, esc(h))).join('')}</tr></thead>`
    + `<tbody>${rows.map((r) => `<tr>${r.map((v, i) => cell('td', i, v)).join('')}</tr>`).join('')}</tbody></table>`;
}

/** A section: its heading, an optional line saying what it shows, and its content. */
function section(title: string, lede: string | null, inner: string): string {
  return `<section><h2>${esc(title)}</h2>${lede ? `<p class="lede">${esc(lede)}</p>` : ''}${inner}</section>`;
}

/** A change in spending: up is coloured as spending, down as income; the sign carries it either way. */
function spendChange(change: number | null): string {
  if (change == null) return '<span class="quiet">New</span>';
  const rounded = Math.round(change);
  const cls = rounded > 0 ? 'out' : rounded < 0 ? 'in' : 'quiet';
  return `<span class="${cls}">${esc(signedAmount(change, fmt))}</span>`;
}

/** One stacked bar from shares (summing to about 100), each segment a filled SVG rect. */
function shareBar(parts: { share: number; color: string }[]): string {
  let x = 0;
  const rects = parts
    .filter((p) => p.share > 0)
    .map((p) => {
      const w = Math.max(0, Math.min(100 - x, p.share));
      const rect = `<rect class="seg" x="${num(x)}" y="0" width="${num(w)}" height="10" fill="${p.color}"/>`;
      x += w;
      return rect;
    }).join('');
  return `<svg class="share" viewBox="0 0 100 10" preserveAspectRatio="none" aria-hidden="true">`
    + `<rect x="0" y="0" width="100" height="10" fill="${TRACK}"/>${rects}</svg>`;
}

/** A thin bar for a ranked row: its length is the row's amount against the largest. */
function rowBar(amount: number, max: number, color: string): string {
  const w = max > 0 ? Math.max(0, Math.min(100, (amount / max) * 100)) : 0;
  return `<svg class="rowbar" viewBox="0 0 100 4" preserveAspectRatio="none" aria-hidden="true">`
    + `<rect x="0" y="0" width="100" height="4" fill="${TRACK}"/><rect x="0" y="0" width="${num(w)}" height="4" fill="${color}"/></svg>`;
}

const swatch = (color: string) =>
  `<svg class="swatch" viewBox="0 0 10 10" aria-hidden="true"><rect width="10" height="10" rx="2" fill="${color}"/></svg>`;

/** Money in and out for each month, side by side. Part months are faded, as the screen marks them. */
function flowChart(report: AdvancedReport): string {
  const months = report.months;
  const W = 640;
  const top = 18;
  const base = 150;
  const H = 190;
  const plot = base - top;
  const max = Math.max(0, ...months.map((m) => Math.max(m.income, m.expense)));
  const n = Math.max(1, months.length);
  const group = W / n;
  const bar = Math.max(2, Math.min(24, group * 0.3));
  const step = Math.ceil(months.length / 12);
  const h = (v: number) => (max > 0 ? Math.max(0, (v / max) * plot) : 0);

  const bars = months.map((m, i) => {
    const cx = group * i + group / 2;
    const fade = m.complete ? '' : ' fill-opacity="0.4"';
    const hi = h(m.income);
    const ho = h(m.expense);
    const label = i % step === 0
      ? `<text x="${num(cx)}" y="${base + 16}" text-anchor="middle" class="axis">${esc(monthShort(m.month))}</text>`
        + (i === 0 || m.month.endsWith('-01')
          ? `<text x="${num(cx)}" y="${base + 30}" text-anchor="middle" class="axis faint">${esc(m.month.slice(0, 4))}</text>` : '')
      : '';
    return `<rect class="bar-in" x="${num(cx - bar - 1)}" y="${num(base - hi)}" width="${num(bar)}" height="${num(hi)}" rx="1.5" fill="${INCOME}"${fade}/>`
      + `<rect class="bar-out" x="${num(cx + 1)}" y="${num(base - ho)}" width="${num(bar)}" height="${num(ho)}" rx="1.5" fill="${SPEND}"${fade}/>`
      + label;
  }).join('');

  const grid = max > 0
    ? `<line x1="0" y1="${top}" x2="${W}" y2="${top}" stroke="${RULE}" stroke-dasharray="3 3"/>`
      + `<text x="${W}" y="${top - 5}" text-anchor="end" class="axis faint">${esc(compactAmount(max))}</text>`
    : '';
  const partNote = months.some((m) => !m.complete) ? '<p class="small">Faded bars are part months.</p>' : '';
  return `<div class="legend">${swatch(INCOME)}<span>Money in</span>${swatch(SPEND)}<span>Money out</span></div>`
    + `<svg class="flow" viewBox="0 0 ${W} ${H}" aria-hidden="true">${grid}`
    + `<line x1="0" y1="${base}" x2="${W}" y2="${base}" stroke="#C9C7C0"/>${bars}</svg>${partNote}`;
}

/** The brand mark (the favicon's F), drawn in paper on the graphite cover. */
const MARK = `<svg class="mark" viewBox="0 0 100 100" aria-hidden="true"><rect x="4" y="4" width="92" height="92" rx="22" fill="${PAPER}" fill-opacity="0.12"/>`
  + `<rect x="32" y="26" width="13" height="48" rx="4" fill="${PAPER}"/><rect x="32" y="26" width="34" height="13" rx="4" fill="${PAPER}"/>`
  + `<rect x="32" y="50" width="25" height="12" rx="4" fill="${PAPER}"/></svg>`;

/** The scoped stylesheet. Every rule sits under .fy-ar, so on web it cannot restyle the page around it. */
export const ADVANCED_PRINT_CSS = `
.fy-ar { font-family: Inter, -apple-system, 'Segoe UI', Roboto, sans-serif; color: ${GRAPHITE}; background: #fff; font-size: 12.5px; line-height: 1.45;
  font-variant-numeric: tabular-nums; -webkit-print-color-adjust: exact; print-color-adjust: exact; }
.fy-ar svg { display: block; }
.fy-ar h1, .fy-ar h2, .fy-ar .hero-num, .fy-ar .fig-num { font-family: Manrope, Inter, -apple-system, 'Segoe UI', Roboto, sans-serif; }
.fy-ar h1 { font-size: 30px; font-weight: 800; line-height: 1.1; letter-spacing: -0.01em; margin: 0; color: ${PAPER}; }
.fy-ar h2 { font-size: 16px; font-weight: 700; margin: 0 0 2px; color: ${GRAPHITE}; break-after: avoid; }
.fy-ar ul { list-style: disc; }
.fy-ar section { margin-top: 26px; }
.fy-ar .lede { margin: 0 0 10px; color: #6B6A65; font-size: 11.5px; }
.fy-ar .cover { position: relative; border-radius: 16px; overflow: hidden; break-inside: avoid; }
.fy-ar .cover-bg { position: absolute; top: 0; left: 0; width: 100%; height: 100%; }
.fy-ar .cover-in { position: relative; padding: 22px 26px 24px; color: ${PAPER}; }
.fy-ar .brand { display: flex; align-items: center; gap: 8px; font-weight: 600; font-size: 12px; }
.fy-ar .brand .mark { width: 22px; height: 22px; }
.fy-ar .brand .kind { margin-left: auto; opacity: 0.75; font-weight: 500; }
.fy-ar .period { margin-top: 26px; }
.fy-ar .span-sub { margin-top: 6px; opacity: 0.75; font-size: 12px; }
.fy-ar .hero { margin-top: 26px; padding-top: 16px; border-top: 1px solid rgba(244, 241, 236, 0.22); display: flex; align-items: flex-end; gap: 24px; flex-wrap: wrap; }
.fy-ar .hero-label { font-size: 12px; opacity: 0.8; }
.fy-ar .hero-num { font-size: 36px; font-weight: 800; line-height: 1.05; margin-top: 2px; letter-spacing: -0.01em; }
.fy-ar .hero-side { margin-left: auto; text-align: right; font-size: 12px; }
.fy-ar .hero-side .rate { font-size: 15px; font-weight: 700; }
.fy-ar .cover .chg { color: ${PAPER}; opacity: 0.75; }
.fy-ar .figs { display: flex; margin-top: 16px; break-inside: avoid; }
.fy-ar .fig { flex: 1; padding: 2px 16px; border-left: 1px solid ${RULE}; }
.fy-ar .fig:first-child { padding-left: 0; border-left: 0; }
.fy-ar .fig-label { font-size: 11.5px; color: #6B6A65; }
.fy-ar .fig-num { font-size: 19px; font-weight: 700; margin-top: 2px; }
.fy-ar .chg { font-size: 11px; color: #55544F; margin-top: 2px; }
.fy-ar .in { color: ${INCOME}; }
.fy-ar .out { color: ${SPEND}; }
.fy-ar .quiet { color: #6B6A65; }
.fy-ar .notes { margin: 18px 0 0; padding: 10px 14px 10px 30px; border: 1px solid ${RULE}; border-left: 3px solid ${FOREST}; border-radius: 10px; color: #3F3E3A; font-size: 11.5px; break-inside: avoid; }
.fy-ar .notes li { margin: 2px 0; }
.fy-ar .legend { display: flex; align-items: center; gap: 6px; font-size: 11px; color: #55544F; margin-bottom: 6px; }
.fy-ar .legend span { margin-right: 10px; }
.fy-ar .swatch { width: 9px; height: 9px; flex: none; }
.fy-ar .flow { width: 100%; height: auto; break-inside: avoid; }
.fy-ar .axis { font-size: 11.5px; fill: #6B6A65; font-family: Inter, -apple-system, 'Segoe UI', Roboto, sans-serif; }
.fy-ar .faint { fill: #9A988F; }
.fy-ar .share { width: 100%; height: 12px; border-radius: 6px; overflow: hidden; margin: 4px 0 10px; }
.fy-ar .rowbar { width: 100%; height: 4px; margin-top: 4px; }
.fy-ar table { width: 100%; border-collapse: collapse; }
.fy-ar th { text-align: left; font-size: 10.5px; color: #6B6A65; font-weight: 600; padding: 6px 6px; border-bottom: 1px solid #CFCDC6; }
.fy-ar td { padding: 7px 6px; border-bottom: 1px solid ${RULE}; vertical-align: top; }
.fy-ar tr { break-inside: avoid; }
.fy-ar th:first-child, .fy-ar td:first-child { padding-left: 0; }
.fy-ar th:last-child, .fy-ar td:last-child { padding-right: 0; }
.fy-ar .num { text-align: right; white-space: nowrap; }
.fy-ar .name { display: flex; align-items: center; gap: 7px; }
.fy-ar .tag { margin-left: 6px; font-size: 10px; color: #7A5A1E; border: 1px solid #E2CFA6; border-radius: 4px; padding: 0 4px; }
.fy-ar .small { font-size: 11px; color: #6B6A65; margin: 6px 0 0; }
.fy-ar .insights { list-style: none; margin: 0; padding: 0; }
.fy-ar .insights li { padding: 10px 0 10px 14px; border-left: 2px solid ${FOREST}; margin-bottom: 8px; break-inside: avoid; }
.fy-ar .insights strong { display: block; font-size: 13px; }
.fy-ar .foot { margin-top: 30px; padding-top: 10px; border-top: 1px solid ${RULE}; font-size: 10.5px; color: #6B6A65; }
`;

/** The report as an HTML fragment, wrapped in one .fy-ar element. `now` dates the footer. */
export function advancedPrintBody(report: AdvancedReport, now: Date = new Date()): string {
  const { period, totals, comparison: c } = report;
  const against = againstName(period);
  const span = periodSpan(period) ?? '';
  const change = (text: string | null) => (c.reliable && text ? `<div class="chg">${esc(text)} vs ${esc(against)}</div>` : '');
  const n = period.monthCount;

  const rateWhy = totals.savingsRatePct == null ? withheldRate(totals.savingsRateGateReason) : null;
  const notes = [
    coverageNote(period), leftOutNote(period), incompleteNote(period), gateNote(c, period),
    // Said as Reports' PDF says it: on paper "Not shown: ..." alone does not name what is not shown.
    rateWhy ? `Savings rate. ${rateWhy}.` : null,
    totals.unresolvedInflow > 0 ? `${fmt(totals.unresolvedInflow)} received is not counted as income yet.` : null,
    'Investment transfers are not counted in any figure.',
  ].filter((x): x is string => !!x);

  // The cover: the period, then what was kept (or by how much spending ran over), then the savings rate.
  const kept = totals.net >= 0;
  const rate = totals.savingsRatePct;
  const rateText = rate == null ? null
    : rate >= 0 ? `${pct(rate)} of income kept` : `Spending was ${pct(Math.abs(rate))} more than income`;
  const rateChange = rate != null && c.reliable ? pointsChange(c.savingsRateDeltaPp) : null;
  // Net is compared as an amount, as the screen does: a percent of a small or negative net misleads.
  const netChange = c.netDelta == null ? null
    : Math.round(c.netDelta) === 0 ? 'No change' : `${c.netDelta > 0 ? '↑' : '↓'} ${fmt(Math.abs(c.netDelta))}`;
  const cover = `<div class="cover"><svg class="cover-bg" viewBox="0 0 100 100" preserveAspectRatio="none" aria-hidden="true">`
    + `<rect width="100" height="100" fill="${GRAPHITE}"/></svg><div class="cover-in">`
    + `<div class="brand">${MARK}<span>Fynora</span><span class="kind">Advanced report</span></div>`
    + `<div class="period"><h1>${esc(span)}</h1>`
    + `<div class="span-sub">${esc(`${n} ${n === 1 ? 'month' : 'months'}${c.reliable ? `, compared with ${against}` : ''}`)}</div></div>`
    + `<div class="hero"><div><div class="hero-label">${kept ? 'You kept' : 'You spent more than came in by'}</div>`
    + `<div class="hero-num">${esc(fmt(Math.abs(totals.net)))}</div>${change(netChange)}</div>`
    + (rateText ? `<div class="hero-side"><div class="rate">${esc(rateText)}</div>${rateChange ? `<div class="chg">${esc(`${rateChange} vs ${against}`)}</div>` : ''}</div>` : '')
    + `</div></div></div>`;

  const fig =(label: string, value: string, cls: string, chg: string) =>
    `<div class="fig"><div class="fig-label">${esc(label)}</div><div class="fig-num${cls ? ` ${cls}` : ''}">${esc(value)}</div>${chg}</div>`;
  const figs = `<div class="figs">${[
    fig('Money in', fmt(totals.income), 'in', change(percentChange(c.incomeDeltaPct))),
    fig('Money out', fmt(totals.expense), 'out', change(percentChange(c.expenseDeltaPct))),
    totals.avgMonthlyExpense != null
      ? fig('Spent in an average month', fmt(totals.avgMonthlyExpense), '', change(percentChange(c.avgMonthlyExpenseDeltaPct)))
      : '',
  ].join('')}</div>`;

  const parts: string[] = [
    cover,
    figs,
    `<ul class="notes">${notes.map((x) => `<li>${esc(x)}</li>`).join('')}</ul>`,
  ];

  if (report.months.length > 0) {
    parts.push(section('Money in and out, month by month', null, flowChart(report) + table(
      ['Month', 'Money in', 'Money out', 'Kept', 'Savings rate'],
      report.months.map((m) => [
        `${esc(monthYear(m.month))}${m.complete ? '' : '<span class="tag">Part month</span>'}`,
        esc(fmt(m.income)), esc(fmt(m.expense)), esc(fmt(m.net)), m.savingsRatePct == null ? '—' : esc(pct(m.savingsRatePct)),
      ]),
      [1, 2, 3, 4],
    )));
  }

  const cats = report.categories;
  if (cats.rows.length > 0) {
    const color = (i: number) => RAMP[Math.min(i, RAMP.length - 1)];
    const segments = [
      ...cats.rows.map((r, i) => ({ share: r.sharePct, color: color(i) })),
      ...(cats.otherAmount > 0 ? [{ share: cats.otherSharePct, color: REST }] : []),
    ];
    const rows = cats.rows.map((r, i) => [
      `<span class="name">${swatch(color(i))}${esc(r.name)}</span>`, esc(fmt(r.amount)), esc(pct(r.sharePct)), spendChange(r.changeAmount),
    ]);
    if (cats.otherAmount > 0) {
      rows.push([`<span class="name">${swatch(REST)}${esc(`Other (${cats.categoryCount - cats.rows.length} more)`)}</span>`,
        esc(fmt(cats.otherAmount)), esc(pct(cats.otherSharePct)), '']);
    }
    parts.push(section('Where the money went', 'Spending by category, and how each moved against the earlier period.',
      shareBar(segments) + table(['Category', 'Spent', 'Share', 'Change'], rows, [1, 2, 3])));
  }

  const merchants = report.merchants;
  if (merchants.rows.length > 0) {
    const max = Math.max(...merchants.rows.map((m) => m.amount));
    parts.push(section('Who you paid most',
      `${Math.round(merchants.attributedSharePct)}% of spending is tied to a named merchant.`,
      table(
        ['Merchant', 'Spent', 'Purchases', 'Change'],
        merchants.rows.map((m) => [
          `${esc(m.name)}${rowBar(m.amount, max, GRAPHITE)}`, esc(fmt(m.amount)), esc(m.transactionCount), spendChange(m.changeAmount),
        ]),
        [1, 2, 3],
      )));
  }

  const methods = methodBreakdown(report.paymentMethods);
  if (methods) {
    const all = [...methods.named, ...(methods.unidentified ? [methods.unidentified] : [])];
    const total = all.reduce((s, r) => s + r.amount, 0);
    const color = (i: number, unidentified: boolean) => (unidentified ? REST : RAMP[Math.min(i, RAMP.length - 1)]);
    const flagged = all.map((r, i) => ({ ...r, color: color(i, !!methods.unidentified && i === all.length - 1) }));
    parts.push(section('How you paid', null,
      shareBar(flagged.map((r) => ({ share: total > 0 ? (r.amount / total) * 100 : 0, color: r.color })))
      + table(['Method', 'Spent', 'Share'],
        flagged.map((r) => [`<span class="name">${swatch(r.color)}${esc(r.label)}</span>`, esc(fmt(r.amount)),
          esc(pct(total > 0 ? (r.amount / total) * 100 : 0))]),
        [1, 2])));
  }

  const income = report.income;
  if (income.sources.length > 0) {
    // How steady income was, worded as the Income tab words it (IncomePanel / IncomeSection).
    const months = report.months.length;
    let steadiness = `Income came in during ${income.monthsWithIncome} of ${months} ${months === 1 ? 'month' : 'months'}.`;
    if (income.highestMonth && income.lowestMonth && income.lowestAsPctOfHighest != null) {
      steadiness += ` Among whole months the highest was ${monthYear(income.highestMonth)} (${fmt(income.highestMonthAmount ?? 0)})`
        + ` and the lowest ${monthYear(income.lowestMonth)} (${fmt(income.lowestMonthAmount ?? 0)}),`
        + ` ${Math.round(income.lowestAsPctOfHighest)}% of the highest.`;
    }
    const max = Math.max(...income.sources.map((s) => s.amount));
    parts.push(section('Where income came from', steadiness, table(
      ['Source', 'Received', 'Share'],
      income.sources.map((s) => [`${esc(s.label)}${rowBar(s.amount, max, INCOME)}`, esc(fmt(s.amount)), esc(pct(s.sharePct))]),
      [1, 2],
    )));
  }

  const intl = report.international;
  if (intl.transactionCount > 0) {
    const lede = `${fmt(intl.totalSpend)} across ${intl.transactionCount} transactions: ${fmt(intl.purchasesSpend)} in foreign purchases`
      + ` and ${fmt(intl.otherChargesSpend)} in other charges (GST, FX markup and anything billed in rupees).`;
    const byCurrency = intl.byCurrency.length > 0
      ? table(
        ['Spent in the currency', 'Billed in rupees', 'Transactions'],
        intl.byCurrency.map((x) => [esc(foreign(x.currency, x.foreignTotal)), esc(fmt(x.rupeeTotal)), esc(x.transactionCount)]),
        [1, 2],
      )
      : '';
    parts.push(section('Spending abroad', lede, byCurrency));
  }

  if (report.unusualSpends.length > 0) {
    parts.push(section('Larger than usual for the category', null, table(
      ['Date', 'Description', 'Category', 'Spent', 'Usual'],
      report.unusualSpends.map((u) => [esc(dayLabel(u.date)), esc(u.label), esc(u.category), esc(fmt(u.amount)), esc(fmt(u.typicalAmount))]),
      [3, 4],
    )));
  }

  if (report.insights.length > 0) {
    parts.push(section('What stood out', null, `<ul class="insights">${report.insights
      .map((i) => `<li><strong>${esc(i.headline)}</strong>${esc(i.detail)}</li>`).join('')}</ul>`));
  }

  parts.push(`<p class="foot">${esc(`Prepared by Fynora on ${dateLabel(now)} from the transactions in your account. Amounts are in Indian rupees.`)}</p>`);

  return `<div class="fy-ar">${parts.join('')}</div>`;
}

/** A whole A4 page around the body, for expo-print. */
export function advancedPrintDocument(report: AdvancedReport, now: Date = new Date()): string {
  return `<!DOCTYPE html>
<html>
<head><meta charset="utf-8" /><meta name="viewport" content="width=device-width, initial-scale=1" />
<title>${esc(advancedPdfName(report))}</title>
<style>body { margin: 0; padding: 28px 30px; background: #fff; }${ADVANCED_PRINT_CSS}</style>
</head>
<body>${advancedPrintBody(report, now)}</body>
</html>`;
}

/** The PDF's file name: the CSV's, with the PDF extension. */
export function advancedPdfName(report: AdvancedReport): string {
  return advancedExportName(report).replace(/\.csv$/, '.pdf');
}
