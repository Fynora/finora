import { PAYMENT_METHOD_LABELS } from './reportSections/methodBreakdown';

/**
 * The bank statement code glossary: one page per code at /bank-statement-codes/<slug>
 * (StatementCodePage.tsx), and the index at /bank-statement-codes. People search the short code
 * ("nach meaning", "ach d", "int pd"), so each page is titled by it and answers first.
 *
 * Every definition rests on one of three kinds of evidence, and nothing else:
 * - The official page that defines the system (NPCI for NACH, IMPS, UPI and cheque clearing, the
 *   RBI for NEFT, RTGS, ECS and the UTR), listed in the page's `sources`.
 * - What Fynora's own statement reading knows, measured on real statements: the rail words in
 *   backend PaymentMethodClassifier / PaymentRailTokens, the 12-digit UPI and IMPS reference in
 *   OwnAccountEvidence, interest and charge wording in BankActivityCategory, reversals in
 *   MoneyBackWords. Those claims say "on the statements Fynora has read", because that is their scope.
 * - For bank-specific short codes no regulator defines (BIL, INF, MMT, TPT), the common reading
 *   banks' statement legends give, worded as such, with the reader sent to their own statement's
 *   legend as the final word.
 * No charge amount is quoted anywhere: each bank sets its own. Every example line is invented
 * (placeholder references such as 100000000001, IFSC placeholders such as UTIB0XXXXXX); none comes
 * from a real statement.
 */

export interface StatementCodeSource {
  label: string;
  url: string;
}

export interface StatementCodeSection {
  heading: string;
  paragraphs: string[];
}

export interface StatementCode {
  /** The page's address, /bank-statement-codes/<slug>. Never change one once published. */
  slug: string;
  /** The code as printed, for the index list ("ACH D"). */
  code: string;
  /** The <h1> and so the <title>: the code people search, first. */
  title: string;
  /** The FAQPage question this page answers. */
  question: string;
  /** The direct answer, first thing under the heading. Self-contained: it may be quoted alone. */
  answer: string;
  /** The meta description, 70 to 160 characters (pageMeta.test.tsx). */
  description: string;
  sections: StatementCodeSection[];
  /** Invented statement lines, each with what it shows. */
  examples: { line: string; note: string }[];
  /** How Fynora reads a line with this code, or null when it has nothing specific to say. */
  fynora: string | null;
  /** More questions and answers, also in the page's FAQPage markup. */
  faqs: [string, string][];
  sources: StatementCodeSource[];
  /** Slugs of other codes worth reading next. */
  related: string[];
}

const NPCI_NACH: StatementCodeSource = { label: 'NPCI: About NACH', url: 'https://www.npci.org.in/product/nach/about-nach' };
const NPCI_NACH_CIRCULARS: StatementCodeSource = { label: 'NPCI: NACH circulars', url: 'https://www.npci.org.in/circulars/nach' };
const NPCI_IMPS: StatementCodeSource = { label: 'NPCI: About IMPS', url: 'https://www.npci.org.in/product/imps/about-imps' };
const NPCI_UPI: StatementCodeSource = { label: 'NPCI: UPI', url: 'https://www.npci.org.in/product/upi' };
const NPCI_CTS: StatementCodeSource = { label: 'NPCI: About the Cheque Truncation System', url: 'https://www.npci.org.in/product/cts/about-cts' };
const RBI_NEFT: StatementCodeSource = { label: 'RBI: NEFT FAQs', url: 'https://www.rbi.org.in/commonman/English/scripts/FAQs.aspx?Id=274' };
const RBI_RTGS: StatementCodeSource = { label: 'RBI: RTGS FAQs', url: 'https://www.rbi.org.in/commonman/english/scripts/FAQs.aspx?Id=275' };
const RBI_PAYMENT_SYSTEMS: StatementCodeSource = { label: 'RBI: Payment systems overview', url: 'https://rbi.org.in/scripts/FS_Overview.aspx?fn=9' };
const RBI_BOOKLET: StatementCodeSource = { label: 'RBI: Booklet on Payment Systems', url: 'https://www.rbi.org.in/scripts/PublicationsView.aspx?Id=20315' };
const RBI_ECS_CHARGES: StatementCodeSource = {
  label: 'RBI: Service charges levied by banks for ECS',
  url: 'https://www.rbi.org.in/commonman/English/Scripts/ECS.aspx?Id=384',
};

