import { useMemo, useState } from 'react';
import { Link } from 'react-router-dom';
import { Search } from 'lucide-react';
import { PublicLayout } from '../components/PublicLayout';
import { HelpContactCard } from './HelpContactCard';
import { HELP_ARTICLES, HELP_CATEGORIES, helpArticlePath } from './helpArticles';


export default function Help() {
  const [query, setQuery] = useState('');
  const [activeCategory, setActiveCategory] = useState<string | null>(null);

  const filtered = useMemo(() => {
    const q = query.trim().toLowerCase();
    return HELP_ARTICLES.filter((a) => {
      const matchesCategory = !activeCategory || a.category === activeCategory;
      const matchesQuery = !q || a.question.toLowerCase().includes(q) || a.answer.toLowerCase().includes(q) || a.category.toLowerCase().includes(q);
      return matchesCategory && matchesQuery;
    });
  }, [query, activeCategory]);

  // One group per category, in HELP_CATEGORIES order, so each category is a real <h2> above its
  // articles. The articles used to be one flat list with the category as a small label inside
  // every card, which left the page with an <h1> and thirty <h3>s and nothing between them.
  const grouped = HELP_CATEGORIES
    .map((category) => [category, filtered.filter((a) => a.category === category)] as const)
    .filter(([, articles]) => articles.length > 0);

  return (
    <PublicLayout
      title="Help Center"
      subtitle="Search for an answer, or browse by topic."
      description="Answers to common questions about Fynora: importing bank and card statements, categorization, budgets, goals, billing, and how to reach support."
    >
      <p className="text-sm text-muted mb-6">
        Not sure what a code on your bank statement means, such as NACH, IMPS or INT PD? See{' '}
        <Link to="/bank-statement-codes" className="text-primary hover:underline">bank statement codes explained</Link>.
      </p>

      <div className="relative mb-6">
        <Search size={16} className="absolute left-4 top-1/2 -translate-y-1/2 text-muted" />
        <input
          value={query}
          onChange={(e) => setQuery(e.target.value)}
          placeholder="Search for help — e.g. 'duplicate transaction', 'phone verification'…"
          className="w-full bg-card border border-border rounded-xl pl-11 pr-4 py-3.5 text-sm text-ink placeholder:text-muted focus:outline-none focus:ring-2 focus:ring-primary/40"
        />
      </div>

      <div className="flex flex-wrap gap-2 mb-8">
        <button
          type="button"
          onClick={() => setActiveCategory(null)}
          className={`text-xs font-medium px-3 py-1.5 rounded-full border transition-colors ${!activeCategory ? 'bg-primary text-on-primary border-primary' : 'border-border text-muted hover:text-ink'}`}
        >
          All Topics
        </button>
        {HELP_CATEGORIES.map((c) => (
          <button
            key={c}
            type="button"
            onClick={() => setActiveCategory(c)}
            className={`text-xs font-medium px-3 py-1.5 rounded-full border transition-colors ${activeCategory === c ? 'bg-primary text-on-primary border-primary' : 'border-border text-muted hover:text-ink'}`}
          >
            {c}
          </button>
        ))}
      </div>

      {filtered.length === 0 ? (
        <div className="bg-card border border-border rounded-xl p-8 text-center">
          <p className="text-sm text-ink mb-1">No articles match "{query}".</p>
          <p className="text-xs text-muted mb-4">Try a different search term, or reach out directly below.</p>
        </div>
      ) : (
        <div className="mb-10">
          {grouped.map(([category, articles]) => (
            <section key={category} className="mb-8">
              <h2 className="text-2xs uppercase tracking-wide text-primary font-semibold mb-3">{category}</h2>
              <div className="space-y-3">
                {/* Each question links to its own page (/help/<slug>), which is the address search
                    results and the structured data point at; this index keeps every answer
                    readable in place as well. */}
                {articles.map((a) => (
                  <div key={a.slug} className="bg-card border border-border rounded-xl p-5">
                    <h3 className="font-semibold text-ink text-sm mb-1.5">
                      <Link to={helpArticlePath(a)} className="hover:text-primary hover:underline">{a.question}</Link>
                    </h3>
                    <p className="text-xs text-muted leading-relaxed">{a.answer}</p>
                  </div>
                ))}
              </div>
            </section>
          ))}
        </div>
      )}

      <HelpContactCard />
    </PublicLayout>
  );
}
