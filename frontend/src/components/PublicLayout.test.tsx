import { render } from '@testing-library/react';
import { MemoryRouter } from 'react-router-dom';
import { afterEach, beforeEach, describe, expect, it } from 'vitest';
import { PublicLayout } from './PublicLayout';
import Terms from '../pages/Terms';
import RefundPolicy from '../pages/RefundPolicy';

const APP_TITLE = 'Fynora — Personal finance, simplified';

describe('PublicLayout document title', () => {
  beforeEach(() => {
    document.title = APP_TITLE;
  });
  afterEach(() => {
    document.title = APP_TITLE;
  });

  it('titles the tab after the page, not the one title every page used to share', () => {
    render(
      <MemoryRouter>
        <PublicLayout title="Terms & Conditions">body</PublicLayout>
      </MemoryRouter>
    );
    expect(document.title).toBe('Terms & Conditions — Fynora');
  });

  it('restores the previous title when the page unmounts', () => {
    const { unmount } = render(
      <MemoryRouter>
        <PublicLayout title="Terms & Conditions">body</PublicLayout>
      </MemoryRouter>
    );
    unmount();
    expect(document.title).toBe(APP_TITLE);
  });

  it('follows a title change without stacking suffixes or losing the original', () => {
    const { rerender, unmount } = render(
      <MemoryRouter>
        <PublicLayout title="One">body</PublicLayout>
      </MemoryRouter>
    );
    rerender(
      <MemoryRouter>
        <PublicLayout title="Two">body</PublicLayout>
      </MemoryRouter>
    );
    expect(document.title).toBe('Two — Fynora');
    unmount();
    expect(document.title).toBe(APP_TITLE);
  });

  it('gives distinct titles to the real pages built on it', () => {
    const titleOf = (ui: React.ReactElement) => {
      const { unmount } = render(<MemoryRouter>{ui}</MemoryRouter>);
      const t = document.title;
      unmount();
      return t;
    };
    const terms = titleOf(<Terms />);
    const refunds = titleOf(<RefundPolicy />);
    expect(terms).not.toBe(APP_TITLE);
    expect(refunds).not.toBe(APP_TITLE);
    expect(terms).not.toBe(refunds);
  });
});
