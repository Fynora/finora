import { AccessibilityInfo, Animated, AppState } from 'react-native';
import { act, render, screen } from '@testing-library/react-native';
import { Shimmer, __activeShimmerCountForTests } from './Shimmer';
import { ThemeProvider } from '../../theme';

function renderShimmer(props: Partial<React.ComponentProps<typeof Shimmer>> = {}) {
  return render(
    <ThemeProvider>
      <Shimmer height={20} {...props} />
    </ThemeProvider>
  );
}

function flatStyle(style: unknown): Record<string, unknown> {
  return Array.isArray(style)
    ? Object.assign({}, ...(style as unknown[]).flat(Infinity).filter(Boolean))
    : (style as Record<string, unknown>);
}

// Shimmer deliberately hides itself from assistive technology (accessibilityElementsHidden +
// importantForAccessibility="no-hide-descendants") -- correct a11y behavior for a placeholder that
// carries no real information. RNTL excludes accessibility-hidden elements from every query by
// default (defaultIncludeHiddenElements: false), so every lookup here needs { hidden: true } to
// find it anyway. This is scoped per-query rather than a global test-setup config change, since
// flipping the default for the whole suite would silently affect every other test's hidden-element
// assumptions, not just this deliberately-hidden component's own tests.
describe('Shimmer', () => {
  it('renders a block sized to the given width and height', () => {
    renderShimmer({ width: 120, height: 20 });
    const block = screen.getByTestId('shimmer-block', { hidden: true });
    const style = flatStyle(block.props.style);
    expect(style.width).toBe(120);
    expect(style.height).toBe(20);
  });

  it('defaults to a full-width block with the theme border color', () => {
    renderShimmer();
    const style = flatStyle(screen.getByTestId('shimmer-block', { hidden: true }).props.style);
    expect(style.width).toBe('100%');
    // light.border from src/theme/palette.ts -- ThemeProvider defaults to 'system', which resolves
    // to light under the test runner's default (non-dark) color scheme.
    expect(style.backgroundColor).toBe('#E6EAF2');
  });

  it('is hidden from assistive technology, since it carries no information of its own', () => {
    renderShimmer();
    const block = screen.getByTestId('shimmer-block', { hidden: true });
    expect(block.props.accessibilityElementsHidden).toBe(true);
    expect(block.props.importantForAccessibility).toBe('no-hide-descendants');
  });

  it('accepts a custom testID so a composed skeleton can query it distinctly', () => {
    renderShimmer({ testID: 'skeleton-chart-bar' });
    expect(screen.getByTestId('skeleton-chart-bar', { hidden: true })).toBeTruthy();
    expect(screen.queryByTestId('shimmer-block', { hidden: true })).toBeNull();
  });

  // The spec drops the live-region announcement: a SkeletonGroup carries a static "Loading" label
  // instead, so a screen reader meets it once per group when it reaches it, not as an interruption.
  describe('screen readers', () => {
    it('never announces on its own', () => {
      const announce = jest.spyOn(AccessibilityInfo, 'announceForAccessibility');
      renderShimmer();
      expect(announce).not.toHaveBeenCalled();
    });
  });

  describe('shared pulse', () => {
    it('counts every mounted Shimmer and returns to zero when the last one unmounts', () => {
      const view = render(
        <ThemeProvider>
          <Shimmer height={20} />
          <Shimmer height={20} />
        </ThemeProvider>
      );
      expect(__activeShimmerCountForTests()).toBe(2);
      view.unmount();
      expect(__activeShimmerCountForTests()).toBe(0);
    });

    it('starts one loop however many Shimmers mount', async () => {
      jest.spyOn(AccessibilityInfo, 'isReduceMotionEnabled').mockResolvedValue(false);
      const loop = jest.spyOn(Animated, 'loop');
      render(
        <ThemeProvider>
          <Shimmer height={20} />
          <Shimmer height={20} />
          <Shimmer height={20} />
        </ThemeProvider>
      );
      await act(async () => {});
      expect(loop).toHaveBeenCalledTimes(1);
    });
  });
});

/**
 * The one animation in this app that has to ask about Reduce Motion itself. AnimatedNumber and
 * ChartReveal are Reanimated-based, and Reanimated defaults every animation to
 * `ReduceMotion.System`, so they already honour the setting for free. This loop is React Native's
 * own Animated API, which has no such behaviour -- and it is the worst offender of the three: an
 * indefinitely repeating pulse (exactly what the setting exists to suppress) that is on screen
 * during every loading state in the app, not for one 450ms transition.
 */
