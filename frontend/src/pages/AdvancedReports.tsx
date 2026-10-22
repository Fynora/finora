import { useEffect, useRef, useState } from 'react';
import { flushSync } from 'react-dom';
import { Link, useSearchParams } from 'react-router-dom';
import { keepPreviousData, useQuery } from '@tanstack/react-query';
import { BarChart3, Crown, Loader2, Lock } from 'lucide-react';
import {
  analyticsApi, type AdvancedRange, type AdvancedReport, type AdvancedReportParams,
} from '../api/endpoints';
import { Button, EmptyState, FinoraCard, Skeleton } from '../design-system';
import { PremiumFeatureGate } from '../components/PremiumFeatureGate';
import { useDelayedLoading } from '../hooks/useDelayedLoading';
import { downloadBlob, toCsv } from '../lib/download';
import { trackNavigation } from '../lib/trackNavigation';
import { advancedCsvRows, advancedExportName } from './advancedReports/advancedExport';
import { advancedPdfName } from './advancedReports/advancedPrint';
import { PRINTING_CLASS, PrintSheet } from './advancedReports/PrintSheet';
import { incompleteNote, noDataCopy, RANGE_OPTIONS } from './advancedReports/advancedFormat';
import { CashFlowChart } from './advancedReports/CashFlowChart';
import { CategoriesPanel } from './advancedReports/CategoriesPanel';
import { CategorisationPanel } from './advancedReports/CategorisationPanel';
import { CategoryTrend } from './advancedReports/CategoryTrend';
import { IncomePanel } from './advancedReports/IncomePanel';
import { InsightsList } from './advancedReports/InsightsList';
import { InternationalCard } from './advancedReports/InternationalCard';
import { KpiRow } from './advancedReports/KpiRow';
import { MerchantsPanel } from './advancedReports/MerchantsPanel';
import { PeriodControls, type PeriodChoice } from './advancedReports/PeriodControls';
import { PeriodNotice } from './advancedReports/PeriodNotice';
import { UnusualSpends } from './advancedReports/UnusualSpends';
import { PaymentMethods } from './reportSections/PaymentMethods';
import { methodBreakdown } from './reportSections/methodBreakdown';

type TabId = 'overview' | 'spending' | 'income' | 'merchants' | 'categorisation';
const TABS: { id: TabId; label: string }[] = [
  { id: 'overview', label: 'Overview' },
  { id: 'spending', label: 'Spending' },
  { id: 'income', label: 'Income' },
  { id: 'merchants', label: 'Merchants' },
  { id: 'categorisation', label: 'Category insights' },
];

const DEFAULT_RANGE: AdvancedRange = 'LAST_12_MONTHS';
const MONTH = /^\d{4}-(0[1-9]|1[0-2])$/;

/** The upgrade prompt shown in place of the whole page for a Free user -- more context than
 *  PremiumFeatureGate's own generic default, since this gates an entire page rather than one
 *  widget. Fails closed exactly like the default (PremiumFeatureGate renders nothing at all while
 *  the entitlements query is loading or erroring; this only ever appears once it has confirmed
 *  the feature is absent). */
function UpgradePrompt() {
  return (
    <FinoraCard padding="lg" className="max-w-lg mx-auto my-12">
      <EmptyState
        icon={Lock}
        iconBg="bg-primary-light"
        iconColor="text-primary"
        title="Advanced Reports is a Plus & Premium feature"
        desc="How your income, spending, categories and merchants have changed over months and years, compared with the period before -- all built from your own transaction history."
        cta={
          <Link
            to="/app/billing" onClick={() => trackNavigation('subscription', 'contextual')}
            className="inline-flex items-center gap-1.5 bg-primary text-on-primary hover:bg-primary-dark rounded-lg px-4 py-2 text-xs font-semibold"
          >
            <Crown size={14} /> View plans
          </Link>
        }
      />
    </FinoraCard>
  );
}

