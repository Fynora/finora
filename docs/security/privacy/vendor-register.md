# Vendor and transfer register

Privacy workstream 9. One row per outside service that receives personal data.

- The list matches the Privacy Policy's "Infrastructure & Service Providers" section; adding or removing a service changes both in the same PR.
- The "What it receives" column was read from code on 2026-11-02.
- Region, DPA status and the date checked come only from each provider's console or contract. They are marked **Sid** until filled in, never guessed.
- The policy says app data is hosted and processed in the United States. Each row's region should confirm or correct that.

| Service | What it receives | Why | Region | DPA / terms accepted | Checked on | Notes |
|---|---|---|---|---|---|---|
| Railway (Postgres, Redis, backend hosting) | Everything in the database; backend logs | Hosting | **Sid** | **Sid** | | Security incident 2026-10-21; workstream 1 |
| Cloudflare R2 | Statement files, encrypted by Fynora (AES-256-GCM) | File storage | **Sid** (location hint) | **Sid** | | |
| Cloudflare (Pages, edge) | IP address, request metadata for the website | Website hosting, edge security | Global edge | **Sid** | | |
| Firebase Authentication (Google) | Phone number, verification events | Phone verification | **Sid** | **Sid** | | Phone user deleted with the account (#2429) |
| Firebase Cloud Messaging (Google) | Device token, push content | Push notifications | **Sid** | **Sid** | | |
| Anthropic | Only when AI features are on: questions, account figures, hand-typed descriptions with names and identifiers hidden | Ask Fyn, AI insights, AI categories | **Sid** | Commercial terms read 2026-10-22: no training on inputs; 30-day deletion, up to 2 years if flagged. Whether the DPA was accepted in the console: **Sid** | 2026-10-22 (terms only) | What leaves is tested in `AiOutboundPayloadIT` |
| Resend | Email address, email content | Transactional email | **Sid** | **Sid** | | |
| TwoFactor | Verified phone number; amount, debit/credit and typed description of a hand-added transaction | SMS transaction alerts | India (Indian SMS provider); confirm: **Sid** | **Sid** | | |
| Sentry | Error details. Form contents, account identity and identifying URL parts are removed in the web and mobile apps. | Error reporting | **Sid** | **Sid** | | Retention: **Sid** |
| Razorpay | Payment details entered in Razorpay's checkout; Fynora keeps the card network and last four digits | Web subscriptions | India; confirm: **Sid** | **Sid** | | Pre-debit renewal notices: not established |
| RevenueCat | Fynora account ID, store purchase events | App Store / Google Play subscriptions | **Sid** | **Sid** | | |
| Apple, Google (store billing, sign-in) | Purchase and sign-in data under their own terms | Store billing; Sign in with Apple / Google | — | Their own terms | | |
| Setu | Only if a bank is linked through Account Aggregator | AA connectivity | **Sid** | **Sid** | | Needs an FIU licence Fynora does not hold; whether users can reach it in production is not established. If they can't, the policy should say "planned" or drop it. |
| Logo.dev | The user's IP address and browser/device details, plus a bank's or a listed merchant's web address | Logos | **Sid** | **Sid** | | |
| Google Fonts; Google and Apple sign-in scripts | IP address, browser details when loaded | Website fonts, sign-in buttons | **Sid** | Their own terms | | |
| ClamAV (self-hosted on Railway) | Uploaded files, for a virus scan | Upload safety | As Railway | — | | Runs in Fynora's own Railway project, not a third party |

## Transfer position (counsel)

- The DPDP Act allows transfers except to countries the Government restricts by notification.
- **Counsel to confirm:**
  - that no listed region is restricted
  - whether any sector rule (RBI, for payment data) adds a localisation duty for Razorpay or card data
