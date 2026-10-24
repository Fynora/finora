import { describe, it, expect } from 'vitest';
import { CHECKLIST_ITEMS } from './checklistItems';

describe('checklist items', () => {
  // Gate 2b PR 2 (spec §4): the import item asks for one statement, in the import screen's words.
  it('checklistUsesNewLabel', () => {
    expect(CHECKLIST_ITEMS.find((i) => i.key === 'IMPORT_STATEMENT')?.label).toBe('Start with one statement');
  });
});
