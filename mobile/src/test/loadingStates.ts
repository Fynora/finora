import { ActivityIndicator } from 'react-native';
import { screen } from '@testing-library/react-native';

/** A query function result that never settles, so the screen stays in its first load. */
export function never<T>(): Promise<T> {
  return new Promise<T>(() => {});
}

/** First load: at least one "Loading" placeholder group, and no spinner anywhere on screen. */
export function expectSkeletonNotSpinner(): void {
  expect(screen.getAllByLabelText('Loading').length).toBeGreaterThan(0);
  expect(screen.UNSAFE_queryAllByType(ActivityIndicator)).toHaveLength(0);
}
