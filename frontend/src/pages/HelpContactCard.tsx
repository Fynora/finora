import { Mail } from 'lucide-react';
import { SUPPORT_MAILTO } from '../lib/contact';

/** The "Still need help?" card at the foot of /help and of every Help article. */
export function HelpContactCard() {
  return (
    <div className="bg-card border border-border rounded-xl p-6 flex items-center gap-4 flex-wrap justify-between">
      <div>
        <p className="text-sm font-semibold text-ink mb-1">Still need help?</p>
        <p className="text-xs text-muted">Our support team is happy to help with anything not covered above.</p>
      </div>
      <a href={SUPPORT_MAILTO} className="bg-primary hover:bg-primary-dark text-on-primary text-xs font-semibold rounded-lg px-4 py-2.5 flex items-center gap-1.5 flex-shrink-0">
        <Mail size={14} /> Contact Support
      </a>
    </div>
  );
}
