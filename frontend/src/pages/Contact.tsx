import { Link } from 'react-router-dom';
import { PublicLayout, PublicSection } from '../components/PublicLayout';
import { SUPPORT_EMAIL, SUPPORT_MAILTO, GRIEVANCE_EMAIL, GRIEVANCE_MAILTO, GRIEVANCE_OFFICER_NAME } from '../lib/contact';

export default function Contact() {
  return (
    <PublicLayout
      title="Contact Us"
      subtitle="Reach the Fynora team for support, billing questions, or anything else."
    >
      <PublicSection title="Support">
        <p>
          For account issues, import problems, or general questions, email{' '}
          <a href={SUPPORT_MAILTO} className="text-primary hover:underline">
            {SUPPORT_EMAIL}
          </a>
          . We aim to respond to every inquiry as quickly as we can.
        </p>
      </PublicSection>

      <PublicSection title="Grievance Officer (Data Protection)">
        <p>
          For complaints about how Fynora handles your personal data under India's DPDP Act, contact our
          Grievance Officer, {GRIEVANCE_OFFICER_NAME}, at{' '}
          <a href={GRIEVANCE_MAILTO} className="text-primary hover:underline">
            {GRIEVANCE_EMAIL}
          </a>
          . See our <Link to="/privacy" className="text-primary hover:underline">Privacy Policy</Link> for the
          full grievance redressal process.
        </p>
      </PublicSection>

      <PublicSection title="Legal Entity & Registered Office">
        <p>
          Fynora is operated by Fynora Technovation LLP, registered at 463, Sita Ram Compound, Chaman Ganj,
          Sipri Bazaar, Jhansi, Uttar Pradesh, 284003, India.
        </p>
      </PublicSection>

      <PublicSection title="Response Time">
        <p>
          We aim to respond to all inquiries as quickly as possible. For urgent account-access issues, include
          "urgent" in your subject line.
        </p>
      </PublicSection>
    </PublicLayout>
  );
}
