import { Link } from 'react-router-dom';
import { Brain, ShieldCheck, Target, TrendingUp } from 'lucide-react';
import { PublicLayout, PublicSection } from '../components/PublicLayout';
import {
  GRIEVANCE_EMAIL,
  GRIEVANCE_MAILTO,
  LEGAL_NAME,
  REGISTERED_OFFICE_TEXT,
  SUPPORT_EMAIL,
  SUPPORT_MAILTO,
} from '../lib/contact';
import { playStoreLink } from '../lib/playStoreLink';
import { everywhere } from './landing/landing-config';

export default function About() {
  return (
    <PublicLayout
      title="About Fynora"
      subtitle="A financial operating system built for people who are tired of spreadsheets."
      description="Fynora is a personal finance platform that turns a bank or credit card statement into one organized view of your accounts, transactions, budgets and goals."
    >
      <PublicSection title="What Fynora Is">
        <p>
          Fynora is a personal finance platform that brings your accounts, transactions, budgets, goals, and
          investments into one place. Import a bank or credit card statement and Fynora detects the account,
          categorizes every transaction, and updates your dashboard instantly — no manual data entry required.
        </p>
      </PublicSection>

      <PublicSection title="Our Mission">
        <p>
          To make understanding your own money as easy as looking at a single screen — replacing scattered
          spreadsheets, bank apps, and mental math with one clear, trustworthy view of your financial life.
        </p>
      </PublicSection>

      <PublicSection title="Why Fynora Exists">
        <p>
          Most people's financial picture is spread across multiple bank apps, credit card statements, and a
          spreadsheet nobody updates consistently. Categorizing spend by hand is tedious, and by the time
          someone gets around to reviewing it, the insight has already lost its usefulness. Fynora exists to
          close that gap — turning a raw statement into an organized, understandable picture in seconds.
        </p>
      </PublicSection>

      <PublicSection title="Problems We Solve">
        <ul className="list-disc list-inside space-y-1.5 ml-1">
          <li>Manually re-typing transactions from a bank statement into a spreadsheet</li>
          <li>Losing track of which categories are over budget until the month is already over</li>
          <li>Having no single view across savings accounts, credit cards, and investments</li>
          <li>Automatic categorization tools that get something wrong once and never learn from the correction</li>
        </ul>
      </PublicSection>

      <div className="grid sm:grid-cols-2 gap-6 mb-10">
        {[
          { icon: <Target size={18} />, title: 'Product Vision', body: 'A single financial command center — not another expense tracker, but a platform that grows into budgeting, goals, investments, and AI-driven insight together.' },
          { icon: <ShieldCheck size={18} />, title: 'Security-First Philosophy', body: 'Hashed passwords, rotating refresh tokens, and server-verified sessions on every request. Security decisions are made before feature decisions, not after.' },
          { icon: <Brain size={18} />, title: 'AI-Powered Finance', body: 'Categorization is always a suggestion, never a silent final decision — and every correction you make teaches the system for next time.' },
          { icon: <TrendingUp size={18} />, title: 'Future Roadmap', body: 'A financial assistant, richer investment tracking, and deeper AI-driven recommendations are all active areas of development.' },
        ].map((item) => (
          <div key={item.title} className="bg-card border border-border rounded-xl p-5">
            <div className="w-9 h-9 rounded-lg bg-primary/10 text-primary flex items-center justify-center mb-3">{item.icon}</div>
            <h3 className="font-semibold text-ink text-sm mb-1.5">{item.title}</h3>
            <p className="text-xs text-muted leading-relaxed">{item.body}</p>
          </div>
        ))}
      </div>

      {/* Who is behind Fynora, in crawlable text. Every fact here is one the site already states, read
          from the same constants: the legal entity and registered office (Contact, Terms, Privacy),
          the app's availability (the homepage), and the two public mailboxes (Contact). The same
          entity is the Organization in this page's structured data. */}
      <PublicSection title="The Company">
        <p>
          Fynora is made by {LEGAL_NAME}, a limited liability partnership registered in India, with its
          registered office at {REGISTERED_OFFICE_TEXT}.
        </p>
        <p>
          {everywhere.nativeNote}{' '}
          <a href={playStoreLink('/about', 'text')} className="text-primary hover:underline">Fynora on Google Play</a>.
        </p>
        <p>
          For help, see the <Link to="/help" className="text-primary hover:underline">Help Center</Link> or
          email <a href={SUPPORT_MAILTO} className="text-primary hover:underline">{SUPPORT_EMAIL}</a>. For
          complaints about how your personal data is handled, write to our Grievance Officer at{' '}
          <a href={GRIEVANCE_MAILTO} className="text-primary hover:underline">{GRIEVANCE_EMAIL}</a>. More on
          the <Link to="/contact" className="text-primary hover:underline">Contact</Link> page.
        </p>
      </PublicSection>
    </PublicLayout>
  );
}
