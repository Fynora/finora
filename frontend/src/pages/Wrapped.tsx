import { useQuery } from '@tanstack/react-query';
import { FinoraCard } from '../design-system';
import { dashboardApi } from '../api/endpoints';

export default function Wrapped() {
  const currentYear = new Date().getFullYear();
  const { data } = useQuery({ queryKey: ['wrapped', currentYear], queryFn: () => dashboardApi.wrapped(currentYear) });

  if (!data) return null;

  return (
    <FinoraCard padding="lg" className="max-w-md mx-auto bg-ink text-on-primary">
      <p className="text-sm opacity-70">Your Financial Journey</p>
      <h1 className="text-4xl font-bold mb-4">{data.year}</h1>
      <p className="text-lg mb-1">{data.goalContributions}</p>
      <p className="text-sm opacity-70 mb-4">goal contributions this year</p>
      <ul>
        {data.landmarkTitles.map((title) => (
          <li key={title} className="text-sm mb-2">{title}</li>
        ))}
      </ul>
    </FinoraCard>
  );
}
