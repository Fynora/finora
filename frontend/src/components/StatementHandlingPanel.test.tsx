import { fireEvent, render, screen, within } from '@testing-library/react';
import { MemoryRouter } from 'react-router-dom';
import { readFileSync } from 'node:fs';
import { dirname, resolve } from 'node:path';
import { fileURLToPath } from 'node:url';
import { describe, expect, it } from 'vitest';
import StatementHandlingPanel from './StatementHandlingPanel';

function renderPanel() {
  return render(
    <MemoryRouter>
      <StatementHandlingPanel />
    </MemoryRouter>,
  );
}

// Spec §5.2, verbatim, in this order. Every line was checked against the code before shipping (PR).
const LINES = [
  'Fynora reads it with its own rules. It is not sent to AI services.',
  'The original file is kept in your account, so you can download it or have it read again later.',
  'You check every row before anything is saved. If you cancel, nothing is added.',
  "If it can't be read automatically, our team may open it to fix the problem. Every opening needs a written reason, is logged, and you are told.",
  'You can delete a statement any time from Statement History. Its file is deleted from our storage 90 days later.',
  'A PDF password is saved only if you choose, and is stored encrypted.',
];

describe('StatementHandlingPanel', () => {
  it('closedByDefault', () => {
    renderPanel();

    const toggle = screen.getByRole('button', { name: 'What happens to my statement?' });
    expect(toggle).toHaveAttribute('aria-expanded', 'false');
    expect(screen.queryByText(/its own rules/)).not.toBeInTheDocument();
  });

  it('opensAndShowsSevenItems', () => {
    renderPanel();

    fireEvent.click(screen.getByRole('button', { name: 'What happens to my statement?' }));

    expect(screen.getByRole('button', { name: 'What happens to my statement?' })).toHaveAttribute('aria-expanded', 'true');
    const items = within(screen.getByTestId('statement-handling-list')).getAllByRole('listitem');
    expect(items).toHaveLength(7);
    LINES.forEach((line, i) => expect(items[i]).toHaveTextContent(line));
  });

  it('puts the lead of each line in bold', () => {
    renderPanel();
    fireEvent.click(screen.getByRole('button', { name: 'What happens to my statement?' }));

    expect(screen.getByText('Fynora reads it with its own rules.').tagName).toBe('STRONG');
    expect(screen.getByText('A PDF password is saved only if you choose,').tagName).toBe('STRONG');
  });

  it('linksToPrivacy', () => {
    renderPanel();
    fireEvent.click(screen.getByRole('button', { name: 'What happens to my statement?' }));

    expect(screen.getByRole('link', { name: 'Read the full privacy policy' }))
      .toHaveAttribute('href', '/privacy#uploaded-statements');
  });

  it('closes again', () => {
    renderPanel();
    const toggle = screen.getByRole('button', { name: 'What happens to my statement?' });
    fireEvent.click(toggle);
    fireEvent.click(toggle);

    expect(toggle).toHaveAttribute('aria-expanded', 'false');
    expect(screen.queryByTestId('statement-handling-list')).not.toBeInTheDocument();
  });

  // Read as text, not imported: the web CI job installs no mobile dependencies.
  it('says the same lines as the mobile panel', () => {
    const here = dirname(fileURLToPath(import.meta.url));
    const strings = (p: string) =>
      [...readFileSync(resolve(here, p), 'utf8').matchAll(/(lead|rest): (['"])(.*?)\2/g)].map((m) => m[3]);
    const web = strings('./StatementHandlingPanel.tsx');
    expect(web).toHaveLength(12);
    expect(strings('../../../mobile/src/components/StatementHandlingPanel.tsx')).toEqual(web);
  });
});
