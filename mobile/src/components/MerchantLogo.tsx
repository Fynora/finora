import { useEffect, useRef, useState, type ReactNode } from 'react';
import { Image, StyleSheet, Text, View, type StyleProp, type ViewStyle } from 'react-native';

interface MerchantLogoProps {
  merchant: string;
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

export function logoDevUrl(merchant: string, sizePx: number, token: string | undefined): string | null {
  const name = merchant?.trim();
  if (!token || !name) return null;
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
export function MerchantLogo({ merchant, size = 32, fallback, style }: MerchantLogoProps) {
  const sizePx = Math.max(64, Math.round(size * 2));
  const src = logoDevUrl(merchant, sizePx, LOGODEV_TOKEN);

  const [stage, setStage] = useState<Stage>(() => (src ? 'logodev' : 'fallback'));
  const timeoutRef = useRef<ReturnType<typeof setTimeout> | null>(null);

  // Reset whenever the merchant itself changes -- e.g. scrolling a transaction list, each row a
  // different merchant -- otherwise a row that previously fell back for one merchant would
  // incorrectly start there for the next.
  useEffect(() => {
    setStage(src ? 'logodev' : 'fallback');
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [merchant]);

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
        style={[{ width: size, height: size, borderRadius: 12 }, style]}
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
