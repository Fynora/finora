import { render } from '@testing-library/react-native';
import { HealthSparkline } from './HealthSparkline';

/**
 * Counts native "RNSVGPath" nodes in a react-test-renderer JSON tree (react-native-svg's
 * <Polyline> renders down to RNSVGPath natively, not a distinct polyline host type). Counting via
 * toJSON() rather than UNSAFE_getAllByProps: that query matches react-native-svg's internal
 * wrapper-component fiber layers too, over-counting each real <Polyline> by 2x -- verified by
 * inspecting the actual rendered tree, not assumed.
 */
function countSvgPaths(node: ReturnType<typeof render>['toJSON'] extends () => infer T ? T : never): number {
  if (!node || typeof node !== 'object') return 0;
  const self = (node as { type?: string }).type === 'RNSVGPath' ? 1 : 0;
  const children = (node as { children?: unknown[] }).children ?? [];
  return self + children.reduce((sum: number, c) => sum + countSvgPaths(c as never), 0);
}

describe('HealthSparkline', () => {
  it('draws one polyline per contiguous-month run, breaking across a gap', () => {
    const { toJSON } = render(
      <HealthSparkline
        color="#000000"
        points={[
          { yearMonth: '2026-04', score: 60 },
          { yearMonth: '2026-05', score: 65 },
          // Gap: 2026-06 missing.
          { yearMonth: '2026-07', score: 70 },
          { yearMonth: '2026-08', score: 72 },
        ]}
      />
    );
    // Two runs of >= 2 points each -- two separate polylines, never one line bridging the gap.
    expect(countSvgPaths(toJSON())).toBe(2);
  });

  it('renders nothing for fewer than 2 points', () => {
    const { queryByTestId } = render(<HealthSparkline color="#000000" points={[{ yearMonth: '2026-08', score: 72 }]} />);
    expect(queryByTestId('health-sparkline')).toBeNull();
  });
});
