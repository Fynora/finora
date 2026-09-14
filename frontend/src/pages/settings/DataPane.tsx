import { useEffect, useState } from 'react';
import { analyticsApi, type ImportStatistics } from '../../api/endpoints';
import { ExportDataModal } from '../../components/ExportDataModal';
import { formatDayMonthYear, MetricTile } from '../../components/AccountUI';
import { Button } from '../../design-system/Button';
import { FinoraCard } from '../../design-system/FinoraCard';
import { SectionHeader } from '../../design-system/SectionHeader';

export function DataPane({
  loading, loadError, signInMethod,
}: {
  loading: boolean; loadError: boolean; signInMethod: 'PASSWORD' | 'GOOGLE';
}) {
  const [importStats, setImportStats] = useState<ImportStatistics | null>(null);
  const [importStatsFailed, setImportStatsFailed] = useState(false);
  const [exportOpen, setExportOpen] = useState(false);

  useEffect(() => {
    // Best-effort -- shows "—" for any stat that doesn't load rather than blocking the rest of
    // the pane on it. "—" for a failed request is distinguished from "—" for a genuinely empty
    // account via importStatsFailed, which flags the difference below.
    analyticsApi.importStatistics().then(setImportStats).catch(() => setImportStatsFailed(true));
  }, []);

  return (
    <FinoraCard>
      <SectionHeader title="Data" />
      <p className="text-sm text-muted -mt-3 mb-5">Your imported statements and transaction history</p>
      <div className="grid grid-cols-2 md:grid-cols-4 gap-4">
        <MetricTile label="Statements Imported" value={importStats ? importStats.totalStatements.toLocaleString('en-IN') : '—'} />
        <MetricTile label="Transactions" value={importStats ? importStats.totalTransactionsImported.toLocaleString('en-IN') : '—'} />
        <MetricTile label="Rows Skipped" value={importStats ? importStats.totalTransactionsSkipped.toLocaleString('en-IN') : '—'} />
        <MetricTile label="Last Import" value={formatDayMonthYear(importStats?.lastImportedAt)} />
      </div>
      {importStatsFailed && (
        <p className="text-xs text-warning mt-3">
          Couldn't load these statistics just now — they're unavailable, not zero.
        </p>
      )}
      <div className="pt-4 mt-4 border-t border-border">
        <p className="text-ink font-medium text-sm">Export My Data</p>
        <p className="text-muted text-2xs mt-1 mb-3">
          Download a ZIP of everything in your account, including your original bank statement files.
        </p>
        <Button
          variant="secondary"
          size="sm"
          disabled={loading || loadError}
          title={loadError ? "Couldn't load your account details" : undefined}
          onClick={() => setExportOpen(true)}
        >
          Export My Data
        </Button>
      </div>

      {exportOpen && <ExportDataModal onClose={() => setExportOpen(false)} signInMethod={signInMethod} />}
    </FinoraCard>
  );
}
