import { useEffect } from 'react';
import { Link, useParams } from 'react-router-dom';
import { ArrowLeft, ChevronRight } from 'lucide-react';
import { PublicLayout } from '../components/PublicLayout';
import NotFound from './NotFound';
import { HelpContactCard } from './HelpContactCard';
import { GetTheApp } from '../components/GetTheApp';
import {
  type HelpArticle as HelpArticleData,
  helpArticleBySlug,
  helpArticleDescription,
  helpArticlePath,
  relatedHelpArticles,
} from './helpArticles';

/**
 * One Help answer on its own page, /help/<slug>. The question is the <h1> (and so the <title>), and
 * the answer is the first thing under it, in full, before any navigation: a reader, a search
 * snippet or an AI answer that takes only the opening sentence gets the direct answer.
 *
 * The prerendered copy (scripts/prerender.mjs, dist/help/<slug>.html) is this same component, given
 * the article directly; the browser route reads the slug from the address.
 */
export function HelpArticleView({ article }: { article: HelpArticleData }) {
  const related = relatedHelpArticles(article);
  return (
    <PublicLayout title={article.question} description={helpArticleDescription(article)}>
      <article className="mb-10">
        <p className="text-base text-ink leading-relaxed max-w-2xl">{article.answer}</p>
      </article>

      <GetTheApp variant="compact" placement="badge_top" className="mb-10 max-w-2xl" />

      <nav aria-label="Breadcrumb" className="mb-10 text-xs text-muted">
        <ol className="flex flex-wrap items-center gap-1.5">
          <li><Link to="/help" className="text-primary hover:underline font-medium">Help Center</Link></li>
          <li aria-hidden="true"><ChevronRight size={12} /></li>
          <li>{article.category}</li>
        </ol>
      </nav>

      {related.length > 0 && (
        <section className="mb-10">
          <h2 className="text-sm font-semibold text-ink mb-3">Related answers</h2>
          <ul className="space-y-2">
            {related.map((a) => (
              <li key={a.slug}>
                <Link to={helpArticlePath(a)} className="text-sm text-primary hover:underline">{a.question}</Link>
              </li>
            ))}
          </ul>
        </section>
      )}

      <p className="mb-10">
        <Link to="/help" className="inline-flex items-center gap-1.5 text-sm text-muted hover:text-ink">
          <ArrowLeft size={14} /> All Help answers
        </Link>
      </p>

      <HelpContactCard />
    </PublicLayout>
  );
}

/**
 * The /help/:slug route. A slug no article has is the not-found page, the same page (and, on the
 * deployed site, the same HTTP 404) as any other unknown address: there is no file for it.
 */
export default function HelpArticle() {
  const { slug } = useParams();
  const article = helpArticleBySlug(slug);
  // Following a related answer keeps this component mounted and only changes the slug, so the
  // browser would stay scrolled to where the link was and hide the new question. The app has no
  // scroll reset of its own; this one covers the article-to-article links this page adds.
  useEffect(() => {
    window.scrollTo(0, 0);
  }, [slug]);
  return article ? <HelpArticleView article={article} /> : <NotFound />;
}
