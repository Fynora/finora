import { Link } from 'react-router-dom';
import { PublicLayout, PublicSection } from '../components/PublicLayout';
import { SUPPORT_EMAIL, SUPPORT_MAILTO, GRIEVANCE_EMAIL, GRIEVANCE_MAILTO, GRIEVANCE_OFFICER_NAME, LEGAL_NAME, REGISTERED_OFFICE_TEXT } from '../lib/contact';

export default function Privacy() {
  return (
    <PublicLayout
      title="Privacy Policy"
      subtitle="Last updated: November 2026. This explains what Fynora collects, why, and the rights you have over it under India's Digital Personal Data Protection Act, 2023 (DPDP Act)."
    >
      <PublicSection title="Who We Are & Your Consent">
        <p>
          Fynora is operated by {LEGAL_NAME}, registered at {REGISTERED_OFFICE_TEXT}. Under the DPDP Act, Fynora Technovation LLP is
          the "Data Fiduciary" for the personal data described in this policy, and you are the "Data
          Principal." We process your personal data on the basis of the consent you give, and when you take
          actions in the app that involve sharing further data (such as importing a statement) — described in
          plain terms in the sections below, as the Act requires.
        </p>
        <p>
          Fynora asks you to tick a box agreeing to the Terms of Service and this policy when you sign up, or,
          for an account created earlier, the next time you use the website or an updated app. We record which
          version you agreed to, when, and whether it was on the website, the iPhone app or the Android app.
        </p>
        <p>
          AI features need a separate yes: they stay off unless you turn them on, and you can turn them off at
          any time without affecting anything else (see AI Features: Your Choice below). So do push notifications
          about offers and new features, which stay off unless you turn on "Offers and news" (see Offers and News
          below). Fynora's other
          features depend on the data you have provided, so withdrawing consent to them means deleting your
          account (see Data Deletion below). Withdrawal does not affect the lawfulness of processing carried
          out before you withdrew it.
        </p>
      </PublicSection>

      <PublicSection id="purposes" title="What We Use, and Why, at a Glance">
        <p>
          A summary of the sections below. Each section has the full detail.
        </p>
        <div className="overflow-x-auto">
          <table className="w-full text-left text-sm">
            <thead>
              <tr>
                <th className="py-1 pr-3 text-ink">Purpose</th>
                <th className="py-1 pr-3 text-ink">Data</th>
                <th className="py-1 text-ink">Your choice</th>
              </tr>
            </thead>
            <tbody>
              <tr>
                <td className="py-1 pr-3 align-top">Your account and signing in</td>
                <td className="py-1 pr-3 align-top">Name, email, mobile number, sign-in times, IP address and device at sign-in</td>
                <td className="py-1 align-top">Needed to have an account</td>
              </tr>
              <tr>
                <td className="py-1 pr-3 align-top">Tracking your money</td>
                <td className="py-1 pr-3 align-top">Accounts, transactions, budgets, goals and the statements you upload</td>
                <td className="py-1 align-top">Needed for the features that use them</td>
              </tr>
              <tr>
                <td className="py-1 pr-3 align-top">Fixing import problems</td>
                <td className="py-1 pr-3 align-top">A technical record of each upload</td>
                <td className="py-1 align-top">Kept for every upload (see Uploaded Statements)</td>
              </tr>
              <tr>
                <td className="py-1 pr-3 align-top">Better category suggestions for everyone</td>
                <td className="py-1 pr-3 align-top">The payee&apos;s UPI ID, the category and the direction of the payment</td>
                <td className="py-1 align-top">Recorded for payees classified as businesses (see Learning From Corrections Across Users)</td>
              </tr>
              <tr>
                <td className="py-1 pr-3 align-top">AI features</td>
                <td className="py-1 pr-3 align-top">Your question and the figures needed to answer it</td>
                <td className="py-1 align-top">Optional: off until you turn it on</td>
              </tr>
              <tr>
                <td className="py-1 pr-3 align-top">Offers and news</td>
                <td className="py-1 pr-3 align-top">The device token of each phone you use the app on</td>
                <td className="py-1 align-top">Optional: off until you turn it on</td>
              </tr>
              <tr>
                <td className="py-1 pr-3 align-top">Opening locked statements for you</td>
                <td className="py-1 pr-3 align-top">A statement password you choose to save</td>
                <td className="py-1 align-top">Optional</td>
              </tr>
              <tr>
                <td className="py-1 pr-3 align-top">Learning where visitors come from</td>
                <td className="py-1 pr-3 align-top">The site and campaign tags that brought you to sign up</td>
                <td className="py-1 align-top">Recorded at sign-up on the website</td>
              </tr>
              <tr>
                <td className="py-1 pr-3 align-top">Keeping your account secure</td>
                <td className="py-1 pr-3 align-top">Security audit logs of account activity</td>
                <td className="py-1 align-top">Kept for every account (see Data Retention)</td>
              </tr>
            </tbody>
          </table>
        </div>
      </PublicSection>

      <PublicSection title="Information We Collect">
        <p>
          Fynora collects the information you provide directly (registration details, account and transaction
          data you add or import, and your answers to the short questions when you first set up Fynora: how you
          keep track of your spending today, which everyone is asked once, and what you hope to achieve with
          Fynora) and a small amount of technical information needed to operate the Service
          securely (login timestamps, IP address at login, device/browser information for session security).
        </p>
        <p>
          When you sign up on the website, Fynora also notes where that visit came from: the name of the
          website that linked you here (its address only, never the page you were on), any campaign tags
          in the link you followed (utm_source, utm_medium, utm_campaign, utm_content), and which public
          page of Fynora you first landed on (its address only, such as /help). We use this only to
          learn which places and pages bring people to Fynora. It is kept with your account and deleted with
          it, and is never used to advertise to you.
        </p>
      </PublicSection>

      <PublicSection title="Personal Information">
        <p>
          This includes your full name, email address, and mobile number, collected at registration and used
          for authentication (including email-or-phone login), account recovery, and OTP-based phone
          verification.
        </p>
      </PublicSection>

      <PublicSection title="Financial Data">
        <p>
          Fynora stores the accounts, transactions, budgets, goals, and categorization data you create or
          import. This data is used exclusively to power the features you use — dashboards, reports, budgets,
          and insights — and is never sold to third parties or used for advertising.
        </p>
      </PublicSection>

      <PublicSection id="uploaded-statements" title="Uploaded Statements">
        <p>
          When you import a bank or credit card statement, the original file is stored securely and linked to
          your account so you can re-download it or re-process it later from Statement History.
        </p>
        <p>
          When we improve how statements are read, Fynora can refresh the transactions from your statements
          using these stored files, so you do not have to upload them again. We tell you in the app, by push
          notification and by email when an improvement would change your statements. A refresh runs when you
          choose to start it, and afterwards shows you what changed: transactions added, corrected or removed.
          If a correction we have already checked is waiting, our support team may also apply it for you; you
          are then told by push notification and by email what changed. Every such action is logged and
          auditable. Either way, a transaction you edited keeps your changes.
        </p>
        <p>
          In most cases statements are processed entirely automatically by Fynora's own rule-based extraction
          logic — Fynora does not send your statement or its contents to third-party AI services such as
          OpenAI, Anthropic, or Google Gemini as part of importing it. (Ask Fyn, a separate optional feature
          described below, does send data to Anthropic if you turn AI features on — but only your own
          already-processed transaction data, never a raw statement file.) When an import cannot be processed automatically, it is queued for
          review, and authorized staff may access the statement to diagnose and fix the problem. Every such
          access is logged and auditable; see Administrative Access below.
        </p>
        <p>
          Each time you upload a statement, Fynora also keeps a short technical record of that attempt: when
          it happened, whether the statement was read, could not be read, or was turned away before it was
          read (for example an empty file, a file of the wrong type, a locked PDF sent without its password,
          or a busy server), and the reason. For a statement that was read or could not be read, the record
          includes the file's name and technical details of its layout, and the reason it could not be read
          may quote a short piece of the text that caused the problem. For an upload turned away before
          reading, the record holds nothing about the file. We use these records to find and fix import
          problems. If you delete your account, they are kept without your identity: the link to your
          account, the file name and the recorded reason text are removed.
        </p>
      </PublicSection>

      <PublicSection id="converter" title="Free Statement Converter">
        <p>
          Fynora offers a free tool at app.fynora.net/bank-statement-to-excel that turns a bank or credit card
          statement into a spreadsheet without an account. When you use it:
        </p>
        <ul className="list-disc pl-5 space-y-1">
          <li>
            You choose to send us one statement file and, if it is locked, its password. We use them only to make
            your spreadsheet.
          </li>
          <li>
            The file is checked for viruses and read by Fynora&apos;s own rule-based software on servers located
            outside India (in the United States; see Cross-Border Data Transfer below). It is not sent to any AI
            service, and no person at Fynora sees it.
          </li>
          <li>
            We do not save the file, its password, or the transactions in it. They exist only while your spreadsheet
            is being made, and are deleted when it is ready, or within 60 seconds if something goes wrong. Nothing is
            kept to retry with; to try again you send the file again.
          </li>
          <li>
            We keep your network (IP) address for up to 24 hours, only to limit how often the tool can be used. We
            also keep daily counts of how many conversions worked or failed and which bank they were from. These
            counts contain nothing that identifies you or your statement.
          </li>
          <li>
            The spreadsheet is created in your browser and saved on your device. We cannot see or delete it.
          </li>
          <li>
            If you then create a Fynora account and choose to import the statement, it is uploaded again from your
            browser and handled like any other import, as described in Uploaded Statements above.
          </li>
        </ul>
        <p>
          The tool is for statements you are entitled to use. Please do not upload someone else&apos;s statement
          without their permission.
        </p>
      </PublicSection>

      <PublicSection title="Saved Statement Passwords">
        <p>
          Some statements are password-protected PDFs. Fynora does not keep the password you type to open one
          unless you agree to it. When you enter a statement's password, we ask whether you want to save it so
          that statement can be refreshed without asking you again. Saving is optional and applies to that one
          statement only. If you choose not to save it, nothing is stored, and we will ask for the password, and
          offer to save it, the next time that statement is refreshed.
        </p>
        <p>
          A saved password is stored encrypted and is used only to open that statement's file — to import it,
          to refresh it, and, if we could not read it automatically, to let authorized staff review it (see
          below). The password itself is never shown to you or to Fynora staff, and it is never written to logs.
          You can remove any saved password at any time from Settings → Data → Saved statement passwords, which
          deletes it immediately. A saved password is also deleted when you delete its statement or its account,
          or delete your Fynora account. We keep a record of when you gave and withdrew this consent.
        </p>
        <p>
          If a statement whose password you saved is queued for review because we could not read it
          automatically, authorized staff reviewing it can open an unlocked copy so they can read its contents
          and fix the problem. The unlocked copy is created only for that review and is never stored, and every
          such access is logged.
        </p>
      </PublicSection>

      <PublicSection id="ai-features" title="AI Features: Your Choice">
        <p>
          Ask Fyn, AI insights and AI category suggestions are optional. They use Anthropic's Claude, as
          described in the next two sections, and they are off unless you turn them on. You are asked when you
          sign up, and you can change your answer at any time in Settings → Data → AI features.
        </p>
        <p>
          While AI features are off, nothing about you is sent to Anthropic: Ask Fyn does not answer, Insights
          shows only text Fynora writes with its own rules, and a transaction you add by hand without a
          category is categorised only by your own rules and corrections, Fynora's built-in rules and shared
          suggestions.
        </p>
        <p>
          When you turn AI features off, Fynora deletes the AI-written insight texts it stored for you and
          clears, from its log of AI requests, the figures that were sent to the AI and returned to it. It keeps
          the rest of each log entry (the model, its cost and how long it took), which it uses to limit AI
          costs, and your Ask Fyn conversations, which it uses to count the Free plan's daily questions; both
          are deleted when you delete your account. While AI features are off, nothing from your conversations
          is sent to the AI, and you can still scroll back through them. A single conversation cannot yet be
          deleted on its own: your conversations are deleted together, with your account. We keep a record of
          each time you turn AI features on or off.
        </p>
      </PublicSection>

      <PublicSection title="Ask Fyn (AI Assistant)">
        <p>
          When you use Ask Fyn, Fynora's AI assistant, your question and the specific account data needed to
          answer it — such as your balance, budget status, recent transactions, or spending by category — are
          sent to Anthropic's Claude API to generate a response. Fyn only reads data already
          stored in your account through a fixed set of lookups; it cannot take any action on your account,
          and it is instructed to state only figures it actually retrieved, never to guess or estimate one. It
          is not a financial advisor — if asked for investment advice or a recommendation about a future
          financial decision, it declines and suggests speaking with a licensed advisor. Your conversations are
          saved so you can scroll back through them, until you delete your account, governed by the same
          administrative-access rules as your other account data (see Administrative Access below).
          Separately, each call to the AI is logged for cost and safety monitoring: which lookup was used,
          what it was asked for, the figures it returned (such as a balance or a category total), and how the
          call performed. That log does not contain the text of your conversation.
        </p>
        <p>
          If you attach a screenshot to a question, Fynora reads its text on its own servers and sends that
          text, not the image. Account numbers, card numbers, UPI IDs, IFSC codes and phone numbers in it are
          replaced with placeholders before it is sent.
        </p>
        <p>
          Anthropic's service is also used in two other places: AI insights, described below, and if you add a transaction by hand without
          choosing a category, and Fynora cannot work out a category from your own rules and corrections, its
          built-in rules, or a shared suggestion (see "Learning From Corrections Across Users" below), the
          description you typed and the names of your categories may be sent so it can suggest a category. A
          description that is a payment to a person is never sent, and in any description that is sent, UPI
          IDs, account and reference numbers and IFSC codes are removed first. Your amount, account details
          and user ID are never sent. The description is free text and may contain personal information if you
          type it; anything in it Fynora does not recognise as a name or an identifier is sent as written, so
          avoid typing anything sensitive into it. Importing a statement does not do this. The short description returned
          by the service is stored against the payee ID so it can be reused for later transactions with the
          same payee.
        </p>
        <p>
          In all of these uses, Fynora hides the names of people it can recognise before anything is sent:
          the names on your profile and on your accounts, the people you have paid or been paid by (their
          full names, first names and surnames), names in a screenshot's text, and common first names and
          surnames from a list built into Fynora, even for someone you have never paid. Each is replaced with
          a placeholder such as [name-1], and Fynora puts the real name back into the answer before you see
          it, so Anthropic receives the placeholder, never the name. A name Fynora cannot recognise is sent
          as written: an uncommon name of someone you have never paid, or a name that is also an everyday
          word, such as Sunny, typed into a question.
        </p>
      </PublicSection>

      <PublicSection title="AI insights">
        <p>
          On the Insights page, in the app and on the website, Fynora can write a short story of your month,
          and in the app it can explain why a category is running higher than usual. For these, Fynora sends
          Anthropic's Claude only figures from your own history: the month's amounts, counts and percentages, and the names
          of Fynora's built-in categories. It never sends payee or people's names, the names of categories you create
          (each is sent as a placeholder, and Fynora puts the name back before you see it), account details or
          transaction descriptions.
        </p>
        <p>
          The AI describes what happened in your past spending; it does not give financial advice. Fynora fills in
          every figure and name itself, and the AI chooses which ones to use and writes the words around them.
          Fynora checks every reply against fixed rules, including rules against advice, predictions and naming
          financial products, and shows its own rule-based text instead of any reply that fails them. Text written
          this way is marked "Written by AI".
        </p>
        <p>
          Fynora stores each text with the figures it was written from, any thumbs up or down you give it and the
          reason you pick, how many AI texts were written for you this month, and a record of each request to the AI:
          the model, its cost, how long it took and whether the reply passed the checks, never the text. All of this is
          deleted if you delete your account. Anthropic receives this data under Anthropic's standard commercial terms: Anthropic does not
          train its models on it, and deletes it within 30 days, or keeps it for up to 2 years if it is flagged for
          misuse of its service.
        </p>
      </PublicSection>

      <PublicSection title="Cookies">
        <p>
          Fynora uses essential, session-related storage (such as your authentication token) to keep you
          signed in. We do not currently use third-party advertising or tracking cookies. See our{' '}
          <Link to="/cookie-policy" className="text-primary hover:underline">Cookie Policy</Link> for the
          full breakdown of exactly what's stored and why.
        </p>
      </PublicSection>

      <PublicSection title="Analytics">
        <p>
          We may collect aggregated, non-identifying usage data (such as which features are used most) to
          improve the product. This is never combined with your individual financial data for any purpose
          outside operating and improving Fynora itself.
        </p>
        <p>
          We also record the dates on which you use Fynora — the calendar date only, once per day, not
          the time or what you did — so we can understand how often the product is used. These records
          are linked to your account and are deleted if you delete your account.
        </p>
        <p>
          We also record the dates on which you opened the import screen, chose a file to upload and opened
          an import's review, and any answer you tap when Fynora asks what stopped you. We record the date
          only, never the time, the file or anything in it, so we can see where adding a statement gets
          difficult. These records are linked to your account and are deleted if you delete your account.
        </p>
        <p>
          If you tell us why you are deleting your account, we keep that answer with the month only and no
          link to you or your account, so it is not deleted with your account.
        </p>
      </PublicSection>

      <PublicSection title="Data Usage">
        <p>
          Your data is used to: provide the core features you sign up for; generate categorization suggestions
          and financial insights (via Fynora's own rule-based logic, except for the three uses of an AI service
          described under "Ask Fyn (AI Assistant)" and "AI insights" above, which run only if you turn AI
          features on; Fynora's staff can also ask an AI service to explain why an import failed, and that
          request holds only Fynora's own status codes, never your data); detect duplicate or transfer transactions; and secure your account
          (fraud/lockout detection on repeated failed logins). Only if you turn on "Offers and news", it is also
          used to send you push notifications about Fynora offers and new features (see Offers and News below).
        </p>
      </PublicSection>

      <PublicSection id="offers-and-news" title="Offers and News">
        <p>
          Fynora can send occasional push notifications about Fynora itself: offers and discounts on its plans,
          and new features. You get them only if you turn on "Offers and news" in Settings, under Notifications,
          on the website or in an up-to-date version of the app. It is off unless you turn it on, and leaving it off changes nothing else:
          you still get the statement and account updates you have chosen. Fynora does not send offers by
          email or text message.
        </p>
        <p>
          To send them, Fynora uses your account, your choice, and the device token of each phone you use the
          Fynora app on — the same token used for your other notifications — and delivers them through Firebase
          Cloud Messaging (see Infrastructure & Service Providers below). Your phone's own notification
          permission still applies.
        </p>
        <p>
          You can turn "Offers and news" off at any time with the same switch. A notification that has not been
          sent yet is not sent once you do, even if it was already queued. Each time you turn it on or off,
          Fynora records when, from which app, and which wording of the switch the app showed, as evidence of
          your choice. These records are included in your data export, and kept after you delete your account
          as described under Data Deletion below.
        </p>
      </PublicSection>

      <PublicSection title="Learning From Corrections Across Users">
        <p>
          When you set or confirm a category for a payment to a UPI ID that Fynora classifies as a business or a
          financial institution, Fynora records the payee's UPI ID, the category, the direction of the payment
          (money in or out), your account ID, how the payee was classified and the time, in an internal log that
          other users cannot see. This is used to improve category suggestions. The classification is automatic
          and can be wrong. Payments classified as an individual, or not classified, are not recorded. If a
          person is wrongly classified as a business, their UPI ID can be recorded. A UPI ID can identify the
          person or business it belongs to, so the shared record described below is not anonymous, even though
          it holds no account ID.
        </p>
        <p>
          If several separate users (at least three) categorise the same payee the same way, that category
          becomes a shared suggestion for other users' new transactions with the same payee ID. The shared
          record holds the payee ID, the direction, the category, how the choices were split, how many users
          agreed, and internal status and timestamps. It does not include your transaction amounts, transaction
          dates, account ID or statement files. A shared suggestion is used only when your own rules and
          corrections, and Fynora's built-in keyword rules, have no answer for a transaction, so it does not
          override a category you have chosen.
        </p>
        <p>
          Entries for a payee that has no shared suggestion are deleted 180 days after the most recent entry
          when only one user has categorised that payee, or 365 days after the most recent entry when two users
          have. Entries for a payee that has a shared suggestion, or that has three or more users but no shared
          suggestion, currently have no scheduled deletion date. If you delete your account, the entries in the
          log that belong to your account are deleted. Shared suggestions are stored separately from
          individual user log entries, hold no account ID, and are not removed when an account is deleted.
        </p>
      </PublicSection>

      <PublicSection title="Infrastructure & Service Providers">
        <p>Fynora runs on the following infrastructure and service providers, each processing only what its function requires:</p>
        <ul className="list-disc pl-5 space-y-1">
          <li><strong className="text-ink">Firebase Authentication</strong> (Google) — sign-in and identity verification.</li>
          <li><strong className="text-ink">Railway PostgreSQL</strong> — our primary database.</li>
          <li><strong className="text-ink">Cloudflare R2</strong> — storage for the original statement files you upload.</li>
          <li><strong className="text-ink">Railway</strong> — backend application hosting.</li>
          <li><strong className="text-ink">Cloudflare</strong> — frontend/website hosting and edge security.</li>
          <li><strong className="text-ink">Resend</strong> — transactional email delivery (verification, password reset, notifications).</li>
          <li><strong className="text-ink">TwoFactor</strong> — SMS alerts for transactions you add by hand, sent only to a verified phone number. The message carries the amount, whether it was debited or credited, and the description you typed. Sign-in and phone-verification codes are sent by Firebase Authentication, not TwoFactor.</li>
          <li><strong className="text-ink">Anthropic</strong> — the AI service behind Ask Fyn, AI insights and category suggestions for hand-typed transactions, as described above. Used only if you turn AI features on.</li>
          <li><strong className="text-ink">Firebase Cloud Messaging</strong> (Google) — delivery of push notifications to the mobile app, using a device token for your phone.</li>
          <li><strong className="text-ink">Sentry</strong> — crash and error reporting for our apps and servers. Reports carry technical details of the error; the web and mobile apps remove form contents, your account identity and the identifying parts of page addresses before a report is sent. Session replay and performance tracing are switched off.</li>
          <li><strong className="text-ink">Razorpay</strong> — payment processing for subscriptions bought on the web. Your card, UPI or bank details are entered into Razorpay's checkout, not into Fynora. Fynora keeps only what Razorpay reports back to show on your Billing page — for a card, its network and last four digits — never the full number.</li>
          <li><strong className="text-ink">RevenueCat</strong> — manages subscriptions bought through the App Store or Google Play, identified by your Fynora account ID. Apple or Google processes the payment itself.</li>
          <li><strong className="text-ink">Setu</strong> — Account Aggregator connectivity, used only if you choose to link a bank account through India's Account Aggregator framework, and only with the consent you give in that flow.</li>
          <li><strong className="text-ink">Logo.dev</strong> — bank and merchant logos on the website, and merchant logos in the mobile app. To show a logo, your browser or the app asks Logo.dev for it directly by web address: the bank's, or a well-known merchant's from a fixed list Fynora keeps, along with your IP address and browser or device details. Names and other text from your transactions are not part of the request. Earlier versions of the mobile app sent the merchant name shown instead.</li>
          <li><strong className="text-ink">Google Fonts</strong>, <strong className="text-ink">Google Sign-In</strong> and <strong className="text-ink">Sign in with Apple</strong> — the website loads its typefaces from Google's servers, and the Google and Apple sign-in buttons load those companies' scripts, so they receive your IP address and browser details when those load. See the <Link to="/cookie-policy" className="text-primary hover:underline">Cookie Policy</Link> for when each one loads.</li>
        </ul>
        <p>
          Some of these providers operate outside India — see Cross-Border Data Transfer below for how that's
          handled under the DPDP Act.
        </p>
      </PublicSection>

      <PublicSection title="Data Encryption & Security">
        <p>
          Passwords are hashed with bcrypt and never stored or logged in plain text. Password reset tokens and
          session refresh tokens are stored hashed, not in plain text, so a database compromise alone cannot be
          used to reset an account or hijack a session. Sessions are scoped per device, visible and individually
          revocable from Settings, and bounded by several limits. The short-lived access token behind each
          request expires every 15 minutes. On the website, you are signed out after 15 minutes without
          activity, and a session also ends after 24 hours of inactivity or 30 days after you signed in, even
          with continuous use. In the Fynora app, you stay signed in on that phone until you sign out or the app
          goes unused for 90 days, and opening the app needs a 4-digit PIN you set (or Face ID or a
          fingerprint, if you turn that on); the PIN is stored only on your phone and never sent to us, and
          five wrong PINs in a row sign you out on that phone. Each use of a session
          automatically rotates it to a new token, so a token used more than once is treated as a sign of
          compromise and every session on the account is signed out as a precaution. All traffic between your
          device and Fynora's servers is encrypted in transit (HTTPS/TLS), and our website enforces HTTP Strict
          Transport Security (HSTS).
        </p>
        <p>
          Fynora encrypts your statement files itself, with AES-256-GCM, before they are stored: the file you
          upload while it waits for you to review and import it, and the copy kept after you import it. Statement
          passwords you choose to save (see Saved Statement Passwords above) are encrypted the same way. Your
          other financial data (transactions, accounts, budgets, goals) is protected by the security of the
          infrastructure listed above (Railway, Cloudflare R2), not by an additional layer of Fynora-managed
          encryption on top of it.
        </p>
      </PublicSection>

      <PublicSection title="Administrative Access">
        <p>
          Access to customer data by Fynora staff is governed by role-based permissions — a staff member only
          has access to the specific data their role requires, not blanket access to all customer data. Every
          administrative access to your data is logged and auditable, and access is permitted only where
          necessary to operate and support the Service (for example, reviewing a statement that failed
          automated processing — see Uploaded Statements above).
        </p>
      </PublicSection>

      <PublicSection title="Data Breach Notification">
        <p>
          If a personal data breach occurs, we will notify the Data Protection Board of India and affected
          users as required under the DPDP Act, and take steps to contain and remediate the breach.
        </p>
      </PublicSection>

      <PublicSection title="Data Retention">
        <p>
          Your data is retained for as long as your account is active — Fynora does not automatically delete
          statements, transactions, or other data from an active account. If you delete an individual account
          (a bank/card/investment account you've added within Fynora), it and its statements continue to
          appear in Statement History for 7 days before being dropped from that view, purely so recent context
          isn't lost abruptly; there is no separate "undo delete" action.
        </p>
        <p>
          When you delete a statement, its original file stays in our storage for 90 days and is then
          deleted. If an import could not be completed, we keep its file so it can be retried without
          uploading it again, until you delete your account. The one exception is a password-protected PDF
          whose password you chose not to save: if its import fails, the file is not kept. When you delete
          your Fynora account, your statement files are deleted with it.
        </p>
        <p>
          If you upload a statement and never import it, the copy waiting for your review is deleted 48 hours
          after you upload it (or, if we hold it for our own review first, 48 hours after that review ends), and
          the uploaded file itself is deleted from our storage 90 days after we finish reading it.
        </p>
        <p>
          We maintain routine backups of our production database for disaster recovery, with point-in-time
          recovery enabled. Because of this, a small amount of data may persist in encrypted backups for a
          limited period after you delete it from the live system, until those backups themselves cycle out —
          this is standard practice and does not mean the data remains accessible or in active use.
        </p>
        <p>
          Security event logs (audit logs) of account activity are kept indefinitely for security purposes,
          but the detailed content of each event is cleared after 730 days, leaving only a minimal record
          (who, what, when) for as long as the log entry itself exists.
        </p>
      </PublicSection>

      <PublicSection title="Your Rights Under the DPDP Act">
        <p>As a Data Principal under the DPDP Act, you have the right to:</p>
        <ul className="list-disc pl-5 space-y-1">
          <li>
            <strong className="text-ink">Access</strong> a summary of the personal data Fynora holds
            about you and how it is being processed.
          </li>
          <li>
            <strong className="text-ink">Correct or update</strong> inaccurate or incomplete personal
            data — directly in the app (Settings, Accounts, Transactions) for most fields, or by contacting{' '}
            <a href={SUPPORT_MAILTO} className="text-primary hover:underline">{SUPPORT_EMAIL}</a>.
          </li>
          <li>
            <strong className="text-ink">Erase</strong> personal data that is no longer needed for the
            purpose it was collected for — see Data Deletion below.
          </li>
          <li>
            <strong className="text-ink">Withdraw consent</strong> at any time, as easily as you gave it —
            including consent to AI features (Settings → Data → AI features), consent to "Offers and news" push
            notifications (Settings, under Notifications) and consent to keep a saved statement password, each of
            which you can withdraw from Settings without affecting anything else in your account.
          </li>
          <li>
            <strong className="text-ink">Nominate</strong> another individual to exercise these rights on
            your behalf in the event of your death or incapacity, by contacting{' '}
            <a href={SUPPORT_MAILTO} className="text-primary hover:underline">{SUPPORT_EMAIL}</a>.
          </li>
          <li>
            <strong className="text-ink">Grievance redressal</strong> — see below — and, if unresolved,
            the right to file a complaint with the Data Protection Board of India.
          </li>
        </ul>
      </PublicSection>

      <PublicSection title="Grievance Redressal">
        <p>
          If you have a complaint about how Fynora handles your personal data, contact our Grievance Officer,
          {' '}{GRIEVANCE_OFFICER_NAME}, at{' '}
          <a href={GRIEVANCE_MAILTO} className="text-primary hover:underline">{GRIEVANCE_EMAIL}</a>{' '}
          with the details of your concern. We aim to acknowledge and resolve grievances within 30 days. If
          you're not satisfied with the outcome, you may escalate the complaint to the Data Protection Board
          of India.
        </p>
      </PublicSection>

      <PublicSection title="Data Export">
        <p>
          You can export your Fynora data at any time from Settings, on both the web app and the mobile app.
          Your export downloads as a ZIP archive containing your accounts, transactions, budgets, goals, and
          other data as JSON files, the record of each version of the Terms of Service and this policy you
          agreed to, the record of each time you turned a notification switch on or off, plus the original statement files you uploaded in their original format, along with a
          manifest listing exactly what's included (and, for transparency, what's deliberately
          excluded and why). Exporting requires you to re-confirm your password (or Google/Apple sign-in), the
          same as account deletion.
        </p>
      </PublicSection>

      <PublicSection title="Data Deletion">
        <p>
          You can delete individual transactions, accounts, or statements at any time from within Fynora. You
          can also delete your entire Fynora account yourself, from Settings on either the web app or the
          mobile app, whether you sign in with a password, Google or Apple — this requires you to re-confirm
          your password (or sign in again with Google or Apple) plus a one-time code sent to your phone, as a
          safeguard against someone else deleting your account without your knowledge.
        </p>
        <p>
          When you delete a statement, Fynora removes at once, from the statement&apos;s own record, its file
          name, the account-holder name it read from the statement, and the details of the statement&apos;s
          layout. Its file is deleted from our storage as described under Data Retention above. Two other
          records of the same upload still hold the file&apos;s name until you delete your account: its entry
          in your import history, and the technical record described under Uploaded Statements.
        </p>
        <p>
          Account deletion is immediate and permanent once confirmed — there is no waiting period and no
          self-service way to undo it. Your transactions, budgets, and goals are permanently removed, and your
          personal information (name, phone number, email) is erased or replaced with a placeholder at that
          point. The names you gave your accounts, and the account details Fynora read from
          your statements, are removed too. Your phone number is also deleted from Firebase Authentication, the
          service we use to verify it.
        </p>
        <p>
          Deleting your account cancels a Plus subscription you bought on the website. It cannot cancel one
          bought through the App Store or Google Play — only Apple or Google can — so cancel that in your App
          Store or Google Play settings first. The deletion screen reminds you if you have one.
        </p>
        <p>
          What we keep after you delete your account: a record of each version of the Terms of Service and
          this policy you agreed to (the version, the date, and whether it was on the website or an app), as
          evidence of your consent; the record of each time you turned a notification switch such as "Offers
          and news" on or off (the switch, the date, the app and the wording shown), for the same reason;
          security audit logs, as described under Data Retention; and the upload records with their link to
          your account removed, the unlinked deletion reason and the shared category suggestions described in
          their own sections above. The consent and notification records and the audit logs stay linked to
          your account&apos;s internal ID. Your name, email and phone number are removed from that account, but
          the ID itself is not anonymous: it appears in those records, and RevenueCat holds it for a
          subscription bought through an app store. A copy of your data may also briefly
          persist in an encrypted backup until that backup cycles out, as described under Data Retention. If
          you'd rather not go through the in-app flow, you can also request deletion by contacting{' '}
          <a href={SUPPORT_MAILTO} className="text-primary hover:underline">{SUPPORT_EMAIL}</a>.
        </p>
      </PublicSection>

      <PublicSection title="Third-Party Services">
        <p>
          Fynora does not sell your data to third parties. Each infrastructure and service provider listed
          above under Infrastructure &amp; Service Providers receives only the minimum information needed to
          perform its specific function (for example, an email provider receives your email address and
          message content, not your transaction history).
        </p>
      </PublicSection>

      <PublicSection title="Cross-Border Data Transfer">
        <p>
          Fynora's application data is currently hosted and processed on servers located outside India (in
          the United States). Some third-party services we use for authentication, communications and AI
          features are also based outside India. The DPDP Act permits this kind of transfer except to countries the
          Government of India specifically restricts by notification; we do not transfer data to any such
          restricted country. Wherever your data is processed, it remains subject to the protections
          described in this policy.
        </p>
      </PublicSection>

      <PublicSection title="Children's Data">
        <p>
          Fynora is intended for users 18 years of age or older and is not directed at children. When you
          agree to the Terms of Service, you confirm that you are 18 or older. We do not knowingly collect
          personal data from anyone under 18. If you believe a child has provided us
          personal data, contact our Grievance Officer at{' '}
          <a href={GRIEVANCE_MAILTO} className="text-primary hover:underline">{GRIEVANCE_EMAIL}</a>{' '}
          and we will delete it.
        </p>
      </PublicSection>

      <PublicSection title="Policy Updates">
        <p>
          We may update this policy from time to time, and the "Last updated" date at the top of this page
          shows when. When we make a material change to this policy or the Terms of Service, the website and
          the current app ask you to read and agree to the new version before you continue, and we record that you
          did.
        </p>
      </PublicSection>
    </PublicLayout>
  );
}
