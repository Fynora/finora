import { useEffect } from 'react';
import { Link, useParams } from 'react-router-dom';
import { ArrowLeft, ChevronRight, ExternalLink } from 'lucide-react';
import { PublicLayout, PublicSection } from '../components/PublicLayout';
import NotFound from './NotFound';
import { HelpContactCard } from './HelpContactCard';
import {
  type StatementCode,
  STATEMENT_CODES_INDEX,
  statementCodeBySlug,
  statementCodePath,
} from './statementCodes';

/**
 * One bank statement code explained, /bank-statement-codes/<slug>. The answer is the first thing
 * under the heading, in full, so a search snippet or an AI answer that quotes one paragraph gets
 * the definition. The prerendered copy (scripts/ssr-entry.tsx pageCollections) is this view, given
 * the code directly.
 */
export function StatementCodeView({ code }: { code: StatementCode }) {
  const related = code.related
    .map((slug) => statementCodeBySlug(slug))
    .filter((c): c is StatementCode => c !== undefined);
  return (
    <PublicLayout title={code.title} description={code.description}>
      <article>
        <p className="text-base text-ink leading-relaxed max-w-2xl mb-10">{code.answer}</p>

        <nav aria-label="Breadcrumb" className="mb-10 text-xs text-muted">
          <ol className="flex flex-wrap items-center gap-1.5">
            <li><Link to={STATEMENT_CODES_INDEX} className="text-primary hover:underline font-medium">Bank statement codes</Link></li>
            <li aria-hidden="true"><ChevronRight size={12} /></li>
            <li>{code.code}</li>
          </ol>
        </nav>

        {code.sections.map((section) => (
          <PublicSection key={section.heading} title={section.heading}>
            {section.paragraphs.map((p) => <p key={p}>{p}</p>)}
          </PublicSection>
        ))}

        {code.examples.length > 0 && (
          <PublicSection title="Example lines">
            <p>Made-up lines in the usual shape. Your bank&apos;s wording will differ.</p>
            <ul className="space-y-3">
              {code.examples.map((example) => (
                <li key={example.line}>
                  <code className="block font-mono text-xs text-ink bg-card border border-border rounded-lg px-3 py-2 break-all">
                    {example.line}
                  </code>
                  <span className="block mt-1">{example.note}</span>
                </li>
              ))}
            </ul>
          </PublicSection>
        )}

        {code.fynora && (
          <PublicSection title="How Fynora reads it">
            <p>{code.fynora}</p>
          </PublicSection>
        )}

        {code.faqs.length > 0 && (
          <PublicSection title="Questions">
            {code.faqs.map(([question, answer]) => (
              <div key={question}>
                <h3 className="font-semibold text-ink text-sm mb-1">{question}</h3>
                <p>{answer}</p>
              </div>
            ))}
          </PublicSection>
        )}

        {code.sources.length > 0 && (
          <PublicSection title="Sources">
            <ul className="space-y-1.5">
              {code.sources.map((source) => (
                <li key={source.url}>
                  <a href={source.url} rel="noopener" className="inline-flex items-center gap-1 text-primary hover:underline">
                    {source.label} <ExternalLink size={12} aria-hidden="true" />
                  </a>
                </li>
              ))}
            </ul>
          </PublicSection>
        )}
      </article>

      {related.length > 0 && (
        <section className="mb-10">
          <h2 className="text-sm font-semibold text-ink mb-3">Related codes</h2>
          <ul className="space-y-2">
            {related.map((c) => (
              <li key={c.slug}>
                <Link to={statementCodePath(c)} className="text-sm text-primary hover:underline">{c.title}</Link>
              </li>
            ))}
          </ul>
        </section>
      )}

      <p className="mb-10">
        <Link to={STATEMENT_CODES_INDEX} className="inline-flex items-center gap-1.5 text-sm text-muted hover:text-ink">
          <ArrowLeft size={14} /> All bank statement codes
        </Link>
      </p>

      <HelpContactCard />
    </PublicLayout>
  );
}

/**
 * The /bank-statement-codes/:slug route. A slug with no code is the not-found page, as on the
 * deployed site, where it has no file and gets a 404.
 */
export default function StatementCodePage() {
  const { slug } = useParams();
  const code = statementCodeBySlug(slug);
  // Following a related code keeps this component mounted and changes only the slug, so the page
  // would otherwise stay scrolled to where the link was.
  useEffect(() => {
    window.scrollTo(0, 0);
  }, [slug]);
  return code ? <StatementCodeView code={code} /> : <NotFound />;
}