/** "Fynora counts a line that starts with ... as <label> in Reports." with the report's own label. */
function countsAs(words: string, method: keyof typeof PAYMENT_METHOD_LABELS): string {
  const label = PAYMENT_METHOD_LABELS[method];
  if (!label) throw new Error(`statementCodes: no payment method label for ${method}`);
  return `When ${words} starts a line on a bank account statement you import, Fynora counts the payment as "${label}" in the How you paid section of Reports.`;
}

const LEGEND_NOTE =
  'Banks print their own abbreviations, so the legend on your statement, or your bank, is the final word on what a code means for your account.';

const RAILS_IN_ONE_LINE: StatementCodeSection = {
  heading: 'IMPS, NEFT and RTGS in one line each',
  paragraphs: [
    'IMPS (Immediate Payment Service): NPCI\'s instant transfer between banks, 24x7.',
    'NEFT (National Electronic Funds Transfer): the RBI\'s bank-to-bank transfer system, available round the clock on all days.',
    'RTGS (Real Time Gross Settlement): for ₹2,00,000 and above, available 24x7 since December 14, 2020.',
  ],
};

export const STATEMENT_CODES: StatementCode[] = [
  {
    slug: 'nach',
    code: 'NACH',
    title: 'NACH meaning in a bank statement',
    question: 'What does NACH mean in a bank statement?',
    answer:
      'NACH stands for National Automated Clearing House, the system NPCI runs for repeated payments between banks. A NACH debit on your statement is usually money collected under a mandate you signed once, such as a loan EMI, a SIP or an insurance premium; a NACH credit is a bulk payment in, such as salary, a dividend or a subsidy.',
    description:
      'NACH is National Automated Clearing House, NPCI\'s system for repeated payments. A NACH debit is usually an EMI, SIP or premium you authorised once.',
    sections: [
      {
        heading: 'NACH debits and credits',
        paragraphs: [
          'A NACH debit collects money from your account for a company. NPCI lists telephone, electricity and water bills, loan instalments, mutual fund investments and insurance premiums among the payments it collects. The company can collect only because you signed a mandate, a standing permission that NACH keeps in its mandate management system.',
          'A NACH credit pays money into many accounts at once. NPCI lists subsidies, dividends, interest, salary and pensions.',
        ],
      },
      {
        heading: 'When a NACH debit can arrive',
        paragraphs: [
          'The RBI made NACH available on all days of the week from August 1, 2021, so a NACH debit can land on a weekend or a holiday.',
        ],
      },
      {
        heading: 'How banks print it',
        paragraphs: [
          'The code varies by bank: NACH, ACH or ECS at the start of the line all describe the same kind of mandate-based payment. The company\'s name and a reference number usually follow it.',
        ],
      },
    ],
    examples: [
      { line: 'NACH DR EXAMPLE FINANCE LTD 100000000001', note: 'A loan EMI a lender collected under your mandate.' },
      { line: 'ACH D- EXAMPLE MUTUAL FUND-100000000002', note: 'A SIP instalment, printed with ACH instead of NACH.' },
      { line: 'NACH CR EXAMPLE EMPLOYER PVT LTD', note: 'A bulk credit, such as salary, paid in through NACH.' },
    ],
    fynora: countsAs('NACH, ACH or ECS', 'AUTO_DEBIT'),
    faqs: [
      [
        'Is NACH the same as ECS?',
        'NACH replaced ECS. NPCI launched NACH to consolidate the ECS systems that ran across the country, and the RBI says the last ECS centres moved to NACH by January 31, 2020. Some statements still print ECS for these payments.',
      ],
      [
        'Why was money taken from my account through NACH?',
        'A company holds a mandate you signed: a lender for an EMI, a fund house for a SIP, an insurer for a premium, or a utility for a bill. Its name usually follows the code on the same line.',
      ],
      [
        'What happens if a NACH debit fails?',
        'If the balance is too low on the day, the debit is returned unpaid, and your bank may charge a return fee for it. The payment itself is still due to the company.',
      ],
    ],
    sources: [NPCI_NACH, RBI_PAYMENT_SYSTEMS, RBI_BOOKLET],
    related: ['ach-debit', 'nach-return-charges', 'ecs', 'other-codes'],
  },
  {
    slug: 'ach-debit',
    code: 'ACH D',
    title: 'ACH D meaning in a bank statement',
    question: 'What does ACH D mean in a bank statement?',
    answer:
      'ACH D means ACH debit: money a company collected from your account through NACH, NPCI\'s automated clearing house, under a mandate you signed. It is the same thing as a NACH debit, printed a different way; NPCI\'s own circulars call these ACH debits under NACH.',
    description:
      'ACH D (ACH debit) is a NACH debit: money a company collects from your account under a mandate you signed, such as an EMI, a SIP or a premium.',
    sections: [
      {
        heading: 'What follows ACH D on the line',
        paragraphs: [
          'The rest of the line usually names the company that collected the money and gives a reference number. The company tells you what the debit was for: a lender, a fund house, an insurer or a utility.',
        ],
      },
      {
        heading: 'Mutual fund SIPs',
        paragraphs: [
          'A SIP bought through an investment platform does not always name the fund house. On the statements Fynora has read, some SIP debits name a clearing corporation, such as Indian Clearing Corporation, instead. Fynora files those under Investments.',
        ],
      },
    ],
    examples: [
      { line: 'ACH D- EXAMPLE FINANCE LTD-100000000001', note: 'An EMI collected by a lender.' },
      { line: 'ACH D- EXAMPLE CLEARING CORP-100000000002', note: 'A SIP collected through a clearing corporation.' },
    ],
    fynora: countsAs('ACH, NACH or ECS', 'AUTO_DEBIT'),
    faqs: [
      [
        'Is an ACH debit the same as a NACH debit?',
        'Yes. ACH D, ACH DR and NACH DR all describe a debit collected under a NACH mandate. NPCI\'s NACH circulars use the name ACH Debit for it.',
      ],
      [
        'What if an ACH debit bounces?',
        'The bank returns it unpaid and may charge a return fee, and the payment is still due to the company that tried to collect it.',
      ],
    ],
    sources: [NPCI_NACH, NPCI_NACH_CIRCULARS, RBI_PAYMENT_SYSTEMS],
    related: ['nach', 'nach-return-charges', 'other-codes'],
  },
  {
    slug: 'ecs',
    code: 'ECS',
    title: 'ECS meaning in a bank statement',
    question: 'What does ECS mean in a bank statement?',
    answer:
      'ECS stands for Electronic Clearing Service, the older system for repeated payments such as EMIs, bills and salaries. NACH has replaced it: the RBI says the last ECS centres moved to NACH by January 31, 2020. An ECS line on a recent statement is a mandate-based payment handled through NACH.',
    description:
      'ECS is Electronic Clearing Service, the older system for EMIs, bills and salaries. NACH replaced it in 2020, but some statements still print ECS.',
    sections: [
      {
        heading: 'ECS today',
        paragraphs: [
          'ECS ran as several separate systems: ECS and its variants RECS and NECS. NPCI launched NACH to consolidate them into one national system, and the RBI records the move as complete by January 31, 2020.',
          'The name has outlived the system. Some banks still print ECS for a debit collected under a mandate or a bulk credit, and it means the same as NACH.',
        ],
      },
    ],
    examples: [
      { line: 'ECS DR EXAMPLE INSURANCE CO 100000000001', note: 'An insurance premium collected under a mandate.' },
    ],
    fynora: countsAs('ECS, NACH or ACH', 'AUTO_DEBIT'),
    faqs: [
      [
        'What is the difference between ECS and NACH?',
        'ECS was several separate clearing systems; NACH is NPCI\'s single system that took over from them. On your statement they mean the same thing: a payment collected or paid under a standing arrangement.',
      ],
    ],
    sources: [RBI_BOOKLET, NPCI_NACH],
    related: ['nach', 'ecs-return-charges', 'ach-debit'],
  },
  {
    slug: 'nach-return-charges',
    code: 'NACH return charges',
    title: 'NACH return charges explained',
    question: 'What are NACH return charges?',
    answer:
      'A NACH return charge is a fee your bank takes when a NACH debit, such as an EMI or a SIP, could not be paid from your account, most often because the balance was too low on the day. Each bank sets its own amount in its schedule of charges, and the company that tried to collect may charge a late fee of its own.',
    description:
      'A fee your bank takes when a NACH debit, such as an EMI or SIP, bounces, usually for low balance. Each bank sets its own amount.',
    sections: [
      {
        heading: 'Why a NACH debit is returned',
        paragraphs: [
          'The common reason is not enough balance when the debit is presented. A debit can also be returned for a problem with the mandate or the account itself.',
        ],
      },
      {
        heading: 'How it shows on a statement',
        paragraphs: [
          'Banks word the charge differently. Look for a debit that names NACH or ACH together with a word like return or bounce, and in many cases a separate line for the tax on that charge.',
        ],
      },
      {
        heading: 'Avoiding it',
        paragraphs: [
          'Keep enough money in the account before the debit date. The date is set in the mandate, and the company that collects the payment can tell you when it is due.',
        ],
      },
    ],
    examples: [
      { line: 'NACH RETURN CHARGES 100000000001', note: 'Illustrative wording of the fee; your bank\'s will differ.' },
    ],
    fynora: null,
    faqs: [
      [
        'How much is the NACH return charge?',
        'There is no single figure. Each bank publishes its own in its schedule of service charges, and it can differ by account type. Check your bank\'s current schedule rather than a number quoted elsewhere.',
      ],
      [
        'Is the EMI or SIP still due after a return?',
        'Yes. A returned debit means nothing was paid, so the lender, fund house or insurer will try again or ask you to pay another way.',
      ],
    ],
    sources: [NPCI_NACH, RBI_ECS_CHARGES],
    related: ['nach', 'ecs-return-charges', 'ach-debit'],
  },
  {
    slug: 'ecs-return-charges',
    code: 'ECS return charges',
    title: 'ECS return charges explained',
    question: 'What are ECS return charges?',
    answer:
      'An ECS return charge is a fee your bank takes when an ECS debit, such as an EMI or an insurance premium, bounces because the account could not pay it, usually for low balance. ECS debits now run through NACH, so this is the same charge as a NACH return charge, and each bank sets its own amount.',
    description:
      'A fee your bank takes when an ECS debit, such as an EMI, bounces, usually for low balance. ECS now runs through NACH; each bank sets its own amount.',
    sections: [
      {
        heading: 'Why it still says ECS',
        paragraphs: [
          'The RBI moved the last ECS centres to NACH by January 31, 2020, but some banks still print ECS on these debits and on the charge for a returned one.',
        ],
      },
      {
        heading: 'What to do',
        paragraphs: [
          'Check that the debit you missed is paid, because the amount is still due. To avoid the next charge, keep enough balance in the account before the debit date.',
        ],
      },
    ],
    examples: [
      { line: 'ECS RTN CHGS 100000000001', note: 'Illustrative wording of the fee; your bank\'s will differ.' },
    ],
    fynora: null,
    faqs: [
      [
        'How much is the ECS return charge?',
        'Each bank sets its own, in its schedule of service charges; the RBI has published lists showing how the charges differ between banks.',
      ],
    ],
    sources: [RBI_ECS_CHARGES, RBI_BOOKLET],
    related: ['nach-return-charges', 'ecs', 'nach'],
  },
  {
    slug: 'imps',
    code: 'IMPS',
    title: 'IMPS meaning in a bank statement',
    question: 'What does IMPS mean in a bank statement?',
    answer:
      'IMPS stands for Immediate Payment Service, NPCI\'s instant, 24x7 transfer between banks, which you can send from mobile banking, internet banking, a branch, an ATM or by SMS. An IMPS line on your statement is a transfer that reached the other bank in real time.',
    description:
      'IMPS is Immediate Payment Service: NPCI\'s instant, 24x7 transfer between banks, sent from mobile or internet banking, a branch or an ATM.',
    sections: [
      {
        heading: 'The reference number',
        paragraphs: [
          'On the statements Fynora has read, an IMPS line carries a 12-digit reference number. It is what your bank asks for if you need to trace the transfer.',
        ],
      },
      {
        heading: 'MMT',
        paragraphs: [
          'Some banks print MMT, mobile money transfer, for an IMPS transfer sent from a phone.',
        ],
      },
      RAILS_IN_ONE_LINE,
    ],
    examples: [
      { line: 'IMPS/P2A/100000000001/EXAMPLE PAYEE', note: 'An IMPS transfer to an account number (P2A: person to account).' },
      { line: 'MMT/IMPS/100000000002/EXAMPLE PAYEE', note: 'The same kind of transfer, printed as MMT.' },
    ],
    fynora: countsAs('IMPS, NEFT, RTGS, MMT or TPT', 'BANK_TRANSFER'),
    faqs: [
      [
        'Does IMPS work on weekends and holidays?',
        'Yes. NPCI describes IMPS as an instant interbank transfer available 24x7, all year.',
      ],
    ],
    sources: [NPCI_IMPS, RBI_NEFT, RBI_RTGS],
    related: ['neft', 'utr-number', 'upi-transaction-id', 'tpt'],
  },
  {
    slug: 'neft',
    code: 'NEFT',
    title: 'NEFT meaning in a bank statement',
    question: 'What does NEFT mean in a bank statement?',
    answer:
      'NEFT stands for National Electronic Funds Transfer, the RBI\'s nationwide system for sending money from one bank account to another. It runs round the clock on all days, and the RBI sets no limit on the amount.',
    description:
      'NEFT is National Electronic Funds Transfer, the RBI\'s system for bank-to-bank transfers. It runs round the clock every day, with no RBI limit.',
    sections: [
      {
        heading: 'On your statement',
        paragraphs: [
          'A NEFT line usually carries the other bank\'s IFSC, the sender\'s or receiver\'s name and a reference number, the UTR, which you quote to your bank to trace a transfer.',
        ],
      },
      {
        heading: 'Charges',
        paragraphs: [
          'The RBI advised banks not to charge savings account holders for NEFT transfers made online, from January 1, 2020.',
        ],
      },
      RAILS_IN_ONE_LINE,
    ],
    examples: [
      { line: 'NEFT CR-UTIB0XXXXXX-EXAMPLE EMPLOYER-100000000001', note: 'Money received by NEFT, such as salary.' },
      { line: 'NEFT DR-SBIN0XXXXXX-EXAMPLE PAYEE-100000000002', note: 'Money you sent by NEFT.' },
    ],
    fynora: countsAs('NEFT, IMPS, RTGS, MMT or TPT', 'BANK_TRANSFER'),
    faqs: [
      [
        'Is there a limit on NEFT?',
        'The RBI sets none. Your bank may set its own limit, and someone without a bank account can send at most ₹50,000 per transaction in cash through NEFT.',
      ],
      [
        'What is the difference between NEFT and RTGS?',
        'RTGS is for ₹2,00,000 and above; the RBI sets no limit on NEFT. Both run 24x7.',
      ],
    ],
    sources: [RBI_NEFT, RBI_RTGS],
    related: ['imps', 'utr-number', 'tpt'],
  },
  {
    slug: 'utr-number',
    code: 'UTR',
    title: 'UTR number meaning in a bank statement',
    question: 'What is a UTR number in a bank statement?',
    answer:
      'A UTR (Unique Transaction Reference) number identifies one bank transfer. The RBI describes the RTGS UTR as a 22-character code, and its NEFT FAQ asks you to give your bank the UTR when you need a transfer traced.',
    description:
      'A UTR (Unique Transaction Reference) identifies one bank transfer. An RTGS UTR is 22 characters; quote it to your bank to trace a payment.',
    sections: [
      {
        heading: 'Where to find it',
        paragraphs: [
          'In the transfer\'s line on your statement, or in the transaction details in your bank\'s app or net banking. If someone says they have paid you, ask them for the UTR: it lets your bank find the credit.',
        ],
      },
      {
        heading: 'UPI and IMPS references',
        paragraphs: [
          'UPI and IMPS transfers carry their own reference number. On the statements Fynora has read it is 12 digits.',
        ],
      },
    ],
    examples: [
      { line: 'NEFT CR-UTIB0XXXXXX-EXAMPLE PAYER-100000000001', note: 'The reference at the end of the line is what you quote to trace it.' },
    ],
    fynora: null,
    faqs: [
      [
        'How do I trace a transfer with a UTR?',
        'Give your bank the UTR along with the date, the amount, the sender\'s IFSC, and the receiver\'s name and IFSC. The RBI\'s NEFT FAQ lists these as the details that speed up tracking.',
      ],
    ],
    sources: [RBI_RTGS, RBI_NEFT],
    related: ['neft', 'imps', 'upi-transaction-id'],
  },
  {
    slug: 'upi-transaction-id',
    code: 'UPI transaction ID',
    title: 'UPI transaction ID in a bank statement',
    question: 'What is the UPI transaction ID in a bank statement?',
    answer:
      'The UPI transaction ID, also called the UPI reference number, identifies one UPI payment. On the bank statements Fynora has read it is a 12-digit number inside the UPI line, and some banks label it RRN.',
    description:
      'The UPI transaction ID identifies one UPI payment. On the statements Fynora has read it is a 12-digit number in the UPI line, sometimes labelled RRN.',
    sections: [
      {
        heading: 'Reading a UPI line',
        paragraphs: [
          'A UPI line typically starts with UPI, then the direction (DR for money out, CR for money in), then the reference number, then the payee\'s name or UPI ID.',
        ],
      },
      {
        heading: 'When you need it',
        paragraphs: [
          'If a payment failed but the money left your account, or a merchant says a payment never arrived, the reference number is what your bank and the merchant use to find it.',
        ],
      },
    ],
    examples: [
      { line: 'UPI/DR/100000000001/EXAMPLE STORE/example@upi', note: 'A UPI payment out; 100000000001 is the transaction ID.' },
      { line: 'UPI/CR/100000000002/EXAMPLE PAYER/example@upi', note: 'A UPI payment in.' },
    ],
    fynora: countsAs('UPI', 'UPI'),
    faqs: [
      [
        'Is the UPI transaction ID the same number my UPI app shows?',
        'It is the same payment\'s reference, but banks and apps give it different names: UPI reference number, transaction ID or RRN. Match the digits rather than the label.',
      ],
    ],
    sources: [NPCI_UPI],
    related: ['imps', 'utr-number', 'other-codes'],
  },
  {
    slug: 'pos-transaction',
    code: 'POS',
    title: 'POS transaction meaning in a bank statement',
    question: 'What does POS mean in a bank statement?',
    answer:
      'POS stands for point of sale. A POS line on your statement is a payment made with your debit card on a shop\'s card machine, and the shop\'s name usually follows the code.',
    description:
      'POS means point of sale: a debit card payment on a shop\'s card machine. The shop\'s name usually follows POS on the statement line.',
    sections: [
      {
        heading: 'Online card payments',
        paragraphs: [
          'Some banks mark a card payment made online differently, for example with ECOM, instead of POS.',
        ],
      },
    ],
    examples: [
      { line: 'POS 4XXXXXXXXXXX1234 EXAMPLE SUPERMARKET', note: 'A debit card payment in a shop; the card number is masked.' },
    ],
    fynora: countsAs('POS, ECOM or PCD', 'CARD'),
    faqs: [
      [
        'Is a POS transaction a credit card payment?',
        'On a savings account statement it is a debit card payment, taken straight from the account. Spending on a credit card appears on the credit card\'s own statement instead.',
      ],
    ],
    sources: [],
    related: ['atm-wdl', 'upi-transaction-id', 'other-codes'],
  },
  {
    slug: 'atm-wdl',
    code: 'ATM WDL',
    title: 'ATM WDL meaning in a bank statement',
    question: 'What does ATM WDL mean in a bank statement?',
    answer:
      'ATM WDL means ATM withdrawal: cash you took out of an ATM with your debit card. The line usually also shows where the ATM is, or its ID.',
    description:
      'ATM WDL means ATM withdrawal: cash taken out of an ATM with your debit card. The line usually also shows where the ATM is, or its ID.',
    sections: [
      {
        heading: 'Other spellings',
        paragraphs: [
          'Banks write cash withdrawals in different ways. Fynora reads ATM, ATW, NWD and CWDR near the start of a line as a cash withdrawal.',
        ],
      },
    ],
    examples: [
      { line: 'ATM WDL EXAMPLE NAGAR BRANCH 100001', note: 'Cash withdrawn at an ATM; the place and a terminal number follow.' },
    ],
    fynora: countsAs('ATM, ATW, NWD or CWDR', 'CASH'),
    faqs: [],
    sources: [],
    related: ['pos-transaction', 'other-codes'],
  },
  {
    slug: 'int-pd',
    code: 'INT PD',
    title: 'INT PD meaning in a bank statement',
    question: 'What does INT PD mean in a bank statement?',
    answer:
      'INT PD means interest paid: interest your bank credited to your savings account or deposit. It is money in, and the line often carries the period the interest was earned for.',
    description:
      'INT PD means interest paid: interest your bank credited to your savings account or deposit, often with the period it was earned for.',
    sections: [
      {
        heading: 'Other spellings',
        paragraphs: [
          'On the statements Fynora has read, the same credit appears as INT.PD, INT CR, SB INT, INTEREST CREDIT or CREDIT INTEREST, among others.',
        ],
      },
      {
        heading: 'Interest you pay is different',
        paragraphs: [
          'A line about interest on a loan or on a card\'s EMI plan is a charge, not interest paid to you. Check whether the amount is in the credit column or the debit column.',
        ],
      },
    ],
    examples: [
      { line: 'INT.PD:01-04-2026 TO 30-06-2026', note: 'Interest credited for a quarter.' },
    ],
    fynora:
      'Fynora reads these credits as interest the bank paid you and files them under "Interest & Cashback", so they are not mistaken for a transfer or a refund.',
    faqs: [],
    sources: [],
    related: ['other-codes', 'neft'],
  },
  {
    slug: 'by-clg',
    code: 'BY CLG',
    title: 'BY CLG meaning in a bank statement',
    question: 'What does BY CLG mean in a bank statement?',
    answer:
      'BY CLG means a credit by clearing: a cheque you deposited has been cleared and the money credited to your account. CLG is short for clearing, and on statements that use them, BY starts a credit and TO starts a debit.',
    description:
      'BY CLG means credit by clearing: a cheque you deposited has cleared and the money is in your account. CLG is short for clearing.',
    sections: [
      {
        heading: 'How a cheque clears',
        paragraphs: [
          'Cheques clear through NPCI\'s Cheque Truncation System: the bank you deposit the cheque with sends an image of it electronically to the cheque writer\'s bank, instead of the paper.',
        ],
      },
      {
        heading: 'TO CLG',
        paragraphs: [
          'On statements that use BY and TO, a cheque you wrote that has been paid out of your account shows as TO CLG.',
        ],
      },
    ],
    examples: [
      { line: 'BY CLG 100001 EXAMPLE PAYER', note: 'A cheque you deposited, now credited.' },
      { line: 'TO CLG 100002 EXAMPLE PAYEE', note: 'A cheque you wrote, now paid.' },
    ],
    fynora: countsAs('CLG, BY CLG or CHQ', 'CHEQUE'),
    faqs: [],
    sources: [NPCI_CTS],
    related: ['tpt', 'neft', 'other-codes'],
  },
  {
    slug: 'tpt',
    code: 'TPT',
    title: 'TPT meaning in a bank statement',
    question: 'What does TPT mean in a bank statement?',
    answer:
      'TPT usually means third-party transfer: money you sent from your account to someone else\'s, typically through net banking or your bank\'s app. The other person\'s name or account usually follows the code.',
    description:
      'TPT usually means third-party transfer: money sent from your account to someone else\'s, typically through net banking or your bank\'s app.',
    sections: [
      {
        heading: 'A bank\'s own code',
        paragraphs: [
          'TPT is not a payment system like NEFT or IMPS; it is a label some banks print for a transfer to another person. LEGEND_NOTE',
        ],
      },
    ],
    examples: [
      { line: 'TPT-100000000001-EXAMPLE PAYEE', note: 'A transfer to another person\'s account.' },
    ],
    fynora: countsAs('TPT, IMPS, NEFT or MMT', 'BANK_TRANSFER'),
    faqs: [],
    sources: [],
    related: ['imps', 'neft', 'by-clg'],
  },
  {
    slug: 'other-codes',
    code: 'BIL, ONL, MMT, INF, REV, SI',
    title: 'Other bank statement codes: BIL, MMT, INF, REV, SI',
    question: 'What do BIL, ONL, MMT, INF, REV and SI mean in a bank statement?',
    answer:
      'They are short codes banks print at the start of a statement line. The common readings: BIL is a bill payment or transfer made through internet banking, ONL an online payment, MMT a mobile money (IMPS) transfer, INF a transfer between your own linked accounts, REV a reversal, and SI a standing instruction.',
    description:
      'Common readings of BIL, ONL, MMT, INF, REV, SI, mandate and SMS charges on Indian bank statements, and where to check your own bank\'s legend.',
    sections: [
      {
        heading: 'BIL and BIL ONL',
        paragraphs: ['A bill payment, or a transfer to someone else, made through internet banking.'],
      },
      {
        heading: 'ONL',
        paragraphs: ['An online payment.'],
      },
      {
        heading: 'MMT',
        paragraphs: ['Mobile money transfer: an IMPS transfer sent from a phone. Fynora reads MMT as a bank transfer.'],
      },
      {
        heading: 'INF',
        paragraphs: ['Internet fund transfer between accounts linked under your own login, such as your savings account and a deposit.'],
      },
      {
        heading: 'REV and reversal',
        paragraphs: [
          'Money coming back because a payment failed or was undone on the bank\'s side, such as a UPI or card payment that did not go through. It is different from a refund, which is a merchant returning money for an order. When a line says reversal, Fynora treats it as a reversal, not a refund.',
        ],
      },
      {
        heading: 'SI',
        paragraphs: ['Standing instruction: a payment you told your bank or card to make on a schedule, such as a monthly transfer or a bill on your debit card.'],
      },
      {
        heading: 'Mandate, e-mandate and AutoPay',
        paragraphs: [
          'A permission you gave a company to collect from your account, through NACH, UPI AutoPay or an e-mandate. A debit carrying MANDATE, EMANDATE or AUTOPAY was collected under one, and Fynora reads it as an auto-debit.',
        ],
      },
      {
        heading: 'SMS charges',
        paragraphs: ['A fee your bank charges for SMS alerts. Fynora files it under "Fees/Interest" as a bank charge, not spending at a shop.'],
      },
      {
        heading: 'Check your own bank\'s legend',
        paragraphs: ['LEGEND_NOTE'],
      },
    ],
    examples: [
      { line: 'BIL/ONL/100000000001/EXAMPLE ELECTRICITY', note: 'A bill paid through internet banking.' },
      { line: 'INF/100000000002/OWN DEPOSIT ACCOUNT', note: 'A transfer between your own linked accounts.' },
      { line: 'REV-UPI/100000000003/EXAMPLE STORE', note: 'A UPI payment reversed back to you.' },
    ],
    fynora: null,
    faqs: [],
    sources: [NPCI_NACH],
    related: ['nach', 'imps', 'upi-transaction-id', 'pos-transaction'],
  },
];

