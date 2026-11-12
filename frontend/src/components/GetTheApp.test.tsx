import { describe, expect, it } from 'vitest';
import { render, screen, within } from '@testing-library/react';
import { renderToStaticMarkup } from 'react-dom/server';
import { MemoryRouter, StaticRouter } from 'react-router-dom';
import { GetTheApp, PLAY_BADGE_ALT, PLAY_LEGAL, PLAY_QR_ALT } from './GetTheApp';
import { playQrPath, playStoreLink } from '../lib/playStoreLink';
import { BankPageView } from '../pages/BankPage';
import { HelpArticleView } from '../pages/HelpArticle';
import { StatementCodeView } from '../pages/StatementCodePage';
import { BANK_PAGES } from '../pages/bankPages';
import { HELP_ARTICLES, helpArticlePath } from '../pages/helpArticles';
import { STATEMENT_CODES, statementCodePath } from '../pages/statementCodes';
import { bankPagePath } from '../pages/bankPages';

function renderAt(path: string, ui: React.ReactNode) {
  return render(<MemoryRouter initialEntries={[path]}>{ui}</MemoryRouter>);
}

const BANK = BANK_PAGES[0];
const BANK_PATH = bankPagePath(BANK);

describe('GetTheApp', () => {
  it("links Google's badge to the Play listing with this page's referrer, in a new tab", () => {
    renderAt(BANK_PATH, <GetTheApp variant="full" placement="badge_bottom" />);
    const link = screen.getByRole('link', { name: PLAY_BADGE_ALT });
    expect(link).toHaveAttribute('href', playStoreLink(BANK_PATH, 'badge_bottom'));
    expect(link).toHaveAttribute('target', '_blank');
    expect(link).toHaveAttribute('rel', 'noopener noreferrer');
  });

  it('sizes and lazy-loads the badge and the QR code so neither moves the page or delays its text', () => {
    renderAt(BANK_PATH, <GetTheApp variant="full" placement="badge_bottom" />);
    const badge = screen.getByRole('img', { name: PLAY_BADGE_ALT });
    expect(badge).toHaveAttribute('width', '155');
    expect(badge).toHaveAttribute('height', '60');
    expect(badge).toHaveAttribute('loading', 'lazy');
    const qr = screen.getByRole('img', { name: PLAY_QR_ALT });
    expect(qr).toHaveAttribute('src', playQrPath(BANK_PATH));
    expect(qr).toHaveAttribute('width', '128');
    expect(qr).toHaveAttribute('height', '128');
    expect(qr).toHaveAttribute('loading', 'lazy');
    // A phone cannot scan its own screen: the code is for tablets and computers.
    expect(qr).toHaveClass('hidden', 'md:block');
    expect(screen.getByText(PLAY_LEGAL)).toBeInTheDocument();
  });

  it('compact is one line and the badge, with no QR code', () => {
    renderAt('/help/x', <GetTheApp variant="compact" placement="badge_top" />);
    expect(screen.queryByRole('img', { name: PLAY_QR_ALT })).toBeNull();
    expect(screen.getByRole('link', { name: PLAY_BADGE_ALT })).toHaveAttribute('href', playStoreLink('/help/x', 'badge_top'));
  });

  it("a bank page shows one QR code per width: the bottom card's below laptop width, the sidebar's above", () => {
    renderAt(BANK_PATH, <BankPageView page={BANK} />);
    const qrs = screen.getAllByRole('img', { name: PLAY_QR_ALT });
    expect(qrs).toHaveLength(2);
    expect(qrs[0]).toHaveClass('hidden', 'md:block', 'lg:hidden');
    expect(qrs[1].closest('.lg\\:block')).not.toBeNull();
  });

  it('sidebar has the QR code and the badge', () => {
    renderAt(BANK_PATH, <GetTheApp variant="sidebar" placement="sidebar" />);
    expect(screen.getByRole('img', { name: PLAY_QR_ALT })).toHaveAttribute('src', playQrPath(BANK_PATH));
    expect(screen.getByRole('link', { name: PLAY_BADGE_ALT })).toHaveAttribute('href', playStoreLink(BANK_PATH, 'sidebar'));
  });
});

describe('Play links on the search pages', () => {
  it('a bank page links near the top, at the bottom and in the desktop sidebar, each attributed apart', () => {
    renderAt(BANK_PATH, <BankPageView page={BANK} />);
    const hrefs = screen.getAllByRole('link', { name: PLAY_BADGE_ALT }).map((a) => a.getAttribute('href'));
    expect(hrefs).toEqual([
      playStoreLink(BANK_PATH, 'badge_top'),
      playStoreLink(BANK_PATH, 'badge_bottom'),
      playStoreLink(BANK_PATH, 'sidebar'),
    ]);
  });

  it('a bank page puts the top badge right after the answer, before the download steps', () => {
    renderAt(BANK_PATH, <BankPageView page={BANK} />);
    const top = screen.getAllByRole('group', { name: 'Get Fynora on Android' })[0];
    const steps = screen.getByRole('heading', { name: new RegExp(`^How to download your ${BANK.bankName}`) });
    expect(top.compareDocumentPosition(steps) & Node.DOCUMENT_POSITION_FOLLOWING).toBeTruthy();
    expect(screen.getByText(BANK.answer).compareDocumentPosition(top) & Node.DOCUMENT_POSITION_FOLLOWING).toBeTruthy();
  });

  it('a Help answer and a statement code put the badge right after the answer', () => {
    const article = HELP_ARTICLES[0];
    const help = renderAt(helpArticlePath(article), <HelpArticleView article={article} />);
    const helpBlock = within(help.container).getByRole('group', { name: 'Get Fynora on Android' });
    expect(within(helpBlock).getByRole('link', { name: PLAY_BADGE_ALT })).toHaveAttribute(
      'href', playStoreLink(helpArticlePath(article), 'badge_top'));
    expect(help.container.querySelector('article')!.nextElementSibling).toBe(helpBlock);
    help.unmount();

    const code = STATEMENT_CODES[0];
    const glossary = renderAt(statementCodePath(code), <StatementCodeView code={code} />);
    const answer = within(glossary.container).getByText(code.answer);
    const codeBlock = within(glossary.container).getByRole('group', { name: 'Get Fynora on Android' });
    expect(answer.nextElementSibling).toBe(codeBlock);
    expect(within(codeBlock).getByRole('link', { name: PLAY_BADGE_ALT })).toHaveAttribute(
      'href', playStoreLink(statementCodePath(code), 'badge_top'));
  });

  it('server-renders the same links the browser renders (the prerendered HTML carries them)', () => {
    const html = renderToStaticMarkup(<StaticRouter location={BANK_PATH}><BankPageView page={BANK} /></StaticRouter>);
    for (const placement of ['badge_top', 'badge_bottom', 'sidebar'] as const) {
      expect(html).toContain(`href="${playStoreLink(BANK_PATH, placement).replace(/&/g, '&amp;')}"`);
    }
    expect(html).toContain(`src="${playQrPath(BANK_PATH)}"`);
  });
});
