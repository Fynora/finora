/** A small line of a series, drawn without axes: the shape of the months, not their values. The
 *  figures it stands for are always written beside it, so it is hidden from assistive technology. */
export function Sparkline({ values, width = 72, height = 22 }: { values: number[]; width?: number; height?: number }) {
  if (values.length < 2) return null;
  const max = Math.max(...values, 0);
  if (max <= 0) return null;
  const step = width / (values.length - 1);
  const path = values
    .map((v, i) => `${i === 0 ? 'M' : 'L'}${(i * step).toFixed(1)},${(height - 2 - (v / max) * (height - 4)).toFixed(1)}`)
    .join(' ');
  return (
    <svg viewBox={`0 0 ${width} ${height}`} width={width} height={height} aria-hidden="true" className="flex-shrink-0">
      <path d={path} fill="none" className="stroke-muted" strokeWidth="1.5" strokeLinejoin="round" strokeLinecap="round" />
    </svg>
  );
}
