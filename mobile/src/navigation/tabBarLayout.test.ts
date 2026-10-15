import {
  PILL_HEIGHT, PLUS_SIZE, TAB_BAR_CLEARANCE, TAB_LABEL_MAX_SCALE,
  pillBottomGap, pillCoverHeight, pillFrame, pillHideDuration,
} from './tabBarLayout';

describe('tabBarLayout', () => {
  it('holds the approved sizes', () => {
    expect(PILL_HEIGHT).toBe(64);
    expect(PLUS_SIZE).toBe(46);
    expect(TAB_LABEL_MAX_SCALE).toBe(1.3);
    expect(TAB_BAR_CLEARANCE).toBe(12);
  });

  it('sits just above the home indicator, and 12 pt up on a phone without one', () => {
    expect(pillBottomGap(34)).toBe(28);
    expect(pillBottomGap(0)).toBe(12);
    // An inset smaller than 18 would put the pill under 12 pt; the floor holds.
    expect(pillBottomGap(16)).toBe(12);
  });

  it('covers its own height plus its bottom gap', () => {
    expect(pillCoverHeight(34)).toBe(92);
    expect(pillCoverHeight(0)).toBe(76);
  });

  it('is 16 pt from each side on a phone', () => {
    expect(pillFrame(393, 34)).toEqual({ left: 16, width: 361, bottom: 28, height: 64 });
    expect(pillFrame(320, 0)).toEqual({ left: 16, width: 288, bottom: 12, height: 64 });
  });

  it('stops at 480 pt and centres on a wide screen', () => {
    expect(pillFrame(1024, 20)).toEqual({ left: 272, width: 480, bottom: 14, height: 64 });
    // Exactly at the cap: 480 + 2 x 16.
    expect(pillFrame(512, 0)).toEqual({ left: 16, width: 480, bottom: 12, height: 64 });
  });

  it('slides in 200 ms, at once under Reduce Motion', () => {
    expect(pillHideDuration(false)).toBe(200);
    expect(pillHideDuration(true)).toBe(0);
  });
});