function BodySkeleton() {
  return (
    <Skeleton.Region label="Loading advanced reports">
      <div className="space-y-6">
        <div className="grid grid-cols-2 gap-3 lg:grid-cols-5">
          {Array.from({ length: 5 }, (_, i) => <Skeleton.Card key={i} />)}
        </div>
        <FinoraCard><Skeleton.Chart className="h-64" /></FinoraCard>
        <div className="grid items-start gap-6 lg:grid-cols-2">
          <FinoraCard><Skeleton.Block className="h-48 w-full" /></FinoraCard>
          <FinoraCard><Skeleton.Block className="h-48 w-full" /></FinoraCard>
        </div>
      </div>
    </Skeleton.Region>
  );
}

function tabPanel(tab: TabId, report: AdvancedReport, goTo: (t: TabId) => void) {
  const { period } = report;
  const methods = methodBreakdown(report.paymentMethods);
  const partial = incompleteNote(period);
  switch (tab) {
    case 'overview':
      return (
        <div className="space-y-6">
          <PeriodNotice report={report} />
          <KpiRow report={report} />
          <CashFlowChart months={report.months} incompleteNoteText={partial} />
          <div className="grid items-start gap-6 lg:grid-cols-2">
            <InsightsList insights={report.insights} />
            <CategoriesPanel report={report} limit={5} onViewAll={() => goTo('spending')} />
          </div>
          <div className="grid items-start gap-6 lg:grid-cols-2">
            <MerchantsPanel report={report} limit={5} onViewAll={() => goTo('merchants')} />
            <PaymentMethods breakdown={methods} />
          </div>
        </div>
      );
    case 'spending':
      return (
        <div className="space-y-6">
          <PeriodNotice report={report} />
          <div className="grid items-start gap-6 lg:grid-cols-5">
            <div className="lg:col-span-3"><CategoriesPanel report={report} /></div>
            <div className="space-y-6 lg:col-span-2">
              <PaymentMethods breakdown={methods} />
              <UnusualSpends items={report.unusualSpends} />
            </div>
          </div>
          <CategoryTrend months={report.months} categories={report.categories.rows} />
          <InternationalCard data={report.international} />
        </div>
      );
    case 'income':
      return (
        <div className="space-y-6">
          <PeriodNotice report={report} />
          <IncomePanel report={report} />
        </div>
      );
    case 'merchants':
      return (
        <div className="space-y-6">
          <PeriodNotice report={report} />
          <MerchantsPanel report={report} />
        </div>
      );
    case 'categorisation':
      return null;
  }
}

/** The gated content -- only ever rendered once PremiumFeatureGate has confirmed ADVANCED_REPORTS
 *  is granted, so none of these queries fire for a Free user. The tab, period and comparison live in
 *  the URL, so a link or a reload lands on the same view. */
