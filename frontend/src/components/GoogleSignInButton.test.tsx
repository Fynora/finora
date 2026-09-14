import { describe, it, expect, vi, beforeEach, afterEach } from 'vitest';
import { render, waitFor } from '@testing-library/react';
import { GoogleSignInButton } from './GoogleSignInButton';
import { isGoogleLoginConfigured, loadGoogleIdentityServices } from '../lib/googleIdentity';

/**
 * Mocks lib/googleIdentity.ts wholesale rather than simulating a real <script> load -- that
 * module's own script-injection/caching/failure mechanics already have dedicated coverage in
 * googleIdentity.test.ts (including the module-scope caching that makes DOM-event simulation
 * order-sensitive across tests). This file's job is narrower: given whatever
 * loadGoogleIdentityServices resolves or rejects with, does the component wire it up correctly.
 */
vi.mock('../lib/googleIdentity', () => ({
  isGoogleLoginConfigured: vi.fn(),
  loadGoogleIdentityServices: vi.fn(),
}));

// jsdom implements neither ResizeObserver nor real layout, so a real one would never fire and
// getBoundingClientRect() would always read 0. This stub records the callback per observed
// element and lets each test fire it with whatever contentRect.width it wants to simulate --
// closest thing to controlling "what the browser measured" without a real layout engine.
//
// Keyed by [element, instance] rather than element alone, and disconnect() only drops THIS
// instance's own entries -- a real ResizeObserver.disconnect() only stops that one instance from
// observing, it doesn't affect other instances still watching other elements. A shared
// element-only map whose disconnect() cleared everything used to mask exactly this: the
// component's own renderedButtonResizeObserver disconnecting itself from inside its callback
// would, on the old stub, also silently wipe the unrelated outer `resizeObserver`'s registration
// on `target`, since both lived in the same map.
let resizeCallbacks: Map<Element, Set<ResizeObserverCallback>>;
function fireResize(el: Element, width: number) {
  resizeCallbacks.get(el)?.forEach((cb) => cb([{ contentRect: { width } } as ResizeObserverEntry], {} as ResizeObserver));
}

beforeEach(() => {
  vi.mocked(isGoogleLoginConfigured).mockReset();
  vi.mocked(loadGoogleIdentityServices).mockReset();
  resizeCallbacks = new Map();
  vi.stubGlobal('ResizeObserver', class {
    private observed = new Set<Element>();
    constructor(private callback: ResizeObserverCallback) {}
    observe(el: Element) {
      this.observed.add(el);
      if (!resizeCallbacks.has(el)) resizeCallbacks.set(el, new Set());
      resizeCallbacks.get(el)!.add(this.callback);
    }
    unobserve(el: Element) {
      this.observed.delete(el);
      resizeCallbacks.get(el)?.delete(this.callback);
    }
    disconnect() {
      this.observed.forEach((el) => resizeCallbacks.get(el)?.delete(this.callback));
      this.observed.clear();
    }
  });
});

afterEach(() => {
  vi.unstubAllEnvs();
});

