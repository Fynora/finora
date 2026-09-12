import { useMemo, useState } from 'react';
import { Search, Mail } from 'lucide-react';
import { PublicLayout } from '../components/PublicLayout';
import { SUPPORT_EMAIL, SUPPORT_MAILTO } from '../lib/contact';

interface HelpArticle {
  category: string;
  question: string;
  answer: string;
}

const ARTICLES: HelpArticle[] = [
  { category: 'Getting Started', question: 'What is Fynora?', answer: 'Fynora is a personal finance platform that imports your bank/card statements, categorizes transactions automatically, and gives you one dashboard across accounts, budgets, goals, and investments.' },
  { category: 'Getting Started', question: 'Do I need to connect my bank account?', answer: "No live bank connection is required. You export a CSV or PDF statement from your bank or card's own portal and upload it to Fynora — nothing requires your bank login credentials." },

  { category: 'Account Registration', question: 'What do I need to sign up?', answer: 'A full name, email address, mobile number, and a password of at least 8 characters. You\'ll verify your phone number with an OTP right after registering.' },
  { category: 'Account Registration', question: 'Can I use the same email or phone number twice?', answer: 'No — each email address and mobile number can only be registered to one account. If you see a "duplicate" error, that identifier is already in use.' },

  { category: 'Login & Authentication', question: 'Can I log in with my phone number instead of email?', answer: 'Yes. The sign-in field accepts either your email address or your registered mobile number — you don\'t need to remember which one you used to register.' },
  { category: 'Login & Authentication', question: 'What happens if I enter the wrong password too many times?', answer: 'After several consecutive failed attempts, the account is temporarily locked for a short period as a security measure, then unlocks automatically.' },
  { category: 'Login & Authentication', question: 'Why do I need to verify my phone number?', answer: "Phone verification confirms you have access to the number on file and is required before you can use the rest of the app — you'll be redirected to the verification screen until it's complete." },

  { category: 'Importing Statements', question: 'Which file formats are supported?', answer: 'CSV exports from your bank or card portal, and PDF statements — including password-protected ones your bank emails you. Fynora automatically detects common column-naming variants (e.g. "Debit (INR)" vs "Withdrawal Amt") across different banks. PDF support covers digital, text-based statements; a scanned or photographed PDF has no selectable text for us to read, so those still need a CSV export instead.' },
  { category: 'Importing Statements', question: 'Can I import statements for more than one account?', answer: 'Yes — each statement is matched to an existing account or used to create a new one automatically, based on details detected in the file.' },
  { category: 'Importing Statements', question: 'What if I import the same statement twice?', answer: 'Fynora checks for likely duplicate transactions during import and flags them for your review rather than silently double-counting them.' },

  { category: 'Managing Accounts', question: 'Can I add an account manually?', answer: 'Yes — from the Accounts page you can add a savings account, credit card, wallet, or investment account by hand, including an optional account holder name and masked account number.' },
  { category: 'Managing Accounts', question: 'How is my account number displayed?', answer: 'Account numbers are shown masked by default, with an eye icon to reveal them on your own screen — this is a display convenience, not an additional storage boundary.' },

  { category: 'Transactions', question: 'Can I edit a transaction after it\'s imported?', answer: 'Yes — every transaction supports full edit and delete, including its category, merchant, description, notes, tags, date, amount, and type.' },
  { category: 'Transactions', question: 'Why was my transaction categorized incorrectly?', answer: 'Automatic categorization is always a starting suggestion, never a final decision. Correct it once and Fynora remembers your correction for that merchant going forward.' },
  { category: 'Transactions', question: 'What happens to my Dashboard when I delete a transaction?', answer: 'Deleting or editing a transaction automatically refreshes the Dashboard, Accounts, Budgets, Reports, Goals, and Insights — nothing is left showing stale numbers.' },

  { category: 'Budgets', question: 'How do I set a budget?', answer: 'Go to Budgets, choose a category, and set a monthly limit. Your spend against that limit updates in real time as transactions come in.' },
  { category: 'Budgets', question: 'Will I be warned before going over budget?', answer: 'Yes — the Budget Progress bar changes color as you approach and exceed your limit, both on the Budgets page and the Dashboard.' },

  { category: 'Goals', question: 'How do I track a savings goal?', answer: 'Create a goal with a target amount and, optionally, a target date. Add contributions as you save, and Fynora tracks your percentage complete.' },

  { category: 'Investments', question: 'Can I track my investments alongside everyday spending?', answer: 'Yes — investment accounts contribute to your overall net worth figure alongside savings and credit card accounts.' },

  { category: 'Reports', question: 'Can I see spending trends over multiple months?', answer: 'Yes — the Reports page and the Dashboard\'s Cash Flow chart both support viewing income/expense trends across a selectable range of months.' },

  { category: 'Billing & Subscriptions', question: 'What plans does Fynora offer?', answer: 'Three: Free, Plus (₹399/month or ₹3,500/year), and Premium (₹799/month or ₹8,000/year). Listed prices are GST-exclusive — 18% GST is added at checkout and itemized on your invoice.' },
  { category: 'Billing & Subscriptions', question: 'How do I upgrade, downgrade, or switch billing cycle?', answer: "From Billing in the app — pick a plan and complete checkout through Razorpay. Upgrading takes effect immediately; a downgrade is scheduled for the end of your current billing period instead of switching you over right away, so you keep what you paid for." },
  { category: 'Billing & Subscriptions', question: 'What happens if I cancel?', answer: "Your plan stays active until the end of the current billing period, then moves to Free automatically — no early cutoff, and no partial refund for time already paid for." },
  { category: 'Billing & Subscriptions', question: 'Can I pause my subscription instead of cancelling?', answer: 'Yes. Billing stops right away and Premium features turn off until you resume — your plan and payment setup stay in place, so resuming needs no new checkout.' },
  { category: 'Billing & Subscriptions', question: 'Can I get an invoice for a payment?', answer: "Yes — every successful payment in your Billing history has View and Download options for its invoice PDF. Pending, failed, and refunded payments don't have one, since nothing was actually charged." },
  { category: 'Billing & Subscriptions', question: 'Does Fynora store my card details?', answer: "No — Razorpay authorizes every charge directly with your bank. Fynora only ever sees your card's network and last four digits after a charge succeeds, for display on your Billing page." },
  { category: 'Billing & Subscriptions', question: 'I subscribed through the App Store or Play Store — can I manage it from Fynora?', answer: "No. A subscription bought that way is managed by Apple or Google, not Razorpay — change your plan, update payment, or cancel from your device's own subscription settings instead. Billing shows it as read-only." },

  { category: 'Gmail Sync', question: 'What is Gmail Sync?', answer: "A Premium feature that automatically detects transaction receipts in your Gmail inbox and turns them into transactions, instead of waiting for your next statement import." },
  { category: 'Gmail Sync', question: 'Is Gmail Sync included in my plan?', answer: "It's Premium only — Free and Plus don't include it. Connect it from Settings once you've upgraded." },
  { category: 'Gmail Sync', question: 'What access to my Gmail does Fynora get?', answer: "Read-only access to your messages (Google's gmail.readonly scope). Fynora can read matching emails to detect transactions, but can never send, delete, or modify anything in your inbox." },
  { category: 'Gmail Sync', question: 'Does every detected transaction get added automatically?', answer: "No — a detection Fynora isn't confident about waits in a review queue for you to confirm rather than being filed silently, the same rule statement imports follow." },
  { category: 'Gmail Sync', question: 'Can I disconnect Gmail Sync?', answer: 'Yes, any time, from Settings — disconnecting revokes access immediately and stops future syncing. Transactions already imported are not removed.' },

  { category: 'AI Insights', question: 'How does AI Insights work?', answer: 'Insights are generated from your real transaction history — biggest spending category, month-over-month category movers, and top merchants — plus the occasional grounded recommendation, like suggesting a budget for a category trending up with none set.' },

  { category: 'Statement History', question: 'Where can I see past imports?', answer: 'Statement History groups every import by the account it belongs to, and lets you view its transactions, download the original file, re-import it, or delete it.' },
  { category: 'Statement History', question: 'What happens if I delete an account with statements?', answer: 'A deleted account remains visible in Statement History for 7 days from the deletion date before it disappears, in case you need to reference it.' },

  { category: 'Troubleshooting', question: 'My statement showed "0 transactions found" — what happened?', answer: 'This usually means the file\'s header row uses column names Fynora didn\'t recognize. Try re-exporting from your bank with default column names, or contact support with the file.' },
  { category: 'Troubleshooting', question: 'I can\'t re-import a statement into a deleted account.', answer: 'That\'s expected — a deleted account can\'t receive new statements. Restore isn\'t currently self-serve; contact support if you need this reversed within the 7-day window.' },

  { category: 'Contact Support', question: 'How do I reach support?', answer: `Already a Fynora customer? Log in and open Support from the Help menu (top right) to file a ticket — you can attach a screenshot or file, and track its status. Not signed in yet, or reaching out about something else? Email ${SUPPORT_EMAIL} — for import problems, attaching the file (with sensitive numbers redacted if you prefer) speeds up a diagnosis.` },
  { category: 'Contact Support', question: 'Can I check the status of a support ticket I filed?', answer: 'Yes — "My Tickets," reachable from the Help menu once you\'re logged in, lists every ticket you\'ve filed along with its current status (Open, In Progress, Resolved, or Closed).' },
  { category: 'Contact Support', question: 'What\'s the difference between a support ticket and feedback?', answer: 'A support ticket is for something specific that\'s broken or wrong with your account — it gets a status and a response. "Send feedback" (also in the Help menu) is for a general bug report, feature request, or idea — it\'s read, but doesn\'t get an individual reply or a status you can track.' },
];