function AdvancedReportsContent() {
  const [params, setParams] = useSearchParams();
  const rawRange = params.get('range') as AdvancedRange | null;
  const range: AdvancedRange = RANGE_OPTIONS.some((o) => o.value === rawRange) ? rawRange! : DEFAULT_RANGE;
  const rawTab = params.get('tab') as TabId | null;
  const tab: TabId = TABS.some((t) => t.id === rawTab) ? rawTab! : 'overview';
  const start = MONTH.test(params.get('start') ?? '') ? params.get('start')! : '';
  const end = MONTH.test(params.get('end') ?? '') ? params.get('end')! : '';
  const compare = params.get('compare') === 'SAME_PERIOD_LAST_YEAR' ? 'SAME_PERIOD_LAST_YEAR'
    : params.get('compare') === 'PREVIOUS_PERIOD' ? 'PREVIOUS_PERIOD' : null;
  const lastPeriod = useRef<AdvancedReport['period'] | null>(null);

  const customReady = range !== 'CUSTOM' || (start !== '' && end !== '' && start <= end);
  const request: AdvancedReportParams = {
    range,
    ...(range === 'CUSTOM' ? { startMonth: start, endMonth: end } : {}),
    ...(compare ? { compare } : {}),
  };
  const reportQ = useQuery({
    queryKey: ['advanced-report', request],
    queryFn: () => analyticsApi.advanced(request),
    // Nothing to ask until a custom run has both months; the categorisation tab does not use the period.
    enabled: customReady && tab !== 'categorisation',
    placeholderData: keepPreviousData,
  });
  const report = reportQ.data ?? null;
  if (report) lastPeriod.current = report.period;

  const showSkeleton = useDelayedLoading(reportQ.isLoading);
  const refreshing = reportQ.isFetching && report !== null;

  function update(next: Record<string, string | null>) {
    const p = new URLSearchParams(params);
    for (const [k, v] of Object.entries(next)) {
      if (v === null || v === '') p.delete(k);
      else p.set(k, v);
    }
    setParams(p, { replace: true });
  }

  const choice: PeriodChoice = { range, startMonth: start, endMonth: end, compare };
  function onChoice(next: PeriodChoice) {
    // Moving to a custom run starts from the months already on screen, so the pickers are never empty.
    const seeded = next.range === 'CUSTOM' && (!next.startMonth || !next.endMonth) && lastPeriod.current
      ? { startMonth: lastPeriod.current.startMonth ?? '', endMonth: lastPeriod.current.endMonth ?? '' }
      : { startMonth: next.startMonth, endMonth: next.endMonth };
    update({
      range: next.range === DEFAULT_RANGE ? null : next.range,
      start: next.range === 'CUSTOM' ? seeded.startMonth : null,
      end: next.range === 'CUSTOM' ? seeded.endMonth : null,
      // A comparison choice belongs to one period shape; changing the range starts from its default.
      compare: next.range !== range ? null : next.compare,
    });
  }

  function exportCsv() {
    if (!report || !report.period.hasData) return;
    downloadBlob(new Blob([toCsv(advancedCsvRows(report))], { type: 'text/csv' }), advancedExportName(report));
  }

  // "Print / PDF": the browser's print dialog is where "Save as PDF" lives, as on Reports. The sheet is
  // put on the page first (flushSync), then the dialog opens from the click itself -- not from an effect,
  // which StrictMode runs twice in development. <body> is marked for the length of this print only:
  // index.css hides the rest of the app on paper just while it carries PRINTING_CLASS, so every other
  // page's printing is untouched. The document title is what the browser offers as the PDF's file name.
  // Everything is put back on afterprint. A browser that never fires it for one print still gets the
  // real title back on the next: the title from before the first unfinished print is the one kept.
  const [printing, setPrinting] = useState<AdvancedReport | null>(null);
  const printState = useRef<{ title: string; done: () => void } | null>(null);
  function printReport() {
    if (!report || !report.period.hasData) return;
    const pending = printState.current;
    if (pending) window.removeEventListener('afterprint', pending.done);
    const title = pending ? pending.title : document.title;
    const done = () => {
      window.removeEventListener('afterprint', done);
      printState.current = null;
      document.title = title;
      document.body.classList.remove(PRINTING_CLASS);
      setPrinting(null);
    };
    printState.current = { title, done };
    flushSync(() => setPrinting(report));
    document.body.classList.add(PRINTING_CLASS);
    document.title = advancedPdfName(report).replace(/\.pdf$/, '');
    window.addEventListener('afterprint', done);
    window.print();
  }
  // Leaving the page before afterprint (a browser whose print() does not block) must not strand the
  // body class or the file-name title on whatever page comes next.
  useEffect(() => () => printState.current?.done(), []);

  const period = report?.period ?? null;
  const copy = period && !period.hasData ? noDataCopy(period) : null;

  return (
    <div className="space-y-6">
      {/* The categorisation tab describes Fynora's learning, not a period of your money, so the period
          pickers are not offered there. */}
      {tab !== 'categorisation' && <FinoraCard padding="sm" className="relative">
        {refreshing && (
          <div className="absolute top-2 right-3 flex items-center gap-1 text-2xs uppercase text-primary">
            <Loader2 size={11} className="animate-spin" aria-hidden="true" /> Refreshing…
          </div>
        )}
        <PeriodControls
          choice={choice} period={period} onChange={onChoice} onExport={exportCsv} onPrint={printReport}
          exportDisabled={!report || !report.period.hasData || refreshing}
        />
      </FinoraCard>}
      {printing && <PrintSheet report={printing} />}

      <div role="tablist" aria-label="Advanced Reports sections" className="flex gap-1 overflow-x-auto border-b border-border">
        {TABS.map((t) => (
          <button
            key={t.id} type="button" role="tab" id={`advanced-tab-${t.id}`} aria-selected={tab === t.id}
            aria-controls="advanced-panel" tabIndex={tab === t.id ? 0 : -1}
            onClick={() => update({ tab: t.id === 'overview' ? null : t.id })}
            onKeyDown={(e) => {
              const i = TABS.findIndex((x) => x.id === tab);
              const next = e.key === 'ArrowRight' ? TABS[(i + 1) % TABS.length] : e.key === 'ArrowLeft' ? TABS[(i + TABS.length - 1) % TABS.length] : null;
              if (next) {
                e.preventDefault();
                update({ tab: next.id === 'overview' ? null : next.id });
                document.getElementById(`advanced-tab-${next.id}`)?.focus();
              }
            }}
            className={`-mb-px flex-shrink-0 border-b-2 px-3 py-2 text-sm font-medium ${tab === t.id ? 'border-primary text-ink' : 'border-transparent text-muted hover:text-ink'}`}
          >
            {t.label}
          </button>
        ))}
      </div>

      {/* While another period loads, the figures on screen are the old period's: dimmed and marked busy so
          they are not read as the new choice's. */}
      <div
        role="tabpanel" id="advanced-panel" aria-labelledby={`advanced-tab-${tab}`} aria-busy={refreshing}
        className={refreshing ? 'opacity-50 transition-opacity' : 'transition-opacity'}
      >
        {tab === 'categorisation' ? (
          <CategorisationPanel />
        ) : !customReady ? (
          <FinoraCard padding="lg">
            <EmptyState icon={BarChart3} iconBg="bg-surface" iconColor="text-ink" title="Choose a start and an end month" desc="A custom period needs both, with the start not after the end." />
          </FinoraCard>
        ) : reportQ.isLoading ? (
          showSkeleton ? <BodySkeleton /> : null
        ) : reportQ.isError && !report ? (
          <FinoraCard padding="lg" className="text-center">
            <p className="text-sm text-muted">Couldn’t load Advanced Reports — please try again.</p>
            <Button variant="secondary" onClick={() => void reportQ.refetch()} className="mt-3">Try again</Button>
          </FinoraCard>
        ) : report && copy ? (
          <FinoraCard padding="lg" className="max-w-lg mx-auto">
            <EmptyState
              icon={BarChart3} iconBg="bg-surface" iconColor="text-ink" title={copy.title} desc={copy.desc}
              cta={report.period.noDataReason === 'NO_TRANSACTIONS' ? (
                <Link to="/app/import" onClick={() => trackNavigation('import-statement', 'contextual')} className="inline-flex items-center gap-1.5 bg-primary text-on-primary hover:bg-primary-dark rounded-lg px-4 py-2 text-xs font-semibold">
                  Import Statement
                </Link>
              ) : undefined}
            />
          </FinoraCard>
        ) : report ? (
          tabPanel(tab, report, (t) => update({ tab: t }))
        ) : null}
      </div>
    </div>
  );
}

export default function AdvancedReports() {
  return (
    <div className="space-y-6">
      <div>
        <h1 className="text-xl font-bold text-ink flex items-center gap-2">
          <Crown size={18} className="text-primary" /> Advanced Reports
        </h1>
        <p className="text-sm text-muted mt-0.5">How your income, spending and savings have changed over time, and what is behind it.</p>
      </div>
      <PremiumFeatureGate featureKey="ADVANCED_REPORTS" fallback={<UpgradePrompt />}>
        <AdvancedReportsContent />
      </PremiumFeatureGate>
    </div>
  );
}
