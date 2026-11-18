import { Line } from 'react-chartjs-2';
import { Chart as ChartJS, CategoryScale, LinearScale, LineElement, PointElement, Tooltip } from 'chart.js';
import type { NetWorthData } from '../../api/endpoints';
import { FinoraCard, useChartColors } from '../../design-system';
import { unknownCardsNote } from '../../lib/amountOwed';

ChartJS.register(CategoryScale, LinearScale, LineElement, PointElement, Tooltip);

/** Net worth in one small card: the screen is about investments, but this is the trend's only home. */
export function NetWorthCard({ netWorth, failed, money }: { netWorth: NetWorthData | null; failed: boolean; money: (n: number) => string }) {
  const colors = useChartColors();
  if (failed || !netWorth) {
    return (
      <FinoraCard padding="lg">
        <p className="text-[13px] font-medium text-muted">Net worth</p>
        <p className="mt-1 text-sm text-muted">Could not load your net worth.</p>
      </FinoraCard>
    );
  }
  const note = unknownCardsNote(netWorth.cardsWithUnknownAmountOwed);
  const enoughHistory = netWorth.history.length >= 2;
  return (
    <FinoraCard padding="lg" className="flex flex-col gap-4 md:flex-row md:items-center md:justify-between">
      <div className="min-w-0">
        <p className="text-[13px] font-medium text-muted">Net worth</p>
        <p className={`mt-0.5 font-display text-2xl font-extrabold tracking-tight ${netWorth.netWorth < 0 ? 'text-danger' : 'text-ink'}`}>
          {money(netWorth.netWorth)}
        </p>
        <p className="text-[13px] text-muted">{`${money(netWorth.totalAssets)} assets · ${money(netWorth.totalLiabilities)} liabilities`}</p>
        {note && <p className="mt-1 text-xs text-muted">{note}</p>}
      </div>
      {enoughHistory ? (
        <div className="h-16 w-full md:w-96">
          <Line
            aria-label="Net worth over time"
            data={{
              labels: netWorth.history.map((h) => h.date),
              datasets: [{ data: netWorth.history.map((h) => h.netWorth), borderColor: colors.green, borderWidth: 2, pointRadius: 0, tension: 0.3 }],
            }}
            options={{
              responsive: true,
              maintainAspectRatio: false,
              plugins: { legend: { display: false } },
              scales: { x: { display: false }, y: { display: false } },
            }}
          />
        </div>
      ) : (
        <p className="text-[13px] text-muted md:max-w-xs md:text-right">Building your net worth trend. Check back in a day or two.</p>
      )}
    </FinoraCard>
  );
}
