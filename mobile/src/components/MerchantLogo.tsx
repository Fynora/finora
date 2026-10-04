import { useEffect, useRef, useState, type ReactNode } from 'react';
import { Image, StyleSheet, Text, View, type ImageStyle, type StyleProp, type ViewStyle } from 'react-native';

interface MerchantLogoProps {
  merchant: string;
  size?: number;
  /** Custom content to show when Logo.dev has no logo for this merchant (or is unconfigured).
   *  Rendered as-is, with no wrapper of its own -- the caller owns its container. Omit to get
   *  this component's own self-contained colored-initials badge instead. */
  fallback?: ReactNode;
  style?: StyleProp<ViewStyle>;
  /** The counterparty is a person (a payment to or from a friend). A person has no brand logo, and
   *  a Logo.dev name search for a person's name returns whichever company matches best -- so no
   *  lookup is made. Mirrors web's MerchantLogo. */
  person?: boolean;
}

const LOGODEV_TOKEN = process.env.EXPO_PUBLIC_LOGODEV_TOKEN;
// Same budget as web's MerchantLogo/BankLogo -- see those components' own comments for why.
const LOGODEV_TIMEOUT_MS = 1500;

/** The label the server gives every interest credit (backend CategoryRules.INTEREST_LABEL). It
 *  names what the money is, not who paid it, so it is no brand to look up: a Logo.dev name search
 *  returns whichever company matches the word best. Mirrors web's MerchantLogo. */
export const INTEREST_LABEL = 'interest';

export function logoDevUrl(merchant: string, sizePx: number, token: string | undefined): string | null {
  const name = merchant?.trim();
  if (!token || !name || name.toLowerCase() === INTEREST_LABEL) return null;
  // https://www.logo.dev/docs/logo-images/get -- `name/` is the explicit identifier type for a
  // bare company name (unlike a domain, which needs no prefix).
  return `https://img.logo.dev/name/${encodeURIComponent(name)}?token=${token}&size=${sizePx}&format=png&fallback=404`;
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
  return `hsl(${Math.abs(hash) % 360}, 55%, 40%)`;
}

/**
 * Merchant-name logo resolution: Logo.dev (looked up by `Transaction.merchant`, a free-text name
 * with no domain field anywhere on the transaction) -> a colored-initials badge, or a
 * caller-supplied fallback. Mobile port of frontend/src/components/MerchantLogo.tsx -- same
 * mechanism, same reasoning, RN's Image in place of <img>.
 *
 * Deliberately no circuit breaker, unlike a future mobile BankLogo. Most rows in a real ledger are
 * cash withdrawals, UPI/IMPS references, or small vendors Logo.dev's catalog was never going to
 * have -- a miss there is the ordinary, expected outcome for THAT merchant, not evidence the whole
 * integration is broken. Each row's Image loads independently and falls back on its own.
 */
/** The Logo.dev URL to try for this row, or null when none should be requested: no token, no
 *  name, or a person (see MerchantLogoProps.person). */
export function logoSourceFor(merchant: string, sizePx: number, token: string | undefined,
                              person: boolean): string | null {
  return person ? null : logoDevUrl(merchant, sizePx, token);
}

export function MerchantLogo({ merchant, size = 32, fallback, style, person = false }: MerchantLogoProps) {
  const sizePx = Math.max(64, Math.round(size * 2));
  const src = logoSourceFor(merchant, sizePx, LOGODEV_TOKEN, person);

  const [stage, setStage] = useState<Stage>(() => (src ? 'logodev' : 'fallback'));
  const trackedKey = `${person ? 'person' : 'business'}|${merchant}`;
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