describe('Reduce Motion', () => {
  afterEach(() => jest.restoreAllMocks());

  it('does not start the pulse loop when the OS setting is on', async () => {
    jest.spyOn(AccessibilityInfo, 'isReduceMotionEnabled').mockResolvedValue(true);
    const loop = jest.spyOn(Animated, 'loop');

    renderShimmer();
    // The check is a native round trip, so the decision lands a microtask later.
    await act(async () => {});

    expect(loop).not.toHaveBeenCalled();
  });

  it('still renders a visible placeholder rather than a frozen invisible one', async () => {
    jest.spyOn(AccessibilityInfo, 'isReduceMotionEnabled').mockResolvedValue(true);

    renderShimmer();
    await act(async () => {});

    // Suppressing the motion must not suppress the block: it is still standing in for content.
    expect(screen.getByTestId('shimmer-block', { hidden: true })).toBeTruthy();
  });

  it('animates as before when the setting is off', async () => {
    jest.spyOn(AccessibilityInfo, 'isReduceMotionEnabled').mockResolvedValue(false);
    const loop = jest.spyOn(Animated, 'loop');

    renderShimmer();
    await act(async () => {});

    expect(loop).toHaveBeenCalled();
  });

  it('falls back to animating when the device cannot answer', async () => {
    // Failing toward the pre-existing behaviour beats a placeholder frozen at a fixed opacity,
    // which reads as a broken screen rather than a loading one.
    jest.spyOn(AccessibilityInfo, 'isReduceMotionEnabled').mockRejectedValue(new Error('nope'));
    const loop = jest.spyOn(Animated, 'loop');

    renderShimmer();
    await act(async () => {});

    expect(loop).toHaveBeenCalled();
  });
});

// Measured on the simulator, 2026-10-15: with a skeleton held on screen and the app in the
// background, the app's main thread took 209 samples in 8 s and its JS thread 83, against 29 and 1
// for the same app backgrounded with no skeleton -- the pulse kept running where nobody can see it.
describe('app in the background', () => {
  afterEach(() => jest.restoreAllMocks());

  it('stops the pulse while the app is in the background and resumes it on return', async () => {
    jest.spyOn(AccessibilityInfo, 'isReduceMotionEnabled').mockResolvedValue(false);
    const listeners: ((s: string) => void)[] = [];
    jest.spyOn(AppState, 'addEventListener').mockImplementation((_e, fn) => {
      listeners.push(fn as (s: string) => void);
      return { remove: jest.fn() } as never;
    });
    const loop = jest.spyOn(Animated, 'loop');
    const view = renderShimmer();
    await act(async () => {});
    const first = loop.mock.results[0].value as Animated.CompositeAnimation;
    const stop = jest.spyOn(first, 'stop');

    act(() => listeners.forEach((fn) => fn('background')));
    expect(stop).toHaveBeenCalled();

    act(() => listeners.forEach((fn) => fn('active')));
    await act(async () => {});
    expect(loop).toHaveBeenCalledTimes(2);
    view.unmount();
  });

  it('does not restart the pulse on return when nothing is loading any more', async () => {
    jest.spyOn(AccessibilityInfo, 'isReduceMotionEnabled').mockResolvedValue(false);
    const listeners: ((s: string) => void)[] = [];
    jest.spyOn(AppState, 'addEventListener').mockImplementation((_e, fn) => {
      listeners.push(fn as (s: string) => void);
      return { remove: jest.fn() } as never;
    });
    const loop = jest.spyOn(Animated, 'loop');
    const view = renderShimmer();
    await act(async () => {});
    act(() => listeners.forEach((fn) => fn('background')));
    view.unmount();

    act(() => listeners.forEach((fn) => fn('active')));
    await act(async () => {});
    expect(loop).toHaveBeenCalledTimes(1);
  });
});

describe('app in the background, then a new load', () => {
  afterEach(() => jest.restoreAllMocks());

  it('a skeleton mounted after returning pulses, even if the last one unmounted while backgrounded', async () => {
    jest.spyOn(AccessibilityInfo, 'isReduceMotionEnabled').mockResolvedValue(false);
    const listeners: ((s: string) => void)[] = [];
    jest.spyOn(AppState, 'addEventListener').mockImplementation((_e, fn) => {
      listeners.push(fn as (s: string) => void);
      return { remove: () => listeners.splice(listeners.indexOf(fn as (s: string) => void), 1) } as never;
    });
    const loop = jest.spyOn(Animated, 'loop');

    const first = renderShimmer();
    await act(async () => {});
    act(() => listeners.forEach((fn) => fn('background')));
    first.unmount();

    // Back in the foreground with nothing listening (no 'active' event reached the pulse); a new
    // first load starts. AppState's own current state is not 'background' here.
    renderShimmer();
    await act(async () => {});
    expect(loop).toHaveBeenCalledTimes(2);
  });
});
