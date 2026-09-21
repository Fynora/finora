import { GMAIL_SYNC_UI_ENABLED } from './features';

// Every other test that touches this flag mocks it, so that both states can be exercised. This is the
// one that reads the REAL shipped value: it fails the moment someone flips the constant, which is the
// point. Gmail sync is paused until Google's annual CASA assessment is funded. To switch it back on,
// follow docs/engineering/gmail-sync-paused.md, which includes updating this test.
test('Gmail sync is paused in the shipped build', () => {
  expect(GMAIL_SYNC_UI_ENABLED).toBe(false);
});
