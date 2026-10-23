import { useEffect, useRef, useState, type ReactNode } from 'react';
import { Image, StyleSheet, Text, View, type ImageStyle, type StyleProp, type ViewStyle } from 'react-native';

interface MerchantLogoProps {
  /** The name shown: used for the initials badge and the accessibility label, never sent anywhere. */
  merchant: string;
  /** The web address to ask Logo.dev for: the server's `logoDomain`, chosen from its own brand list
   *  (backend MerchantBrands). Null or absent means no request at all, only initials. */
  domain?: string | null;
  size?: number;
  /** Custom content to show when Logo.dev has no logo for this merchant (or is unconfigured).
   *  Rendered as-is, with no wrapper of its own -- the caller owns its container. Omit to get
   *  this component's own self-contained colored-initials badge instead. */
  fallback?: ReactNode;
  style?: StyleProp<ViewStyle>;
}

const LOGODEV_TOKEN = process.env.EXPO_PUBLIC_LOGODEV_TOKEN;
// Same budget as web's MerchantLogo/BankLogo -- see those components' own comments for why.
const LOGODEV_TIMEOUT_MS = 1500;

/** A bare lowercase domain such as `swiggy.com`. Anything else -- a name, a narration, a path --
 *  is refused, so a caller passing the wrong field still cannot send text to Logo.dev. */
const BARE_DOMAIN = /^[a-z0-9-]+(\.[a-z0-9-]+)+$/;

export function logoDevUrl(domain: string | null | undefined, sizePx: number, token: string | undefined): string | null {
  const d = domain?.trim();
  if (!token || !d || !BARE_DOMAIN.test(d)) return null;
  // https://www.logo.dev/docs/logo-images/get -- a bare domain is the default identifier type.
  return `https://img.logo.dev/${d}?token=${token}&size=${sizePx}&format=png&fallback=404`;
}

type Stage = 'logodev' | 'fallback';

function initialsOf(name: string): string {
  const words = name.trim().split(/\s+/).filter(Boolean);
  if (words.length === 0) return '?';
  if (words.length === 1) return words[0].slice(0, 2).toUpperCase();
  return (words[0][0] + words[1][0]).toUpperCase();
}

// Deterministic name -> color, so the same merchant always gets the same badge color across rows
// rather than a new random one on every render.
function colorFor(name: string): string {
  let hash = 0;
  for (let i = 0; i < name.length; i++) hash = (hash * 31 + name.charCodeAt(i)) | 0;
  // 30% lightness so the white initials clear WCAG AA on every hue: at the 40% this used to be,
  // yellow-green hues put white at 2.84:1; the worst hue at 30% is 4.75:1.
  return `hsl(${Math.abs(hash) % 360}, 55%, 30%)`;
}

/**
 * Merchant logo: Logo.dev looked up by the server's `logoDomain` only -> a colored-initials badge,
 * or a caller-supplied fallback. It used to look the merchant's own name up, which sent people's
 * names and bank narration to Logo.dev (measured on the statement corpus, 2026-10-24); now only an
 * address from the server's brand list ever leaves the app. Mirrors web's MerchantLogo, with RN's
 * Image in place of <img>. Each row's Image loads independently and falls back on its own.
 */
export function MerchantLogo({ merchant, domain = null, size = 32, fallback, style }: MerchantLogoProps) {
  const sizePx = Math.max(64, Math.round(size * 2));
  const src = logoDevUrl(domain, sizePx, LOGODEV_TOKEN);

  const [stage, setStage] = useState<Stage>(() => (src ? 'logodev' : 'fallback'));
  const trackedKey = `${domain ?? ''}|${merchant}`;
  const [trackedMerchant, setTrackedMerchant] = useState(trackedKey);
  const timeoutRef = useRef<ReturnType<typeof setTimeout> | null>(null);

  // Reset whenever the merchant itself changes -- e.g. scrolling a transaction list, each row a
  // different merchant -- otherwise a row that previously fell back for one merchant would
  // incorrectly start there for the next. Adjusted during render (React's documented pattern for
  // resetting state in response to a prop change) rather than in an effect, so there's no extra
  // render and no synchronous setState-in-effect.
  if (trackedKey !== trackedMerchant) {
    setTrackedMerchant(trackedKey);
    setStage(src ? 'logodev' : 'fallback');
  }

  useEffect(() => {
    if (stage !== 'logodev') return undefined;
    timeoutRef.current = setTimeout(() => setStage('fallback'), LOGODEV_TIMEOUT_MS);
    return () => { if (timeoutRef.current) clearTimeout(timeoutRef.current); };
  }, [stage, merchant]);

  function clearLogoTimeout() {
    if (timeoutRef.current) { clearTimeout(timeoutRef.current); timeoutRef.current = null; }
  }

  if (stage === 'logodev' && src) {
    return (
      <Image
        source={{ uri: src }}
        accessibilityLabel={merchant}
        accessibilityIgnoresInvertColors
        // ViewStyle and ImageStyle disagree only on `overflow`'s allowed values ('scroll' is
        // View-only) -- style is typed as ViewStyle since that's what the (more common) fallback
        // View below expects; a caller's style is passed through untouched either way.
        style={[{ width: size, height: size, borderRadius: 12 }, style as StyleProp<ImageStyle>]}
        resizeMode="contain"
        onLoad={clearLogoTimeout}
        onError={() => { clearLogoTimeout(); setStage('fallback'); }}
      />
    );
  }

  if (fallback !== undefined) return <>{fallback}</>;

  return (
    <View
      style={[
        styles.fallback,
        { width: size, height: size, backgroundColor: colorFor(merchant || '?') },
        style,
      ]}
      accessibilityLabel={merchant}
    >
      <Text style={[styles.fallbackText, { fontSize: Math.max(9, size * 0.34) }]}>
        {initialsOf(merchant || '')}
      </Text>
    </View>
  );
}

const styles = StyleSheet.create({
  fallback: { borderRadius: 12, alignItems: 'center', justifyContent: 'center' },
  fallbackText: { fontWeight: '700', color: '#FFFFFF' },
});
