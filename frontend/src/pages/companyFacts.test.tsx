import { render, screen, within } from '@testing-library/react';
import { MemoryRouter } from 'react-router-dom';
import { describe, expect, it } from 'vitest';
import About from './About';
import Contact from './Contact';
import Privacy from './Privacy';
import Terms from './Terms';
import { everywhere } from './landing/landing-config';
import {
  GRIEVANCE_EMAIL,
  LEGAL_NAME,
  REGISTERED_OFFICE,
  REGISTERED_OFFICE_TEXT,
  SUPPORT_EMAIL,
} from '../lib/contact';
import { PLAY_STORE_URL } from '../lib/openInApp';

function textOf(Page: React.ComponentType): string {
  const { container, unmount } = render(<MemoryRouter><Page /></MemoryRouter>);
  const text = (container.textContent ?? '').replace(/\s+/g, ' ');
  unmount();
  return text;
}

describe('the registered office', () => {
  it('is the address the pages printed before it was centralised, character for character', () => {
    // The pages typed this inline until it moved to contact.ts. It is a legal fact, so a refactor
    // of the constant must not change what the pages say.
    expect(REGISTERED_OFFICE_TEXT).toBe('463, Sita Ram Compound, Chaman Ganj, Sipri Bazaar, Jhansi, Uttar Pradesh, 284003, India');
    expect(LEGAL_NAME).toBe('Fynora Technovation LLP');
    expect(REGISTERED_OFFICE.addressCountry).toBe('IN');
  });

  it('is printed from the constant, in the same sentences, on Contact, Terms and Privacy', () => {
    expect(textOf(Contact)).toContain(`Fynora is operated by ${LEGAL_NAME}, registered at ${REGISTERED_OFFICE_TEXT}.`);
    expect(textOf(Privacy)).toContain(`Fynora is operated by ${LEGAL_NAME}, registered at ${REGISTERED_OFFICE_TEXT}. Under the DPDP Act`);
    expect(textOf(Terms)).toContain(
      `Fynora is operated by ${LEGAL_NAME}, a limited liability partnership registered in India, with its registered office at ${REGISTERED_OFFICE_TEXT}. References`,
    );
  });
});

describe('About: The Company', () => {
  it('names the legal entity and its registered office, as Terms does', () => {
    render(<MemoryRouter><About /></MemoryRouter>);
    expect(screen.getByRole('heading', { level: 2, name: 'The Company' })).toBeInTheDocument();
    const text = (document.body.textContent ?? '').replace(/\s+/g, ' ');
    expect(text).toContain(
      `Fynora is made by ${LEGAL_NAME}, a limited liability partnership registered in India, with its registered office at ${REGISTERED_OFFICE_TEXT}.`,
    );
  });

  it("states the apps' availability in the homepage's own words, with the Play listing", () => {
    render(<MemoryRouter><About /></MemoryRouter>);
    expect(document.body.textContent).toContain(everywhere.nativeNote);
    expect(screen.getByRole('link', { name: 'Fynora on Google Play' })).toHaveAttribute('href', PLAY_STORE_URL);
  });

  it('gives the support and grievance mailboxes, and links Help and Contact', () => {
    render(<MemoryRouter><About /></MemoryRouter>);
    const section = within(screen.getByRole('heading', { level: 2, name: 'The Company' }).closest('section')!);
    expect(section.getByRole('link', { name: SUPPORT_EMAIL })).toHaveAttribute('href', `mailto:${SUPPORT_EMAIL}`);
    expect(section.getByRole('link', { name: GRIEVANCE_EMAIL })).toHaveAttribute('href', `mailto:${GRIEVANCE_EMAIL}`);
    expect(section.getByRole('link', { name: 'Help Center' })).toHaveAttribute('href', '/help');
    expect(section.getByRole('link', { name: 'Contact' })).toHaveAttribute('href', '/contact');
  });
});
