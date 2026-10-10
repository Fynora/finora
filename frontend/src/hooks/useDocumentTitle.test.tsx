import { render } from '@testing-library/react';
import { afterEach, beforeEach, describe, expect, it } from 'vitest';
import { useDocumentTitle } from './useDocumentTitle';

const SEARCH_TITLE = 'Bank statement analyzer for Indian banks and cards — Fynora';

function Probe({ title }: { title: string }) {
  useDocumentTitle(title);
  return null;
}

describe('useDocumentTitle', () => {
  beforeEach(() => {
    document.title = SEARCH_TITLE;
  });
  afterEach(() => {
    document.title = SEARCH_TITLE;
  });

  it("replaces index.html's search-result title while mounted and restores it on unmount", () => {
    const { unmount } = render(<Probe title="Fynora" />);
    expect(document.title).toBe('Fynora');
    unmount();
    expect(document.title).toBe(SEARCH_TITLE);
  });

  it('follows a title change and still restores the original, not the intermediate one', () => {
    const { rerender, unmount } = render(<Probe title="One" />);
    rerender(<Probe title="Two" />);
    expect(document.title).toBe('Two');
    unmount();
    expect(document.title).toBe(SEARCH_TITLE);
  });
});