// The legend note is written once above and placed where a page asks for it.
for (const code of STATEMENT_CODES) {
  for (const section of code.sections) {
    section.paragraphs = section.paragraphs.map((p) => p.replace('LEGEND_NOTE', LEGEND_NOTE));
  }
}

export const STATEMENT_CODE_ROUTE = '/bank-statement-codes/:slug';
/** The index page's heading, which is also the middle crumb of every code page's breadcrumb trail. */
export const STATEMENT_CODES_TITLE = 'Bank statement codes explained';
export const STATEMENT_CODES_INDEX = '/bank-statement-codes';

export function statementCodePath(code: Pick<StatementCode, 'slug'>): string {
  return `${STATEMENT_CODES_INDEX}/${code.slug}`;
}

export function statementCodeBySlug(slug: string | undefined): StatementCode | undefined {
  return slug === undefined ? undefined : STATEMENT_CODES.find((c) => c.slug === slug);
}

export function statementCodeForPath(pathname: string): StatementCode | undefined {
  const match = /^\/bank-statement-codes\/([^/]+)\/?$/.exec(pathname);
  return match ? statementCodeBySlug(match[1]) : undefined;
}

/** The page's question and answer, then its other questions: what its FAQPage markup carries. */
export function statementCodeFaqs(code: StatementCode): [string, string][] {
  return [[code.question, code.answer], ...code.faqs];
}
