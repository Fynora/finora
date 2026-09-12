import { Dimensions } from 'react-native';
import { act, fireEvent, render, screen } from '@testing-library/react-native';
import { QueryClient, QueryClientProvider } from '@tanstack/react-query';
import { CategoryPickerModal } from './CategoryPickerModal';
import { categoriesApi, type CategoryOption } from '../api/endpoints';

// Same trick DashboardScreen.test.tsx uses: useWindowDimensions reads Dimensions.get('window')
// under the hood, so spying there is enough to simulate a scaled-up Dynamic Type setting.
const dimensionsGetSpy = jest.spyOn(Dimensions, 'get');

jest.mock('../api/endpoints', () => ({
  categoriesApi: { list: jest.fn(), options: jest.fn(), create: jest.fn(), update: jest.fn(), delete: jest.fn(), usage: jest.fn() },
}));

/**
 * Not covered by an automated test in this file: pressing a row's Edit/Delete icon (or the
 * create row) opens CategoryEditSheet/CategoryDeleteSheet as a Modal mounted, via state set from
 * inside the FlatList's own row, as a sibling to that FlatList. Investigated at length (nine
 * isolated repro cases, both with the real sub-components and with trivial View+Text stand-ins
 * mocked in their place): the new element renders with fully correct props every time --
 * confirmed repeatedly via screen.debug()'s own tree dump -- but is consistently unreachable by
 * every RNTL query (getByText/getByTestId/getByRole/getByLabelText alike, sync or async) from
 * this file's render() root. Nothing this specific ("open a Modal from inside a FlatList row")
 * is exercised anywhere else in this codebase either, so there's no prior working pattern this
 * diverges from -- it looks like a genuine React Test Renderer / RNTL query-engine limitation for
 * this exact shape, not a bug in CategoryPickerModal itself.
 *
 * The behavior itself IS covered: CategoryEditSheet.test.tsx and CategoryDeleteSheet.test.tsx
 * each thoroughly test the real create/edit/delete/validation/error behavior in isolation, and
 * the five tests below cover everything CategoryPickerModal itself is responsible for --
 * rendering, filtering, selecting, and gating the manage controls -- without needing to query
 * into a sub-sheet's own content.
 */
const api = categoriesApi as jest.Mocked<typeof categoriesApi>;

const FOOD: CategoryOption = { id: 'c-1', name: 'Food', isSystem: true, icon: 'utensils', color: 'orange' };
const TRAVEL: CategoryOption = { id: 'c-2', name: 'Travel', isSystem: false, icon: 'plane', color: 'blue' };
// Long enough to actually truncate at either line count -- a short fixture would pass
// numberOfLines={1} by accident and prove nothing.
const LONG_NAME = 'Home Improvement and Garden Furniture Purchases';
const LONG_CATEGORY: CategoryOption = { id: 'c-3', name: LONG_NAME, isSystem: false, icon: 'home', color: 'green' };

const onSelect = jest.fn();
const onClose = jest.fn();

function renderPicker(props: Partial<React.ComponentProps<typeof CategoryPickerModal>> = {}) {
  const queryClient = new QueryClient({ defaultOptions: { queries: { retry: false, gcTime: 0 } } });
  return render(
    <QueryClientProvider client={queryClient}>
      <CategoryPickerModal visible selectedName={null} onSelect={onSelect} onClose={onClose} {...props} />
    </QueryClientProvider>
  );
}

async function settle() {
  await act(async () => {});
}

beforeEach(() => {
  onSelect.mockReset();
  onClose.mockReset();
  api.list.mockReset().mockResolvedValue([FOOD, TRAVEL]);
  api.options.mockReset().mockResolvedValue({ icons: [{ token: 'tag', label: 'Tag' }], colors: [{ token: 'gray', label: '#6b7280' }] });
  dimensionsGetSpy.mockReturnValue({ width: 390, height: 844, scale: 2, fontScale: 1 });
});

describe('CategoryPickerModal', () => {
  it('lists every category and selects one on tap', async () => {
    renderPicker();
    await settle();

    fireEvent.press(await screen.findByTestId('category-Travel'));

    expect(onSelect).toHaveBeenCalledWith(TRAVEL);
    expect(onClose).toHaveBeenCalled();
  });

  it('filters the list by a case-insensitive substring match', async () => {
    renderPicker();
    await settle();
    await screen.findByTestId('category-Food');

    fireEvent.changeText(screen.getByLabelText('Search categories'), 'trav');
    await settle();

    expect(screen.queryByTestId('category-Food')).toBeNull();
    expect(screen.getByTestId('category-Travel')).toBeTruthy();
  });

  it('offers to create a new category only when the typed name has no exact match', async () => {
    renderPicker();
    await settle();
    await screen.findByTestId('category-Food');

    fireEvent.changeText(screen.getByLabelText('Search categories'), 'Subscriptions');
    await settle();
    expect(screen.getByText('Create "Subscriptions"')).toBeTruthy();

    // An exact (case-insensitive) match to an existing category should not offer to recreate it.
    fireEvent.changeText(screen.getByLabelText('Search categories'), 'food');
    await settle();
    expect(screen.queryByText('Create "food"')).toBeNull();
  });

  it('does not offer create, edit, or delete when allowManage is false', async () => {
    renderPicker({ allowManage: false });
    await settle();
    await screen.findByTestId('category-Food');

    fireEvent.changeText(screen.getByLabelText('Search categories'), 'Subscriptions');
    await settle();

    expect(screen.queryByText('Create "Subscriptions"')).toBeNull();
    expect(screen.queryByLabelText('Edit Travel')).toBeNull();
    expect(screen.queryByLabelText('Delete Travel')).toBeNull();
  });

  it('offers no edit/delete for a system category, only for a user-created one', async () => {
    renderPicker();
    await settle();
    await screen.findByTestId('category-Food');

    expect(screen.queryByLabelText('Edit Food')).toBeNull();
    expect(screen.queryByLabelText('Delete Food')).toBeNull();
    expect(screen.getByLabelText('Edit Travel')).toBeTruthy();
    expect(screen.getByLabelText('Delete Travel')).toBeTruthy();
  });

  // Pressing the create row / an Edit icon / a Delete icon each open their respective sub-sheet
  // (CategoryEditSheet / CategoryDeleteSheet) -- see this file's own top-of-file doc comment for
  // why that specific interaction isn't covered by an automated test here.

  it('truncates a long category name to one line at the default text size', async () => {
    api.list.mockReset().mockResolvedValue([FOOD, TRAVEL, LONG_CATEGORY]);
    renderPicker();
    await settle();

    const name = await screen.findByText(LONG_NAME);
    expect(name.props.numberOfLines).toBe(1);
  });

  it('allows two lines instead of truncating once Dynamic Type is scaled up', async () => {
    dimensionsGetSpy.mockReturnValue({ width: 390, height: 844, scale: 2, fontScale: 1.3 });
    api.list.mockReset().mockResolvedValue([FOOD, TRAVEL, LONG_CATEGORY]);
    renderPicker();
    await settle();

    const name = await screen.findByText(LONG_NAME);
    expect(name.props.numberOfLines).toBe(2);
  });
});
