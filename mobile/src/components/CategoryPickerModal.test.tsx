import { Dimensions } from 'react-native';
import { act, fireEvent, render, screen } from '@testing-library/react-native';
import { QueryClient, QueryClientProvider } from '@tanstack/react-query';
import { CategoryPickerModal, NESTED_SHEET_DISMISS_MS } from './CategoryPickerModal';
import { CategoryEditSheet } from './CategoryEditSheet';
import { categoriesApi, type CategoryOption } from '../api/endpoints';
import { expectHaptics } from '../test/haptics';

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
const PET_CARE: CategoryOption = {
  id: 'c-4',
  name: 'Pet Care',
  isSystem: false,
  icon: 'tag',
  color: 'gray',
  aiCreationReason: 'Pet supplies retailer, no existing match',
};

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

  it('ticks only when the pick differs from the category already selected', async () => {
    renderPicker({ selectedName: 'Food' });
    await settle();
    fireEvent.press(await screen.findByTestId('category-Food'));
    expect(onSelect).toHaveBeenCalledWith(FOOD);
    expectHaptics({});
    fireEvent.press(screen.getByTestId('category-Travel'));
    expectHaptics({ selection: 1 });
  });

  // Regression for FYNORA-MOBILE-5 (Sentry): this component is always mounted inside LedgerScreen
  // (RN Modal renders its children even while `visible` is false), so its two useMemo hooks run
  // against whatever's already in the ['categories'] query cache on every cold start -- including
  // data just restored from AsyncStorage before the real fetch resolves. A row missing `name`
  // used to throw `cat.name.toLowerCase()` and crash the whole render tree; it must now be
  // dropped instead.
  it('drops a category row with no name instead of crashing', async () => {
    const MALFORMED = { id: 'c-bad', isSystem: false, icon: 'tag', color: 'gray' } as unknown as CategoryOption;
    api.list.mockReset().mockResolvedValue([FOOD, MALFORMED, TRAVEL]);

    expect(() => renderPicker()).not.toThrow();
    await settle();
    await screen.findByTestId('category-Food');

    // Row testIDs are built from `name` (`category-${item.name}`), never `id` -- so a malformed
    // row without a name can't be located by an `id`-based testID either way, and asserting one
    // proves nothing. Assert on what the row list actually rendered instead: exactly the two valid
    // rows, nothing at the `category-undefined` testID a rendered-but-nameless row would produce.
    expect(screen.getByTestId('category-Travel')).toBeTruthy();
    expect(screen.queryByTestId('category-undefined')).toBeNull();
    expect(screen.getAllByTestId(/^category-/)).toHaveLength(2);
  });

  it('shows a badge for AI-created categories', async () => {
    api.list.mockReset().mockResolvedValue([FOOD, TRAVEL, PET_CARE]);
    renderPicker();
    await settle();
    await screen.findByTestId('category-Pet Care');

    expect(screen.getByLabelText(/created by fynora/i)).toBeTruthy();
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

  // For a caller whose pick IS the save (Ledger, Category Review, Quick Sort, the recurring
  // question): the save's success or error is the action's one haptic, so the pick gives none.
  it('gives no tick when silent, and still selects', async () => {
    renderPicker({ selectedName: 'Food', silent: true });
    await settle();
    fireEvent.press(await screen.findByTestId('category-Travel'));
    expect(onSelect).toHaveBeenCalledWith(TRAVEL);
    expectHaptics({});
  });

  // A category created (or the selected one renamed) here is selected for the caller, but the
  // edit sheet's save already gave the one success haptic: one action, one haptic.
  it('selects a category saved from its edit sheet without a second, selection haptic', async () => {
    renderPicker({ selectedName: 'Food' });
    await settle();
    await screen.findByTestId('category-Food');
    fireEvent.changeText(screen.getByLabelText('Search categories'), 'Subscriptions');
    await settle();
    fireEvent.press(screen.getByText('Create "Subscriptions"'));
    await settle();

    const saved: CategoryOption = { id: 'c-9', name: 'Subscriptions', isSystem: false, icon: 'tag', color: 'gray' };
    jest.useFakeTimers();
    try {
      act(() => { screen.UNSAFE_getByType(CategoryEditSheet).props.onSaved(saved); });
      act(() => { jest.advanceTimersByTime(NESTED_SHEET_DISMISS_MS); });
    } finally {
      jest.useRealTimers();
    }

    expect(onSelect).toHaveBeenCalledWith(saved);
    expectHaptics({});
  });

  // Reproduced on the iOS simulator (2026-10-16): the edit sheet is a native Modal inside this
  // one, and unmounting it in the same commit that the caller closes this picker left an
  // invisible modal layer over the app that swallowed every touch until the app was killed. A
  // tester hit it creating a category from Quick sort's More….
  describe('after a category is created from the edit sheet', () => {
    const saved: CategoryOption = { id: 'c-9', name: 'Subscriptions', isSystem: false, icon: 'tag', color: 'gray' };

    async function createAndSave() {
      renderPicker();
      await settle();
      await screen.findByTestId('category-Food');
      fireEvent.changeText(screen.getByLabelText('Search categories'), 'Subscriptions');
      await settle();
      fireEvent.press(screen.getByText('Create "Subscriptions"'));
      await settle();
      jest.useFakeTimers();
      act(() => { screen.UNSAFE_getByType(CategoryEditSheet).props.onSaved(saved); });
    }

    afterEach(() => { jest.useRealTimers(); });

    it('closes the edit sheet first, and selects and closes only once it has gone', async () => {
      await createAndSave();
      expect(screen.UNSAFE_queryByType(CategoryEditSheet)).toBeNull();
      expect(onSelect).not.toHaveBeenCalled();
      expect(onClose).not.toHaveBeenCalled();

      act(() => { jest.advanceTimersByTime(NESTED_SHEET_DISMISS_MS - 1); });
      expect(onSelect).not.toHaveBeenCalled();

      act(() => { jest.advanceTimersByTime(1); });
      expect(onSelect).toHaveBeenCalledTimes(1);
      expect(onSelect).toHaveBeenCalledWith(saved);
      expect(onClose).toHaveBeenCalledTimes(1);
    });

    it('selects only what was tapped when a row is picked during the wait', async () => {
      await createAndSave();
      // The search still holds the new category's name; cleared, the other rows come back.
      fireEvent.changeText(screen.getByLabelText('Search categories'), '');
      fireEvent.press(screen.getByTestId('category-Travel'));
      act(() => { jest.advanceTimersByTime(NESTED_SHEET_DISMISS_MS * 2); });
      expect(onSelect).toHaveBeenCalledTimes(1);
      expect(onSelect).toHaveBeenCalledWith(TRAVEL);
    });

    it('selects nothing when the picker is closed during the wait', async () => {
      await createAndSave();
      fireEvent.press(screen.getByText('Done'));
      act(() => { jest.advanceTimersByTime(NESTED_SHEET_DISMISS_MS * 2); });
      expect(onSelect).not.toHaveBeenCalled();
    });

    it('selects nothing once the picker has unmounted', async () => {
      await createAndSave();
      screen.unmount();
      act(() => { jest.advanceTimersByTime(NESTED_SHEET_DISMISS_MS * 2); });
      expect(onSelect).not.toHaveBeenCalled();
    });
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

  // Regression: this row used to require query.trim().length > 0, so the only way to discover
  // category creation was to already know to type a new name first -- opening the picker and just
  // looking at the list showed nothing inviting you to create one. Matches web's CategoryCombobox,
  // whose own "+ New category" row is visible before anything is typed.
  it('offers to create a category before anything is typed', async () => {
    renderPicker();
    await settle();
    await screen.findByTestId('category-Food');

    expect(screen.getByText('New category')).toBeTruthy();
    expect(screen.getByLabelText('Create a new category')).toBeTruthy();
  });

  it('does not offer the empty-query create row when allowManage is false', async () => {
    renderPicker({ allowManage: false });
    await settle();
    await screen.findByTestId('category-Food');

    expect(screen.queryByText('New category')).toBeNull();
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

  it('defaults the header to "Category" but accepts a caller-supplied title', async () => {
    renderPicker();
    await settle();
    expect(screen.getByText('Category')).toBeTruthy();

    renderPicker({ title: 'Apply to 5 transactions' });
    await settle();
    expect(screen.getByText('Apply to 5 transactions')).toBeTruthy();
  });
});
