import type { ReactNode } from 'react';
import { Link } from 'react-router-dom';
import {
  ShieldCheck, UploadCloud, TrendingUp, PiggyBank, Target, LineChart,
  Wallet, PieChart as PieChartIcon, BarChart3,
} from 'lucide-react';
import { BrandMark } from '../../components/BrandMark';

// Lifted verbatim from Login.tsx/Register.tsx, which had the identical array duplicated in
// both -- one copy now, shared by every step that shows the marketing panel.
const FEATURES = [
  { icon: ShieldCheck, iconBg: 'bg-accent-blue-bg', iconColor: 'text-accent-blue', title: 'Secure & Private', desc: 'Your data is encrypted and bank-level secure.' },
  { icon: UploadCloud, iconBg: 'bg-accent-green-bg', iconColor: 'text-accent-green', title: 'Auto Statement Import', desc: 'Import bank & credit card statements in seconds.' },
  { icon: TrendingUp, iconBg: 'bg-accent-orange-bg', iconColor: 'text-accent-orange', title: 'AI Financial Insights', desc: 'AI-powered insights to help you save more.' },
  { icon: PiggyBank, iconBg: 'bg-accent-purple-bg', iconColor: 'text-accent-purple', title: 'Budget Tracking', desc: 'Set budgets and stay effortlessly on track.' },
  { icon: Target, iconBg: 'bg-accent-blue-bg', iconColor: 'text-accent-blue', title: 'Goal Management', desc: 'Plan and reach your financial goals faster.' },
  { icon: LineChart, iconBg: 'bg-accent-teal-bg', iconColor: 'text-accent-teal', title: 'Investment Tracking', desc: 'Track your portfolio and net worth growth.' },
];

interface MarketingPanelProps {
  badge: string;
  headline: ReactNode;
  description: string;
}

export function MarketingPanel({ badge, headline, description }: MarketingPanelProps) {
  return (
    <div className="hidden lg:block auth-reveal">
      <Link to="/" className="flex items-center gap-2.5 mb-8 w-fit">
        <BrandMark size={36} variant="auto" className="rounded-lg" />
        <span className="font-display font-extrabold tracking-wide text-ink text-xl">FYNORA</span>
      </Link>

      <span className="inline-block bg-primary-light text-primary text-xs font-medium px-3 py-1 rounded-full mb-4">
        {badge}
      </span>
      <h1 className="text-4xl font-bold text-ink leading-tight mb-4">{headline}</h1>
      <p className="text-muted text-base mb-8 max-w-md">{description}</p>

      <div className="space-y-5 mb-10">
        {FEATURES.map((f) => (
          <div key={f.title} className="flex items-start gap-3">
            <div className={`w-10 h-10 rounded-lg ${f.iconBg} flex items-center justify-center flex-shrink-0`}>
              <f.icon size={18} className={f.iconColor} />
            </div>
            <div>
              <p className="text-sm font-semibold text-ink">{f.title}</p>
              <p className="text-xs text-muted">{f.desc}</p>
            </div>
          </div>
        ))}
      </div>

      <div className="flex items-center gap-4 opacity-70">
        <div className="w-14 h-14 rounded-2xl bg-primary-light flex items-center justify-center">
          <Wallet size={22} className="text-primary" />
        </div>
        <div className="w-14 h-14 rounded-2xl bg-accent-green-bg flex items-center justify-center -translate-y-2">
          <PieChartIcon size={22} className="text-accent-green" />
        </div>
        <div className="w-14 h-14 rounded-2xl bg-accent-orange-bg flex items-center justify-center">
          <BarChart3 size={22} className="text-accent-orange" />
        </div>
      </div>
    </div>
  );
}