const CATEGORIES = Array.from(new Set(ARTICLES.map((a) => a.category)));

export default function Help() {
  const [query, setQuery] = useState('');
  const [activeCategory, setActiveCategory] = useState<string | null>(null);

  const filtered = useMemo(() => {
    const q = query.trim().toLowerCase();
    return ARTICLES.filter((a) => {
      const matchesCategory = !activeCategory || a.category === activeCategory;
      const matchesQuery = !q || a.question.toLowerCase().includes(q) || a.answer.toLowerCase().includes(q) || a.category.toLowerCase().includes(q);
      return matchesCategory && matchesQuery;
    });
  }, [query, activeCategory]);

  return (
    <PublicLayout title="Help Center" subtitle="Search for an answer, or browse by topic.">
      <div className="relative mb-6">
        <Search size={16} className="absolute left-4 top-1/2 -translate-y-1/2 text-gray-500" />
        <input
          value={query}
          onChange={(e) => setQuery(e.target.value)}
          placeholder="Search for help — e.g. 'duplicate transaction', 'phone verification'…"
          className="w-full bg-[#12142a] border border-white/10 rounded-xl pl-11 pr-4 py-3.5 text-sm text-white placeholder:text-gray-500 focus:outline-none focus:ring-2 focus:ring-primary/40"
        />
      </div>

      <div className="flex flex-wrap gap-2 mb-8">
        <button
          type="button"
          onClick={() => setActiveCategory(null)}
          className={`text-xs font-medium px-3 py-1.5 rounded-full border transition-colors ${!activeCategory ? 'bg-primary text-on-primary border-primary' : 'border-white/10 text-gray-400 hover:text-white'}`}
        >
          All Topics
        </button>
        {CATEGORIES.map((c) => (
          <button
            key={c}
            type="button"
            onClick={() => setActiveCategory(c)}
            className={`text-xs font-medium px-3 py-1.5 rounded-full border transition-colors ${activeCategory === c ? 'bg-primary text-on-primary border-primary' : 'border-white/10 text-gray-400 hover:text-white'}`}
          >
            {c}
          </button>
        ))}
      </div>

      {filtered.length === 0 ? (
        <div className="bg-[#12142a] border border-white/10 rounded-xl p-8 text-center">
          <p className="text-sm text-gray-300 mb-1">No articles match "{query}".</p>
          <p className="text-xs text-gray-500 mb-4">Try a different search term, or reach out directly below.</p>
        </div>
      ) : (
        <div className="space-y-3 mb-10">
          {filtered.map((a) => (
            <div key={a.question} className="bg-[#12142a] border border-white/10 rounded-xl p-5">
              <span className="text-2xs uppercase tracking-wide text-primary font-semibold">{a.category}</span>
              <h3 className="font-semibold text-white text-sm mt-1 mb-1.5">{a.question}</h3>
              <p className="text-xs text-gray-400 leading-relaxed">{a.answer}</p>
            </div>
          ))}
        </div>
      )}

      <div className="bg-[#12142a] border border-white/10 rounded-xl p-6 flex items-center gap-4 flex-wrap justify-between">
        <div>
          <p className="text-sm font-semibold text-white mb-1">Still need help?</p>
          <p className="text-xs text-gray-400">Our support team is happy to help with anything not covered above.</p>
        </div>
        <a href={SUPPORT_MAILTO} className="bg-primary hover:bg-primary-dark text-on-primary text-xs font-semibold rounded-lg px-4 py-2.5 flex items-center gap-1.5 flex-shrink-0">
          <Mail size={14} /> Contact Support
        </a>
      </div>
    </PublicLayout>
  );
}