describe('GoogleSignInButton', () => {
  it('renders nothing and never loads the script when unconfigured', () => {
    vi.mocked(isGoogleLoginConfigured).mockReturnValue(false);

    const { container } = render(
      <GoogleSignInButton text="signin_with" onCredential={vi.fn()} onError={vi.fn()} />
    );

    expect(container).toBeEmptyDOMElement();
    expect(loadGoogleIdentityServices).not.toHaveBeenCalled();
  });

  it('initializes GIS with the configured client id and renders the button at the observed width', async () => {
    vi.stubEnv('VITE_GOOGLE_LOGIN_CLIENT_ID', 'test-client-id.apps.googleusercontent.com');
    vi.mocked(isGoogleLoginConfigured).mockReturnValue(true);
    const initialize = vi.fn();
    const renderButton = vi.fn();
    vi.mocked(loadGoogleIdentityServices).mockResolvedValue({ initialize, renderButton } as any);

    const { container } = render(<GoogleSignInButton text="signup_with" onCredential={vi.fn()} onError={vi.fn()} />);

    await waitFor(() => expect(initialize).toHaveBeenCalledWith(
      expect.objectContaining({ client_id: 'test-client-id.apps.googleusercontent.com' })
    ));

    // Real ResizeObserver fires once as soon as observe() is called, reporting whatever width the
    // browser actually settled on -- this simulates that first callback.
    fireResize(container.querySelector('[aria-busy]')!, 288);

    expect(renderButton).toHaveBeenCalledWith(
      expect.any(HTMLElement),
      expect.objectContaining({ text: 'signup_with', theme: 'outline', width: '288', logo_alignment: 'center' }),
    );
  });

  it('re-renders at the new width when the container is resized', async () => {
    vi.stubEnv('VITE_GOOGLE_LOGIN_CLIENT_ID', 'test-client-id.apps.googleusercontent.com');
    vi.mocked(isGoogleLoginConfigured).mockReturnValue(true);
    const initialize = vi.fn();
    const renderButton = vi.fn();
    vi.mocked(loadGoogleIdentityServices).mockResolvedValue({ initialize, renderButton } as any);

    const { container } = render(<GoogleSignInButton text="signin_with" onCredential={vi.fn()} onError={vi.fn()} />);
    await waitFor(() => expect(initialize).toHaveBeenCalled());
    const target = container.querySelector('[aria-busy]')!;

    // Simulates exactly the production bug this fix closes: an early, too-narrow measurement
    // (the container hadn't finished laying out yet) followed by the real, settled width.
    fireResize(target, 107);
    fireResize(target, 304);

    expect(renderButton).toHaveBeenLastCalledWith(expect.any(HTMLElement), expect.objectContaining({ width: '304' }));
  });

  it('caps the rendered width at 400px, matching GIS documented max', async () => {
    vi.stubEnv('VITE_GOOGLE_LOGIN_CLIENT_ID', 'test-client-id.apps.googleusercontent.com');
    vi.mocked(isGoogleLoginConfigured).mockReturnValue(true);
    const initialize = vi.fn();
    const renderButton = vi.fn();
    vi.mocked(loadGoogleIdentityServices).mockResolvedValue({ initialize, renderButton } as any);

    const { container } = render(<GoogleSignInButton text="signin_with" onCredential={vi.fn()} onError={vi.fn()} />);
    await waitFor(() => expect(initialize).toHaveBeenCalled());

    fireResize(container.querySelector('[aria-busy]')!, 900);

    expect(renderButton).toHaveBeenCalledWith(expect.any(HTMLElement), expect.objectContaining({ width: '400' }));
  });

  it('hands the credential straight to onCredential when Google calls back', async () => {
    vi.stubEnv('VITE_GOOGLE_LOGIN_CLIENT_ID', 'test-client-id.apps.googleusercontent.com');
    vi.mocked(isGoogleLoginConfigured).mockReturnValue(true);
    const onCredential = vi.fn();
    const initialize = vi.fn();
    vi.mocked(loadGoogleIdentityServices).mockResolvedValue({ initialize, renderButton: vi.fn() } as any);

    render(<GoogleSignInButton text="signin_with" onCredential={onCredential} onError={vi.fn()} />);
    await waitFor(() => expect(initialize).toHaveBeenCalled());

    // Simulate Google's own button invoking the callback it was configured with.
    const { callback } = initialize.mock.calls[0][0];
    callback({ credential: 'a-real-looking-jwt' });

    expect(onCredential).toHaveBeenCalledWith('a-real-looking-jwt');
  });

  it('reports the iframe\'s own real rendered width via onRenderedWidth, not the requested width', async () => {
    vi.stubEnv('VITE_GOOGLE_LOGIN_CLIENT_ID', 'test-client-id.apps.googleusercontent.com');
    vi.mocked(isGoogleLoginConfigured).mockReturnValue(true);
    const initialize = vi.fn();
    // Real GIS inserts an iframe into the container renderButton() is called with; the requested
    // `width` param and the iframe's own eventual rendered width are two different numbers (see
    // the component's own comment) -- this mock creates the iframe so the test can drive that gap.
    const renderButton = vi.fn((container: HTMLElement) => {
      container.appendChild(document.createElement('iframe'));
    });
    vi.mocked(loadGoogleIdentityServices).mockResolvedValue({ initialize, renderButton } as any);
    const onRenderedWidth = vi.fn();

    const { container } = render(
      <GoogleSignInButton text="signin_with" onCredential={vi.fn()} onError={vi.fn()} onRenderedWidth={onRenderedWidth} />
    );
    await waitFor(() => expect(initialize).toHaveBeenCalled());

    fireResize(container.querySelector('[aria-busy]')!, 400);
    const iframe = container.querySelector('iframe')!;
    fireResize(iframe, 420);

    expect(onRenderedWidth).toHaveBeenCalledWith(420);
  });

  // Bug fix: GIS has switched which element it draws before -- production (app.fynora.net,
  // 2026-09) currently renders a plain `<div role="button">` instead of the `<iframe>` the test
  // above covers, and `querySelector('iframe')` alone found nothing for that shape, so this whole
  // correction silently never ran: onRenderedWidth never fired, the parent form never narrowed
  // off its seeded value, and Google's real (400px, hard-capped) button ended up visibly
  // narrower than Apple's uncapped full-width button next to it. This is the same gap as the
  // iframe test above, just for the shape GIS actually uses today.
  it('reports the rendered button\'s own real width via onRenderedWidth when GIS draws a div instead of an iframe', async () => {
    vi.stubEnv('VITE_GOOGLE_LOGIN_CLIENT_ID', 'test-client-id.apps.googleusercontent.com');
    vi.mocked(isGoogleLoginConfigured).mockReturnValue(true);
    const initialize = vi.fn();
    // Real GIS currently inserts a `role="button"` div (with its own inline `width:400px`, not an
    // iframe) into the container renderButton() is called with -- see GoogleSignInButton.tsx's
    // own comment for the live-production DOM this mirrors.
    const renderButton = vi.fn((container: HTMLElement) => {
      const btn = document.createElement('div');
      btn.setAttribute('role', 'button');
      container.appendChild(btn);
    });
    vi.mocked(loadGoogleIdentityServices).mockResolvedValue({ initialize, renderButton } as any);
    const onRenderedWidth = vi.fn();

    const { container } = render(
      <GoogleSignInButton text="signin_with" onCredential={vi.fn()} onError={vi.fn()} onRenderedWidth={onRenderedWidth} />
    );
    await waitFor(() => expect(initialize).toHaveBeenCalled());

    fireResize(container.querySelector('[aria-busy]')!, 420);
    const divButton = container.querySelector('[role="button"]')!;
    fireResize(divButton, 400);

    expect(onRenderedWidth).toHaveBeenCalledWith(400);
  });

  // Bug fix: reporting the rendered button's width on EVERY re-render created a real feedback
  // loop, live on production -- onRenderedWidth changes the parent's formWidth, which resizes
  // THIS component's own container (it's `w-full` of the form), which re-triggers the outer
  // resizeObserver, which re-requests a DIFFERENT width from Google, whose newly-drawn button
  // gets measured and reported again, and so on. Observed live: formWidth collapsing to 147px
  // while Google's own button (which won't shrink below its min-content) stayed at 169px, wider
  // than its own now-too-narrow container. This simulates exactly that cycle -- a second
  // container resize, mimicking the parent reacting to the first report -- and asserts the
  // second one never gets reported, breaking the loop before it can compound.
  it('reports the rendered width only once, even across multiple resize-and-redraw cycles', async () => {
    vi.stubEnv('VITE_GOOGLE_LOGIN_CLIENT_ID', 'test-client-id.apps.googleusercontent.com');
    vi.mocked(isGoogleLoginConfigured).mockReturnValue(true);
    const initialize = vi.fn();
    // A fresh `role="button"` div every call, matching real GIS's replaceChildren()+renderButton()
    // cycle -- each redraw is a genuinely new element, not the same one resized in place.
    const renderButton = vi.fn((container: HTMLElement) => {
      const btn = document.createElement('div');
      btn.setAttribute('role', 'button');
      container.appendChild(btn);
    });
    vi.mocked(loadGoogleIdentityServices).mockResolvedValue({ initialize, renderButton } as any);
    const onRenderedWidth = vi.fn();

    const { container } = render(
      <GoogleSignInButton text="signin_with" onCredential={vi.fn()} onError={vi.fn()} onRenderedWidth={onRenderedWidth} />
    );
    await waitFor(() => expect(initialize).toHaveBeenCalled());
    const target = container.querySelector('[aria-busy]')!;

    // First cycle: container settles at 400, Google's button reports back 147 (its own real,
    // narrower rendering) -- the one legitimate correction this mechanism exists to make.
    fireResize(target, 400);
    fireResize(container.querySelector('[role="button"]')!, 147);
    expect(onRenderedWidth).toHaveBeenCalledTimes(1);
    expect(onRenderedWidth).toHaveBeenCalledWith(147);

    // Second cycle: the container resizes AGAIN (simulating the parent's formWidth=147 update
    // shrinking this component's own `w-full` container) -- Google redraws a brand-new button at
    // the new requested width, but that new button's own eventual measurement must NOT be
    // reported again, or the cycle would repeat indefinitely.
    fireResize(target, 147);
    const allButtons = [...container.querySelectorAll('[role="button"]')];
    const secondButton = allButtons[allButtons.length - 1]!;
    fireResize(secondButton, 130);

    expect(renderButton).toHaveBeenCalledTimes(2);
    expect(onRenderedWidth).toHaveBeenCalledTimes(1);
  });

  it('reports onError when Google Identity Services fails to load', async () => {
    vi.stubEnv('VITE_GOOGLE_LOGIN_CLIENT_ID', 'test-client-id.apps.googleusercontent.com');
    vi.mocked(isGoogleLoginConfigured).mockReturnValue(true);
    vi.mocked(loadGoogleIdentityServices).mockRejectedValue(new Error('Failed to load Google Identity Services.'));
    const onError = vi.fn();

    render(<GoogleSignInButton text="signin_with" onCredential={vi.fn()} onError={onError} />);

    await waitFor(() => expect(onError).toHaveBeenCalledWith(
      'Sign in with Google is unavailable right now. Please try again later.'
    ));
  });
});
