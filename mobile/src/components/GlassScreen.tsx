import { Image, StyleSheet, View, type ViewProps } from 'react-native';
import { useTheme } from '../theme';
import { dark } from '../theme/palette';
import { useReduceTransparency } from '../lib/useReduceTransparency';

const MESH = {
  light: require('../../assets/glass/mesh-light.png'),
  dark: require('../../assets/glass/mesh-dark.png'),
};

/**
 * Screen root for the glass redesign: opaque `c.bg` (so native-stack transitions and anything
 * behind a screen never show through) plus the static mesh backdrop glass surfaces read against.
 * Drop-in for the `<View style={[..., { backgroundColor: c.bg }]}>` root every screen had.
 *
 * Never use this for the lock cover or anything that must hide content -- those keep plain c.bg
 * (marked `glass-exempt`, see glassMigration.test.ts).
 *
 * The scheme comes from palette identity rather than useThemeSetting(): that hook throws without
 * a ThemeProvider, while useTheme() deliberately falls back to the system scheme so screen tests
 * render without one (see ThemeContext.tsx). Both paths hand back the exact `dark`/`light`
 * module objects, so identity is a reliable tell.
 */
export function GlassScreen({ style, children, ...rest }: ViewProps) {
  const c = useTheme();
  const solid = useReduceTransparency() !== false; // null (unknown) renders solid too
  return (
    <View {...rest} style={[style, { backgroundColor: c.bg }]}>
      {solid ? null : (
        // The wrapper View carries the "decorative" props: RN 0.86's Image has no pointerEvents
        // prop (it is a View prop / ViewStyle key), and the two accessibility props hide the
        // whole backdrop from screen readers in one place.
        <View
          testID="glass-mesh"
          style={StyleSheet.absoluteFill}
          pointerEvents="none"
          accessibilityElementsHidden
          importantForAccessibility="no-hide-descendants"
        >
          <Image
            source={c === dark ? MESH.dark : MESH.light}
            resizeMode="cover"
            style={StyleSheet.absoluteFill}
            // `false` on purpose: under iOS Smart Invert the whole screen inverts, text included.
            // A backdrop that opted out would stay light under now-light text. It must invert
            // with everything else to keep the text/background relationship the contrast test
            // measured.
            accessibilityIgnoresInvertColors={false}
          />
        </View>
      )}
      {children}
    </View>
  );
}
