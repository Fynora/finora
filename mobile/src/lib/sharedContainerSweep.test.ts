import AsyncStorage from '@react-native-async-storage/async-storage';
import { Directory, File, Paths } from 'expo-file-system';
import { purgeSharedContainers, sweepSharedContainers } from './sharedContainerSweep';

const ONE_HOUR_MS = 60 * 60 * 1000;
const FIRST_SEEN_KEY = 'finora_shared_container_first_seen';

/**
 * A unique App Group directory per test, not a reset between them: the expo-file-system jest mock
 * keeps one in-memory store for this whole file (see fileCacheSweep.test.ts for the same
 * reasoning), so a shared directory would carry one test's files into the next.
 */
let containerCounter = 0;
let container: Directory;

function fileIn(name: string): File {
  const file = new File(container, name);
  file.create();
  file.write('x');
  return file;
}

async function firstSeen(): Promise<Record<string, number>> {
  return JSON.parse((await AsyncStorage.getItem(FIRST_SEEN_KEY)) ?? '{}');
}

beforeEach(() => {
  containerCounter += 1;
  container = new Directory(`file:///mock/appgroup-${containerCounter}/`);
  container.create({ idempotent: true, intermediates: true });
  jest.spyOn(Paths, 'appleSharedContainers', 'get').mockReturnValue({ 'group.com.fynora.app': container });
});

afterEach(() => {
  jest.restoreAllMocks();
});

describe('sweepSharedContainers', () => {
  // The bug the whole first-seen design exists to prevent: FileManager.copyItem keeps the source's
  // modification time, so a just-shared statement can look arbitrarily old. Nothing here reads a
  // modification time, so the clock being far ahead of the file must not matter on a first sighting.
  it('never deletes a file the first time it sees it, however far ahead the clock is', async () => {
    const file = fileIn('just-shared.csv');

    await sweepSharedContainers(Date.now() + 1000 * ONE_HOUR_MS);

    expect(file.exists).toBe(true);
    expect(Object.keys(await firstSeen())).toEqual(['just-shared.csv']);
  });

  it('keeps a file at exactly the max age and deletes it one millisecond past', async () => {
    const file = fileIn('aging.pdf');
    const t0 = 1_000_000;
    await sweepSharedContainers(t0);

    await sweepSharedContainers(t0 + ONE_HOUR_MS);
    expect(file.exists).toBe(true);

    await sweepSharedContainers(t0 + ONE_HOUR_MS + 1);
    expect(file.exists).toBe(false);
    expect(await firstSeen()).toEqual({});
  });

  it('does not delete a file that arrived later in the same sweep that deletes an older one', async () => {
    const old = fileIn('old.csv');
    const t0 = 5_000_000;
    await sweepSharedContainers(t0);
    const fresh = fileIn('fresh.pdf');

    await sweepSharedContainers(t0 + ONE_HOUR_MS + 1);

    expect(old.exists).toBe(false);
    expect(fresh.exists).toBe(true);
  });

  it('only ever touches pdf and csv files: never a dotfile, another type, or a directory', async () => {
    const metadata = fileIn('.com.apple.mobile_container_manager.metadata.plist');
    const dotCsv = fileIn('.hidden.csv');
    const other = fileIn('notes.txt');
    const image = fileIn('photo.png');
    const library = new Directory(container, 'Library');
    library.create({ idempotent: true });
    const t0 = 9_000_000;
    await sweepSharedContainers(t0);

    await sweepSharedContainers(t0 + 10 * ONE_HOUR_MS);

    for (const survivor of [metadata, dotCsv, other, image]) expect(survivor.exists).toBe(true);
    expect(library.exists).toBe(true);
    expect(await firstSeen()).toEqual({});
  });

  it('matches the extension case-insensitively', async () => {
    const file = fileIn('STATEMENT.PDF');
    const t0 = 2_000_000;
    await sweepSharedContainers(t0);

    await sweepSharedContainers(t0 + ONE_HOUR_MS + 1);

    expect(file.exists).toBe(false);
  });

  it('forgets a file that vanished on its own, so the record cannot grow forever', async () => {
    const file = fileIn('gone.csv');
    await sweepSharedContainers(1);
    file.delete();

    await sweepSharedContainers(2);

    expect(await firstSeen()).toEqual({});
  });

  it('treats a corrupt record as empty, so nothing is deleted on that run', async () => {
    const file = fileIn('kept.csv');
    await AsyncStorage.setItem(FIRST_SEEN_KEY, '{not json');

    await sweepSharedContainers(Date.now() + 100 * ONE_HOUR_MS);

    expect(file.exists).toBe(true);
  });

  it('ignores non-numeric entries in the record rather than trusting them', async () => {
    const file = fileIn('tampered.csv');
    await AsyncStorage.setItem(FIRST_SEEN_KEY, JSON.stringify({ 'tampered.csv': 'yesterday' }));

    await sweepSharedContainers(Date.now() + 100 * ONE_HOUR_MS);

    expect(file.exists).toBe(true);
  });

  it('is a no-op where there is no App Group (Android): touches nothing and does not throw', async () => {
    jest.spyOn(Paths, 'appleSharedContainers', 'get').mockReturnValue({});
    const file = fileIn('elsewhere.csv');

    await expect(sweepSharedContainers(Date.now() + 100 * ONE_HOUR_MS)).resolves.toBeUndefined();

    expect(file.exists).toBe(true);
  });

  it('does not throw when a container cannot be listed', async () => {
    jest.spyOn(Paths, 'appleSharedContainers', 'get').mockReturnValue({
      broken: { list: () => { throw new Error('unreadable'); } } as unknown as Directory,
    });

    await expect(sweepSharedContainers()).resolves.toBeUndefined();
  });

  it('keeps tracking a file it could not delete, so a later sweep retries it', async () => {
    const file = fileIn('stuck.csv');
    const t0 = 3_000_000;
    await sweepSharedContainers(t0);
    const deleteSpy = jest.spyOn(File.prototype, 'delete').mockImplementationOnce(() => {
      throw new Error('busy');
    });

    await sweepSharedContainers(t0 + ONE_HOUR_MS + 1);
    expect(file.exists).toBe(true);
    expect(await firstSeen()).toEqual({ 'stuck.csv': t0 });

    deleteSpy.mockRestore();
    await sweepSharedContainers(t0 + ONE_HOUR_MS + 2);
    expect(file.exists).toBe(false);
  });

  it('does not throw with the real clock', async () => {
    await expect(sweepSharedContainers()).resolves.toBeUndefined();
  });
});

