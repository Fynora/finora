import { Pressable, StyleSheet, Text, View } from 'react-native';
import { GRIEVANCE_EMAIL, GRIEVANCE_MAILTO, GRIEVANCE_OFFICER_NAME } from '../../lib/contact';
import { openMailto } from '../../lib/openMailto';
import { LegalLink } from '../LegalLink';
import { spacing, useTheme } from '../../theme';

/**
 * The standalone notice: what Fynora collects, why, how to withdraw, and where to complain
 * (privacy workstream 8, docs/superpowers/specs/2026-10-31-ai-consent-and-terms-acceptance-design.md).
 * Shown on RegisterScreen and on ConsentScreen. The same text as the web app's
 * frontend/src/components/consent/ConsentNotice.tsx; draft wording for counsel to review.
 */
export function ConsentNotice() {
  const c = useTheme();
  const heading = [styles.heading, { color: c.ink }];
  const body = [styles.body, { color: c.muted }];
  return (
    <View testID="consent-notice">
      <Text style={heading}>What Fynora uses, and why</Text>
      <Text style={body}>• Your name, email and mobile number — to create your account, sign you in and verify your phone.</Text>
      <Text style={body}>
        • The accounts, statements and transactions you add or import — to show your dashboards, budgets and
        reports. Fynora never sells them or uses them for advertising.
      </Text>
      <Text style={body}>• Sign-in and device details (time, IP address, device or browser) — to keep your account secure.</Text>
      <Text style={body}>• For a sign-up on the website, the site or campaign link you came from — to learn what brings people to Fynora.</Text>
      <Text style={heading}>Optional: AI features</Text>
      <Text style={body}>
        Ask Fyn, AI insights and AI category suggestions send the question you ask, the account figures needed
        to answer it, or the description of a transaction you add by hand to Anthropic (Claude). Names and
        account numbers Fynora recognises are hidden first. They stay off unless you tick the box below.
      </Text>
      <Text style={heading}>Your choices</Text>
      <Text style={body}>
        Turn AI features on or off at any time in Settings → Data. You can also export your data or delete
        your account there; deleting it is how you withdraw consent to everything else.
      </Text>
      <Text style={body}>
        Questions or complaints: {GRIEVANCE_OFFICER_NAME}, Grievance Officer. If you are not satisfied with the
        answer, you can complain to the Data Protection Board of India.
      </Text>
      <View style={styles.links}>
        <Pressable onPress={() => openMailto(GRIEVANCE_MAILTO, GRIEVANCE_EMAIL)} hitSlop={6} accessibilityRole="link" style={styles.mail}>
          <Text style={[styles.mailLabel, { color: c.primary }]}>{GRIEVANCE_EMAIL}</Text>
        </Pressable>
        <LegalLink label="Privacy Policy" path="/privacy" />
      </View>
    </View>
  );
}

const styles = StyleSheet.create({
  heading: { fontSize: 13, fontWeight: '700', marginTop: spacing.sm, marginBottom: 2 },
  body: { fontSize: 13, lineHeight: 18, marginBottom: 4 },
  links: { flexDirection: 'row', flexWrap: 'wrap', columnGap: spacing.md },
  mail: { paddingVertical: 8 },
  mailLabel: { fontSize: 13, lineHeight: 18, fontWeight: '600' },
});
