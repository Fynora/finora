import { describe, it, expect } from 'vitest';
import { NAV_TAXONOMY, NAV_DESTINATION_IDS } from './taxonomy';

describe('nav taxonomy', () => {
  it('has exactly one home destination, in the root group', () => {
    const root = NAV_TAXONOMY.filter((e) => e.group === 'root');
    expect(root).toHaveLength(1);
    expect(root[0].id).toBe('home');
  });

  it('assigns every destination to exactly one group', () => {
    const ids = NAV_TAXONOMY.map((e) => e.id);
    expect(new Set(ids).size).toBe(ids.length);
  });

  it('exposes the same ids as NAV_DESTINATION_IDS', () => {
    expect([...NAV_DESTINATION_IDS].sort()).toEqual(NAV_TAXONOMY.map((e) => e.id).sort());
  });

  it('covers all five groups plus root', () => {
    expect(new Set(NAV_TAXONOMY.map((e) => e.group))).toEqual(
      new Set(['root', 'money', 'statements', 'planning', 'analysis', 'your-account']),
    );
  });
});
