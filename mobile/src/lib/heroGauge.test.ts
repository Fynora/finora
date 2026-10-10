import { GAUGE_RADIUS, GAUGE_SIZE, gaugeArcPath } from './heroGauge';

const nums = (d: string) => d.match(/-?\d+(\.\d+)?/g)!.map(Number);

describe('gaugeArcPath', () => {
  it('draws nothing for a score of zero or below', () => {
    expect(gaugeArcPath(0)).toBe('');
    expect(gaugeArcPath(-10)).toBe('');
  });

  it('starts at the bottom-left of the dial', () => {
    const [x, y] = nums(gaugeArcPath(50));
    expect(x).toBeLessThan(GAUGE_SIZE / 2);
    expect(y).toBeGreaterThan(GAUGE_SIZE / 2);
  });

  it('uses the large-arc flag only once the sweep passes half a turn', () => {
    // M x y A r r 0 <large> 1 x y  ->  the sixth number is the large-arc flag
    expect(nums(gaugeArcPath(60))[5]).toBe(0); // 162 degrees
    expect(nums(gaugeArcPath(70))[5]).toBe(1); // 189 degrees
  });

  it('ends at the bottom-right for a full score, and never beyond it', () => {
    const full = nums(gaugeArcPath(100));
    const over = nums(gaugeArcPath(140));
    expect(full[7]).toBeGreaterThan(GAUGE_SIZE / 2);
    expect(full[8]).toBeGreaterThan(GAUGE_SIZE / 2);
    expect(over.slice(7)).toEqual(full.slice(7));
  });

  it('keeps every point on the dial radius', () => {
    const [x, y] = nums(gaugeArcPath(33)).slice(7);
    expect(Math.hypot(x - GAUGE_SIZE / 2, y - GAUGE_SIZE / 2)).toBeCloseTo(GAUGE_RADIUS, 5);
  });
});
