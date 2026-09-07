import { act, fireEvent, render, screen, waitFor } from '@testing-library/react-native';
import { QueryClient, QueryClientProvider } from '@tanstack/react-query';
import { CategoryEditSheet } from './CategoryEditSheet';
import { categoriesApi, type CategoryOption } from '../api/endpoints';

jest.mock('../api/endpoints', () => ({
  categoriesApi: { options: jest.fn(), create: jest.fn(), update: jest.fn() },
}));

const api = categoriesApi as jest.Mocked<typeof categoriesApi>;

function renderSheet(props: React.ComponentProps<typeof CategoryEditSheet>) {
  const queryClient = new QueryClient({ defaultOptions: { queries: { retry: false, gcTime: 0 } } });
  return render(
    <QueryClientProvider client={queryClient}>
      <CategoryEditSheet {...props} />
    </QueryClientProvider>
  );
}

const OPTIONS = {
  icons: [{ token: 'tag', label: 'Tag' }, { token: 'home', label: 'Home' }],
  colors: [{ token: 'gray', label: '#6b7280' }, { token: 'blue', label: '#2563eb' }],
};

const onClose = jest.fn();
const onSaved = jest.fn();

async function settle() {
  await act(async () => {});
}

beforeEach(() => {
  onClose.mockReset();
  onSaved.mockReset();
  api.options.mockReset().mockResolvedValue(OPTIONS);
  api.create.mockReset();
  api.update.mockReset();
});

describe('CategoryEditSheet — create mode', () => {
  it('creates with the typed name and the default icon/color when nothing else is picked', async () => {
    const saved: CategoryOption = { id: 'c-new', name: 'Subscriptions', isSystem: false, icon: 'tag', color: 'gray' };
    api.create.mockResolvedValue(saved);
    renderSheet({ mode: 'create', initialName: 'Subscriptions', onClose, onSaved });
    await settle();

    fireEvent.press(screen.getByRole('button', { name: /^Save$/ }));
    await settle();

    await waitFor(() => expect(api.create).toHaveBeenCalledWith('Subscriptions', 'tag', 'gray'));
    expect(onSaved).toHaveBeenCalledWith(saved);
  });

  it('creates with a picked icon and color', async () => {
    const saved: CategoryOption = { id: 'c-new', name: 'Subscriptions', isSystem: false, icon: 'home', color: 'blue' };
    api.create.mockResolvedValue(saved);
    renderSheet({ mode: 'create', initialName: 'Subscriptions', onClose, onSaved });
    await settle();

    // categoriesApi.options() resolves asynchronously -- the icon/color grids start empty and
    // only populate once that query settles, so these need to be waited for, not read synchronously.
    fireEvent.press(await screen.findByLabelText('Home'));
    fireEvent.press(await screen.findByLabelText('blue'));
    fireEvent.press(screen.getByRole('button', { name: /^Save$/ }));
    await settle();

    await waitFor(() => expect(api.create).toHaveBeenCalledWith('Subscriptions', 'home', 'blue'));
  });

  it('disables Save for a blank (whitespace-only) name', async () => {
    renderSheet({ mode: 'create', initialName: '  ', onClose, onSaved });
    await settle();

    expect(
      screen.getByRole('button', { name: /^Save$/ }).props.accessibilityState.disabled
    ).toBe(true);
    expect(api.create).not.toHaveBeenCalled();
  });

  it('shows an error and does not close when the create request fails', async () => {
    api.create.mockRejectedValue(
      Object.assign(new Error('bad'), { isAxiosError: true, response: { status: 409, data: { message: 'A category with this name already exists.' } } })
    );
    renderSheet({ mode: 'create', initialName: 'Food', onClose, onSaved });
    await settle();

    fireEvent.press(screen.getByRole('button', { name: /^Save$/ }));
    await settle();

    expect(await screen.findByText('A category with this name already exists.')).toBeTruthy();
    expect(onSaved).not.toHaveBeenCalled();
  });
});

describe('CategoryEditSheet — edit mode', () => {
  const existing: CategoryOption = { id: 'c-1', name: 'Groceries', isSystem: false, icon: 'home', color: 'blue' };

  it('seeds the form from the existing category and PATCHes its id', async () => {
    const saved: CategoryOption = { ...existing, name: 'Groceries & Home' };
    api.update.mockResolvedValue(saved);
    renderSheet({ mode: 'edit', initialName: existing.name, category: existing, onClose, onSaved });
    await settle();

    expect(screen.getByLabelText('Name').props.value).toBe('Groceries');
    // Seeded icon/color stay selected without the user touching either picker -- waited for the
    // same reason as the create-mode test above (categoriesApi.options() is async).
    expect((await screen.findByLabelText('Home')).props.accessibilityState.selected).toBe(true);
    expect(screen.getByLabelText('blue').props.accessibilityState.selected).toBe(true);

    fireEvent.changeText(screen.getByLabelText('Name'), 'Groceries & Home');
    fireEvent.press(screen.getByRole('button', { name: /^Save$/ }));
    await settle();

    await waitFor(() =>
      expect(api.update).toHaveBeenCalledWith('c-1', { name: 'Groceries & Home', icon: 'home', color: 'blue' })
    );
    expect(onSaved).toHaveBeenCalledWith(saved);
  });
});
