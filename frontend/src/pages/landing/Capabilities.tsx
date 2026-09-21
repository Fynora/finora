import { BarChart3, Brain, LayoutDashboard, LineChart, Repeat, Tags, Target, TrendingUp } from 'lucide-react';
import { Reveal, Section, SectionHeading } from './primitives';
import { DashboardMock } from './DashboardMock';
import { capabilities } from './landing-config';

// One icon per card, in the order of capabilities.items.
const ICONS = [Tags, LayoutDashboard, Target, Repeat, BarChart3, LineChart, TrendingUp, Brain];

/**
 * What Fynora does, as a grid, followed by the dashboard illustration. The illustration's figures
 * are illustrative (see DashboardMock's own note), so it carries a "Sample data" caption. This
 * section replaces DashboardShowcase, whose one generic sentence named nothing.
 */
export function Capabilities() {
  return (
    <Section id="features">
      <SectionHeading
        eyebrow={capabilities.eyebrow}
        title={<>{capabilities.title}<br />{capabilities.titleLine2}</>}
        blurb={capabilities.blurb}
      />
      {/* Eight cards: two columns on tablets and four on desktop both fill their last row. */}
      <div className="grid sm:grid-cols-2 lg:grid-cols-4 gap-4 mb-14">
        {capabilities.items.map((item, i) => {
          const Icon = ICONS[i];
          return (
            <Reveal key={item.title} delayMs={(i % 4) * 70}>
              <div className="m-card m-card-hover p-6 h-full">
                <div className="flex items-center justify-between mb-4">
                  <span className="w-10 h-10 rounded-xl grid place-items-center" style={{ background: 'var(--m-brand-wash)', color: 'var(--m-brand)' }}>
                    <Icon size={18} aria-hidden="true" />
                  </span>
                  {item.plan ? (
                    <span className="text-[10px] uppercase tracking-wide font-semibold px-2 py-1 rounded-full" style={{ background: 'var(--m-brand-wash)', color: 'var(--m-brand-deep)' }}>
                      {item.plan}
                    </span>
                  ) : null}
                </div>
                <h3 className="m-h3 text-[15px] mb-1.5">{item.title}</h3>
                <p className="text-sm leading-relaxed" style={{ color: 'var(--m-ink-2)' }}>{item.body}</p>
              </div>
            </Reveal>
          );
        })}
      </div>
      <Reveal><DashboardMock withSidebar /></Reveal>
      <p className="text-center text-xs mt-3" style={{ color: 'var(--m-ink-3)' }}>{capabilities.mockCaption}</p>
    </Section>
  );
}
