import { Pressable, StyleSheet, Text, View } from 'react-native';
import { LegalLink } from '../LegalLink';
import { spacing, useTheme } from '../../theme';

const TERMS_LABEL = 'I am 18 or older and agree to the Terms of Service and Privacy Policy (required)';
const AI_LABEL = 'Use AI features: Ask Fyn, AI insights and AI category suggestions (optional)';

function Box({ checked, label, onToggle, testID }: {
  checked: boolean; label: string; onToggle: () => void; testID: string;
}) {
  const c = useTheme();
  return (
    <Pressable
      testID={testID}
      onPress={onToggle}
      style={styles.row}
      accessibilityRole="checkbox"
      accessibilityState={{ checked }}
      accessibilityLabel={label}
    >
      <View style={[styles.box, { borderColor: checked ? c.primary : c.border, backgroundColor: checked ? c.primary : 'transparent' }]}>
        {checked ? <Text style={[styles.tick, { color: c.onPrimary }]}>✓</Text> : null}
      </View>
      <Text style={[styles.label, { color: c.ink }]}>{label}</Text>
    </Pressable>
  );
}

/**
 * The two boxes, neither ticked to start with (V302): the required "18 or older and agree" one --
 * left out when this user has already accepted the current version -- and the optional AI one.
 * Same wording as the web app's ConsentChoices.
 */
export function ConsentChoices({
  showTermsBox, termsAccepted, onTermsAcceptedChange, aiEnabled, onAiEnabledChange,
}: {
  showTermsBox: boolean;
  termsAccepted: boolean;
  onTermsAcceptedChange: (value: boolean) => void;
  aiEnabled: boolean;
  onAiEnabledChange: (value: boolean) => void;
}) {
  return (
    <View>
      {showTermsBox ? (
        <>
          <Box testID="consent-terms-box" checked={termsAccepted} label={TERMS_LABEL} onToggle={() => onTermsAcceptedChange(!termsAccepted)} />
          <View style={styles.links}>
            <LegalLink label="Terms of Service" path="/terms" />
            <LegalLink label="Privacy Policy" path="/privacy" />
          </View>
        </>
      ) : null}
      <Box testID="consent-ai-box" checked={aiEnabled} label={AI_LABEL} onToggle={() => onAiEnabledChange(!aiEnabled)} />
    </View>
  );
}

const styles = StyleSheet.create({
  row: { flexDirection: 'row', alignItems: 'flex-start', gap: spacing.sm, paddingVertical: spacing.sm },
  box: { width: 22, height: 22, borderRadius: 6, borderWidth: 2, alignItems: 'center', justifyContent: 'center', marginTop: 1 },
  tick: { fontSize: 14, fontWeight: '700', lineHeight: 16 },
  label: { flex: 1, fontSize: 14, lineHeight: 20 },
  links: { flexDirection: 'row', flexWrap: 'wrap', columnGap: spacing.md, paddingLeft: 30 },
});
