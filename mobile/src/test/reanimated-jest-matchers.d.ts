// react-native-reanimated@4.7.0 moved its Jest matcher type augmentation
// (toHaveAnimatedStyle/toHaveAnimatedProps) into jestUtils/index.native.d.ts. Its package.json
// "types" field still points only at the platform-agnostic jestUtils/index.d.ts, which no longer
// declares them, and there's no "exports" "react-native" condition for `tsc` (moduleResolution
// "bundler") to pick the native variant instead -- so plain `tsc --noEmit` never sees this
// augmentation upstream, even though the matchers exist and work at runtime via setUpTests().
// Mirrors node_modules/react-native-reanimated/lib/typescript/jestUtils/index.native.d.ts until
// upstream's own index.d.ts re-exposes this.
declare global {
  namespace jest {
    interface Matchers<R> {
      toHaveAnimatedStyle(
        style: Record<string, unknown>[] | Record<string, unknown>,
        config?: { shouldMatchAllProps?: boolean }
      ): R;
      toHaveAnimatedProps(props: Record<string, unknown>): R;
    }
  }
}

export {};
