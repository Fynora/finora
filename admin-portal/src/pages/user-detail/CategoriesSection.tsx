import { useQuery } from '@tanstack/react-query';
import { Sparkles } from 'lucide-react';
import { adminUserCategoriesApi } from '../../api/endpoints';

export function CategoriesSection({ userId }: { userId: string }) {
  const { data: categories, isLoading } = useQuery({
    queryKey: ['admin-user-ai-categories', userId],
    queryFn: () => adminUserCategoriesApi.aiCreated(userId),
  });

  return (
    <div className="bg-card border border-border rounded-xl2 shadow-card p-6">
      <div className="flex items-center gap-2 mb-3">
        <Sparkles size={15} className="text-primary" />
        <h3 className="text-sm font-semibold text-ink">Fynora-Created Categories</h3>
      </div>

      {isLoading && <p className="text-sm text-muted">Loading…</p>}
      {!isLoading && (categories ?? []).length === 0 && (
        <p className="text-sm text-muted">This user has no Fynora-created categories.</p>
      )}
      <div>
        {categories?.map((cat) => (
          <div key={cat.id} className="py-2 border-b border-border last:border-b-0">
            <p className="text-ink font-medium">{cat.name}</p>
            <p className="text-xs text-muted">{cat.aiCreationReason}</p>
          </div>
        ))}
      </div>
    </div>
  );
}