describe('purgeSharedContainers', () => {
  it('deletes every shared statement immediately, with no age margin, and clears the record', async () => {
    const pdf = fileIn('a.pdf');
    const csv = fileIn('b.csv');
    await sweepSharedContainers(1);
    expect(Object.keys(await firstSeen())).toHaveLength(2);

    await purgeSharedContainers();

    expect(pdf.exists).toBe(false);
    expect(csv.exists).toBe(false);
    expect(await AsyncStorage.getItem(FIRST_SEEN_KEY)).toBeNull();
  });

  it('leaves everything that is not a shared statement alone', async () => {
    const metadata = fileIn('.com.apple.mobile_container_manager.metadata.plist');
    const other = fileIn('notes.txt');
    const library = new Directory(container, 'Library');
    library.create({ idempotent: true });

    await purgeSharedContainers();

    expect(metadata.exists).toBe(true);
    expect(other.exists).toBe(true);
    expect(library.exists).toBe(true);
  });

  it('keeps going when one file cannot be deleted', async () => {
    const stuck = fileIn('stuck.csv');
    const fine = fileIn('fine.pdf');
    jest.spyOn(File.prototype, 'delete').mockImplementationOnce(() => {
      throw new Error('busy');
    });

    await expect(purgeSharedContainers()).resolves.toBeUndefined();

    expect([stuck.exists, fine.exists].filter(Boolean)).toHaveLength(1);
  });

  it('is a no-op where there is no App Group (Android)', async () => {
    jest.spyOn(Paths, 'appleSharedContainers', 'get').mockReturnValue({});
    const file = fileIn('elsewhere.csv');

    await expect(purgeSharedContainers()).resolves.toBeUndefined();

    expect(file.exists).toBe(true);
  });
});
