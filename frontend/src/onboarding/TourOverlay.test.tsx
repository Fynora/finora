import { render, screen, fireEvent } from '@testing-library/react';
import { describe, it, expect, vi } from 'vitest';
import { TourOverlay } from './TourOverlay';
import type { TourStep } from './tourSteps';

const STEPS: TourStep[] = [
  { targetSelector: '[data-tour="a"]', title: 'Step A', body: 'Body A' },
  { targetSelector: '[data-tour="b"]', title: 'Step B', body: 'Body B' },
];

function renderWithTargets(steps: TourStep[], onFinish = vi.fn(), onSkip = vi.fn()) {
  document.body.innerHTML = '<div data-tour="a"></div><div data-tour="b"></div>';
  return render(<TourOverlay steps={steps} onFinish={onFinish} onSkip={onSkip} />);
}

describe('TourOverlay', () => {
  it('shows the first step title on mount', () => {
    renderWithTargets(STEPS);
    expect(screen.getByText('Step A')).toBeInTheDocument();
  });

  it('advances to the next step on Next', () => {
    renderWithTargets(STEPS);
    fireEvent.click(screen.getByRole('button', { name: 'Next' }));
    expect(screen.getByText('Step B')).toBeInTheDocument();
  });

  it('calls onFinish after Next on the last step', () => {
    const onFinish = vi.fn();
    renderWithTargets(STEPS, onFinish);
    fireEvent.click(screen.getByRole('button', { name: 'Next' }));
    fireEvent.click(screen.getByRole('button', { name: 'Finish' }));
    expect(onFinish).toHaveBeenCalled();
  });

  it('calls onSkip from Skip at any step', () => {
    const onSkip = vi.fn();
    renderWithTargets(STEPS, vi.fn(), onSkip);
    fireEvent.click(screen.getByText('Skip'));
    expect(onSkip).toHaveBeenCalled();
  });

  it('goes back to the previous step on Back', () => {
    renderWithTargets(STEPS);
    fireEvent.click(screen.getByRole('button', { name: 'Next' }));
    fireEvent.click(screen.getByRole('button', { name: 'Back' }));
    expect(screen.getByText('Step A')).toBeInTheDocument();
  });

  it('does not show a Back button on the first step', () => {
    renderWithTargets(STEPS);
    expect(screen.queryByRole('button', { name: 'Back' })).not.toBeInTheDocument();
  });

  describe('viewport-boundary clamping', () => {
    // Bug fix regression coverage: a target near the bottom/right edge of the viewport used to
    // push the tooltip card off-screen (positioned at `rect.bottom + 12`/`rect.left` unconditionally,
    // with nothing to bring it back since the card is pinned to the viewport, not the document).
    // jsdom's own getBoundingClientRect() always returns zeros, so these tests stub it per-element
    // to give the target and the card real-looking dimensions to clamp against.
    function mockRect(el: Element, rect: Partial<DOMRect>) {
      vi.spyOn(el, 'getBoundingClientRect').mockReturnValue({
        top: 0, left: 0, right: 0, bottom: 0, width: 0, height: 0, x: 0, y: 0, toJSON() {},
        ...rect,
      });
    }

    it('flips the card above the target when placing it below would overflow the viewport', () => {
      window.innerHeight = 700;
      window.innerWidth = 1200;
      renderWithTargets(STEPS);
      const target = document.querySelector('[data-tour="a"]')!;
      // Near the very bottom of a 700px-tall viewport -- rect.bottom + 12 would place the card at
      // y=672, and a ~150px-tall card would run to y=822, well past the 700px viewport.
      mockRect(target, { top: 630, bottom: 660, left: 100, right: 300, width: 200, height: 30 });
      const card = screen.getByText('Step A').closest('div')!;
      mockRect(card, { width: 280, height: 150 });

      fireEvent.click(screen.getByRole('button', { name: 'Next' }));
      fireEvent.click(screen.getByRole('button', { name: 'Back' }));

      const style = (screen.getByText('Step A').closest('div') as HTMLElement).style;
      // Flipped above the 630px target top, not below its 660px bottom.
      expect(parseFloat(style.top)).toBeLessThan(630);
      expect(parseFloat(style.top)).toBeGreaterThanOrEqual(12);
    });

    it('clamps the card horizontally so it never extends past the right edge of the viewport', () => {
      window.innerHeight = 900;
      window.innerWidth = 1200;
      renderWithTargets(STEPS);
      const target = document.querySelector('[data-tour="a"]')!;
      // Left edge near the right side of a 1200px-wide viewport -- a 280px-wide card placed at
      // rect.left would run to x=1450, well past the viewport.
      mockRect(target, { top: 100, bottom: 130, left: 1150, right: 1180, width: 30, height: 30 });
      const card = screen.getByText('Step A').closest('div')!;
      mockRect(card, { width: 280, height: 150 });

      fireEvent.click(screen.getByRole('button', { name: 'Next' }));
      fireEvent.click(screen.getByRole('button', { name: 'Back' }));

      const style = (screen.getByText('Step A').closest('div') as HTMLElement).style;
      expect(parseFloat(style.left) + 280).toBeLessThanOrEqual(1200 - 12);
    });
  });
});
