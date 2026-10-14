import { describe, expect, it } from 'vitest';
import { NO_ANCHORS, asWholeListPage, pagingSignature, rememberAnchors, requestFor } from './ledgerPaging';

const row = (id: string) => ({ id });

describe('ledgerPaging', () => {
  describe('pagingSignature', () => {
    it('is the same for two requests that differ only in the page asked for', () => {
      expect(pagingSignature({ page: 0, size: 10, sortField: 'date', sortDir: 'desc' }))
        .toBe(pagingSignature({ page: 4, size: 10, sortField: 'date', sortDir: 'desc', after: 'x' }));
    });

    it('changes when anything that changes the list changes', () => {
      const base = pagingSignature({ page: 0, size: 10, sortField: 'date', sortDir: 'desc' });
      expect(pagingSignature({ page: 0, size: 20, sortField: 'date', sortDir: 'desc' })).not.toBe(base);
      expect(pagingSignature({ page: 0, size: 10, sortField: 'date', sortDir: 'asc' })).not.toBe(base);
      expect(pagingSignature({ page: 0, size: 10, sortField: 'date', sortDir: 'desc', keyword: 'rent' })).not.toBe(base);
    });

    it('does not depend on the order the filters were set in, or on unset ones', () => {
      expect(pagingSignature({ size: 10, type: 'EXPENSE', keyword: undefined }))
        .toBe(pagingSignature({ type: 'EXPENSE', size: 10 }));
    });
  });

  describe('rememberAnchors', () => {
    it('records the last row of a page as the place the next page continues from', () => {
      const page = { content: [row('a'), row('b')], page: 0, size: 2, totalElements: 5, totalPages: 3 };

      expect(rememberAnchors(NO_ANCHORS, 'sig', page)).toEqual({ signature: 'sig', afterByPage: { 1: 'b' } });
    });

    it('keeps the places already known for the same list', () => {
      const known = { signature: 'sig', afterByPage: { 1: 'b' } };
      const page = { content: [row('c'), row('d')], page: 1, size: 2, totalElements: 5, totalPages: 3 };

      expect(rememberAnchors(known, 'sig', page)).toEqual({ signature: 'sig', afterByPage: { 1: 'b', 2: 'd' } });
    });

    it('forgets every place when the list itself changed', () => {
      const known = { signature: 'old', afterByPage: { 1: 'b', 2: 'd' } };
      const page = { content: [row('x')], page: 0, size: 2, totalElements: 3, totalPages: 2 };

      expect(rememberAnchors(known, 'new', page)).toEqual({ signature: 'new', afterByPage: { 1: 'x' } });
    });

    it('returns the same object when there is nothing new to record', () => {
      const known = { signature: 'sig', afterByPage: { 1: 'b' } };
      const page = { content: [row('a'), row('b')], page: 0, size: 2, totalElements: 5, totalPages: 3 };

      expect(rememberAnchors(known, 'sig', page)).toBe(known);
    });

    it('records nothing for a page with no rows', () => {
      const known = { signature: 'sig', afterByPage: { 1: 'b' } };
      const page = { content: [], page: 1, size: 2, totalElements: 2, totalPages: 1 };

      expect(rememberAnchors(known, 'sig', page)).toBe(known);
    });
  });

  describe('requestFor', () => {
    const filters = { page: 1, size: 2, sortField: 'date', sortDir: 'desc' };

    it('names the last row of the page before, when it is known for this list', () => {
      const anchors = { signature: pagingSignature(filters), afterByPage: { 1: 'b' } };

      expect(requestFor(filters, anchors)).toEqual({ ...filters, after: 'b' });
    });

    it('asks by page number alone when the page before was never seen', () => {
      const anchors = { signature: pagingSignature(filters), afterByPage: { 1: 'b' } };

      expect(requestFor({ ...filters, page: 5 }, anchors)).toEqual({ ...filters, page: 5 });
    });

    it('asks by page number alone when the places belong to a different list', () => {
      const anchors = { signature: pagingSignature({ ...filters, keyword: 'rent' }), afterByPage: { 1: 'b' } };

      expect(requestFor(filters, anchors)).toEqual(filters);
    });

    it('never names a row for the first page', () => {
      const anchors = { signature: pagingSignature(filters), afterByPage: { 0: 'zzz', 1: 'b' } };

      expect(requestFor({ ...filters, page: 0 }, anchors)).toEqual({ ...filters, page: 0 });
    });
  });

  describe('asWholeListPage', () => {
    it('turns "what is left after that row" back into a page of the whole list', () => {
      // Asked for page 2 (the third) of pages of 10, continued from a row: 14 rows are left.
      const remainder = { content: [row('u')], page: 0, size: 10, totalElements: 14, totalPages: 2 };

      expect(asWholeListPage(remainder, 2, true)).toEqual({
        content: [row('u')], page: 2, size: 10, totalElements: 34, totalPages: 4,
      });
    });

    it('reports a page past the end when nothing is left after that row', () => {
      const nothingLeft = { content: [], page: 0, size: 10, totalElements: 0, totalPages: 0 };

      // page >= totalPages is what the Ledger's "step back to the last real page" effect watches.
      expect(asWholeListPage(nothingLeft, 3, true)).toMatchObject({ page: 3, totalPages: 3, totalElements: 30 });
    });

    it('leaves alone an answer that is already a page of the whole list', () => {
      // A server that does not know `after`, or was sent a row that is not the caller's own.
      const byNumber = { content: [row('u')], page: 2, size: 10, totalElements: 34, totalPages: 4 };

      expect(asWholeListPage(byNumber, 2, true)).toBe(byNumber);
    });

    it('leaves alone the answer to a request that named no row', () => {
      const first = { content: [row('a')], page: 0, size: 10, totalElements: 34, totalPages: 4 };

      expect(asWholeListPage(first, 0, false)).toBe(first);
    });
  });
});
