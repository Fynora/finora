import { render } from '@testing-library/react';
import { MemoryRouter } from 'react-router-dom';
import { afterEach, describe, expect, it, vi } from 'vitest';
import Landing from './Landing';

describe('Landing — Hero-visibility observer', () => {
  afterEach(() => {
    vi.restoreAllMocks();
  });

  it('drives overHero via an IntersectionObserver watching the Hero wrapper, rootMargin-adjusted for the nav height', () => {
    // The page already uses IntersectionObserver elsewhere (every section's own scroll-reveal),
    // so this asserts on the SPECIFIC instance this feature creates -- identified by its
    // rootMargin, which no other observer on the page uses -- rather than a raw call count.
    //
    // A plain class, not vi.fn(...) -- vitest 4 changed vi.fn()'s mock functions to no longer be
    // usable as constructors (`new` on one throws "is not a constructor"), and every call site
    // here constructs its IntersectionObserver with `new`. Matches the real class
    // src/test/setup.ts already uses for the same reason (NoopIntersectionObserver).
    type Options = IntersectionObserverInit | undefined;
    const heroObserveSpy = vi.fn();
    const OriginalIO = window.IntersectionObserver;
    class MockIntersectionObserver {
      constructor(_callback: IntersectionObserverCallback, options: Options) {
        const isHeroObserver = options?.rootMargin?.includes('px 0px 0px 0px') && options.rootMargin !== '0px 0px 0px 0px';
        return {
          observe: isHeroObserver ? heroObserveSpy : vi.fn(),
          unobserve: vi.fn(),
          disconnect: vi.fn(),
          takeRecords: () => [],
        } as unknown as IntersectionObserver;
      }
    }
    window.IntersectionObserver = MockIntersectionObserver as unknown as typeof IntersectionObserver;

    render(
      <MemoryRouter>
        <Landing />
      </MemoryRouter>
    );

    expect(heroObserveSpy).toHaveBeenCalledTimes(1);

    window.IntersectionObserver = OriginalIO;
  });
});

describe('Landing — reframed running order', () => {
  it('runs proof, capabilities, Ask Fyn, trust, security, pricing, FAQ in that order, with a trust strip and no upgrade ladder', () => {
    const { container } = render(
      <MemoryRouter>
        <Landing />
      </MemoryRouter>
    );

    const wanted = ['how', 'features', 'ask-fyn', 'trust', 'security', 'pricing', 'faq'];
    const present = Array.from(container.querySelectorAll('section[id]'))
      .map((el) => el.id)
      .filter((id) => wanted.includes(id));
    expect(present).toEqual(wanted);

    expect(container.querySelector('#upgrade')).toBeNull();
    expect(container.querySelector('section[aria-label="Trust"]')).not.toBeNull();
  });

  it('puts no colour band between two sections of the same tone', () => {
    // Pricing and Faq are both `tone="alt"`. A white band between them showed in the browser as a
    // pale stripe across two identical surfaces (measured 2026-09-21).
    const { container } = render(
      <MemoryRouter>
        <Landing />
      </MemoryRouter>
    );

    expect(container.querySelector('#pricing')?.nextElementSibling).toBe(container.querySelector('#faq'));
  });
});

describe('Landing — fragment in the URL at mount', () => {
  // jsdom has no layout and does not implement scrollIntoView, so each case installs its own.
  const proto = Element.prototype as { scrollIntoView?: Element['scrollIntoView'] };
  const original = proto.scrollIntoView;

  afterEach(() => {
    proto.scrollIntoView = original;
    window.location.hash = '';
  });

  function renderAt(hash: string) {
    const scrolled: string[] = [];
    proto.scrollIntoView = function (this: Element) {
      scrolled.push(this.id);
    };
    window.location.hash = hash;
    render(
      <MemoryRouter>
        <Landing />
      </MemoryRouter>
    );
    return scrolled;
  }

  it('scrolls to the section the fragment names, which did not exist when the browser first looked', () => {
    // "#how" is what the hero button in the prerendered first frame sets when it is tapped early.
    expect(renderAt('#how')).toEqual(['how']);
  });

  it('does nothing without a fragment, or for one that names no element or is not valid encoding', () => {
    expect(renderAt('')).toEqual([]);
    expect(renderAt('#access_token=abc')).toEqual([]);
    expect(() => renderAt('#%E0%A4%A')).not.toThrow();
  });
});
