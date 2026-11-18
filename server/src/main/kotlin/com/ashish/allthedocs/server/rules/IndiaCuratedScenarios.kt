package com.ashish.allthedocs.server.rules

import com.ashish.allthedocs.api.DocumentRequirement
import com.ashish.allthedocs.api.FollowUpQuestion
import com.ashish.allthedocs.api.ObtainGuide
import com.ashish.allthedocs.api.SourceLink
import com.ashish.allthedocs.model.DocumentType

object IndiaCuratedScenarios {

    private object Links {
        val passportSeva = SourceLink("Passport Seva", "https://www.passportindia.gov.in")
        val myAadhaar = SourceLink("myAadhaar (UIDAI)", "https://myaadhaar.uidai.gov.in")
        val uidai = SourceLink("UIDAI", "https://uidai.gov.in")
        val incomeTax = SourceLink("Income Tax e-Filing portal", "https://www.incometax.gov.in")
        val protean = SourceLink("Protean (NSDL) PAN services", "https://www.protean-tinpan.com")
        val utiitsl = SourceLink("UTIITSL PAN services", "https://www.utiitsl.com")
        val rbiKyc = SourceLink(
            "RBI Master Direction – Know Your Customer (KYC) Direction, 2016",
            "https://www.rbi.org.in/Scripts/BS_ViewMasDirections.aspx?id=11566",
        )
        val epfo = SourceLink("EPFO", "https://www.epfindia.gov.in")
        val epfoMember = SourceLink("EPFO Member Portal (UAN)", "https://unifiedportal-mem.epfindia.gov.in")
        val sarathi = SourceLink("Parivahan Sarathi (driving licence)", "https://sarathi.parivahan.gov.in")
        val voters = SourceLink("Voters' Service Portal (ECI)", "https://voters.eci.gov.in")
        val crs = SourceLink("Civil Registration System", "https://crsorgi.gov.in")
        val digiLocker = SourceLink("DigiLocker", "https://www.digilocker.gov.in")
        val gst = SourceLink("GST portal", "https://www.gst.gov.in")
        val udyam = SourceLink("Udyam Registration", "https://udyamregistration.gov.in")
    }

    private object Guides {
        val aadhaar = ObtainGuide(
            steps = listOf(
                "If you already have Aadhaar, download e-Aadhaar on myAadhaar using the OTP sent to your registered mobile.",
                "For a new enrolment or to update details, book an appointment at an Aadhaar Seva Kendra or enrolment centre.",
            ),
            links = listOf(Links.myAadhaar, Links.uidai, Links.digiLocker),
            typicalFee = "Enrolment is free; updates carry a small fee (check UIDAI)",
            typicalTime = "e-Aadhaar download is instant; a new enrolment can take a few weeks",
        )
        val pan = ObtainGuide(
            steps = listOf(
                "No PAN yet: apply for an instant e-PAN on the Income Tax e-Filing portal using Aadhaar OTP.",
                "Need a physical card or a correction: apply online through Protean (NSDL) or UTIITSL.",
                "Already have a PAN: download the e-PAN from Protean/UTIITSL or DigiLocker.",
            ),
            links = listOf(Links.incomeTax, Links.protean, Links.utiitsl, Links.digiLocker),
            typicalFee = "Instant e-PAN via Aadhaar is free; a physical card is approx. ₹100–110 within India",
            typicalTime = "Instant e-PAN within minutes; a physical card in approx. 2–3 weeks",
        )
        val voterId = ObtainGuide(
            steps = listOf("Apply or download e-EPIC on the Voters' Service Portal."),
            links = listOf(Links.voters),
            typicalFee = "Free",
        )
        val drivingLicence = ObtainGuide(
            steps = listOf("Apply, renew or download licence details on Parivahan Sarathi; DigiLocker also holds a copy."),
            links = listOf(Links.sarathi, Links.digiLocker),
        )
        val birthCertificate = ObtainGuide(
            steps = listOf(
                "Request it from the municipal corporation / registrar where the birth was registered.",
                "Some states issue it online through the Civil Registration System.",
            ),
            links = listOf(Links.crs),
            typicalFee = "Nominal, varies by state",
        )
        val utilityBill = ObtainGuide(
            steps = listOf("Download a recent electricity, water, gas, landline or postpaid mobile bill from the provider's website or app."),
        )
        val bankStatement = ObtainGuide(
            steps = listOf(
                "Download a PDF statement for the required period from net banking or the bank's app.",
                "Or request a stamped statement at your branch.",
            ),
            typicalFee = "Usually free online",
            typicalTime = "Instant online",
        )
        val salarySlip = ObtainGuide(
            steps = listOf("Download payslips from your employer's payroll/HR portal or ask HR."),
            typicalTime = "Usually instant",
        )
        val form16 = ObtainGuide(
            steps = listOf(
                "Ask your employer's payroll/HR team; employers issue Form 16 (Part A + Part B) after the financial year ends.",
                "It is normally issued by mid-June for the previous financial year.",
            ),
            links = listOf(Links.incomeTax),
        )
        val taxStatement = ObtainGuide(
            steps = listOf(
                "Log in to the Income Tax e-Filing portal.",
                "Form 26AS: e-File → Income Tax Returns → View Form 26AS (opens TRACES).",
                "AIS/TIS: Services → Annual Information Statement (AIS).",
            ),
            links = listOf(Links.incomeTax),
            typicalFee = "Free",
            typicalTime = "Instant",
        )
        val filedReturn = ObtainGuide(
            steps = listOf("Log in to the Income Tax e-Filing portal → e-File → Income Tax Returns → View Filed Returns → download ITR-V / acknowledgement."),
            links = listOf(Links.incomeTax),
            typicalFee = "Free",
            typicalTime = "Instant",
        )
        val photograph = ObtainGuide(
            steps = listOf("Get recent passport-size colour photographs on a white background from a photo studio."),
            typicalFee = "Approx. ₹50–150 for a set",
            typicalTime = "Same day",
        )
        val passportApplication = ObtainGuide(
            steps = listOf(
                "Register on Passport Seva (or the mPassport Seva app) and fill the application form.",
                "Pay the fee online and book an appointment at a Passport Seva Kendra (PSK) or Post Office PSK.",
                "Visit with original documents and self-attested copies; photo and biometrics are captured there.",
                "Complete police verification when contacted.",
            ),
            links = listOf(Links.passportSeva),
            typicalFee = "Approx. ₹1,500 for a 36-page booklet (normal); Tatkaal adds approx. ₹2,000; lower fee for minors below 15",
            typicalTime = "Normal: often a few weeks depending on police verification; Tatkaal: faster",
        )
        val employerLetter = ObtainGuide(
            steps = listOf("Request it from your (previous) employer's HR team."),
        )
        val financials = ObtainGuide(
            steps = listOf("Ask your chartered accountant for CA-certified / audited balance sheet and profit & loss statements."),
        )
        val businessRegistration = ObtainGuide(
            steps = listOf("Download your GST registration certificate from the GST portal, or the Udyam certificate from the Udyam portal."),
            links = listOf(Links.gst, Links.udyam),
        )
        val capitalGains = ObtainGuide(
            steps = listOf(
                "Download the tax P&L / capital gains report from your broker.",
                "Download mutual fund capital gains statements from CAMS or KFintech (or the fund house).",
                "For property sales, keep the sale and purchase deeds and cost-of-improvement proofs.",
            ),
        )
        val uan = ObtainGuide(
            steps = listOf("Find your UAN on payslips or on the EPFO Member Portal; complete KYC (Aadhaar, PAN, bank) there."),
            links = listOf(Links.epfoMember, Links.epfo),
        )
    }

    private val kycIdentityTypes = listOf(
        DocumentType.AADHAAR,
        DocumentType.PASSPORT,
        DocumentType.VOTER_ID,
        DocumentType.DRIVING_LICENCE,
    )

    private val kycAddressTypes = kycIdentityTypes + DocumentType.UTILITY_BILL

    private fun panRequirement(specs: List<String>, citations: List<SourceLink>) = DocumentRequirement(
        id = "pan",
        name = "PAN card",
        description = "Permanent Account Number card issued by the Income Tax Department.",
        acceptedTypes = listOf(DocumentType.PAN_CARD),
        specs = specs,
        obtainGuide = Guides.pan,
        citations = citations,
    )

    private fun identityProof(citations: List<SourceLink>) = DocumentRequirement(
        id = "identity_proof",
        name = "Identity proof",
        description = "Any one officially valid document: Aadhaar, passport, voter ID or driving licence.",
        acceptedTypes = kycIdentityTypes,
        specs = listOf("Self-attested copy; originals may be checked"),
        obtainGuide = Guides.aadhaar,
        citations = citations,
    )

    private fun addressProof(citations: List<SourceLink>, extraTypes: List<DocumentType> = emptyList()) = DocumentRequirement(
        id = "address_proof",
        name = "Address proof",
        description = "A document showing your current address.",
        acceptedTypes = (kycAddressTypes + extraTypes).distinct(),
        specs = listOf("Utility bills should be recent (typically within the last 2–3 months)"),
        obtainGuide = Guides.utilityBill,
        citations = citations,
    )

    private fun photographs(specs: List<String>) = DocumentRequirement(
        id = "photographs",
        name = "Passport-size photographs",
        acceptedTypes = listOf(DocumentType.PHOTOGRAPH),
        specs = specs,
        obtainGuide = Guides.photograph,
    )

    private object Q {
        const val PASSPORT_TYPE = "application_type"
        const val APPLICANT = "applicant"
        const val EMPLOYMENT = "employment_type"
        const val PROPERTY = "property_stage"
        const val HAS_PAN = "has_pan"
        const val REGIME = "tax_regime"
        const val CAPITAL_GAINS = "capital_gains"
        const val HOUSE_PROPERTY = "house_property"
        const val HRA = "hra"
        const val EXPERIENCE = "experience"
        const val MID_YEAR = "mid_year_switch"
    }

    private const val SALARIED = "Salaried"
    private const val SELF_EMPLOYED = "Self-employed"

    private val employmentQuestion = FollowUpQuestion(
        id = Q.EMPLOYMENT,
        text = "What is your employment type?",
        options = listOf(SALARIED, SELF_EMPLOYED),
    )

    private val salaried = answer(Q.EMPLOYMENT, SALARIED, includeIfUnanswered = true)
    private val selfEmployed = answer(Q.EMPLOYMENT, SELF_EMPLOYED)

    val passport = CuratedScenario(
        id = "in.passport_application",
        title = "Indian passport application",
        summary = "Applying for a fresh Indian passport or re-issuing an existing one (adult or minor).",
        countryCode = "IN",
        keywords = listOf("passport", "passport renewal", "passport reissue", "re-issue passport", "tatkaal"),
        questions = listOf(
            FollowUpQuestion(Q.PASSPORT_TYPE, "Is this a fresh passport or a re-issue?", listOf("Fresh", "Re-issue")),
            FollowUpQuestion(Q.APPLICANT, "Who is the applicant?", listOf("Adult (18+)", "Minor (below 18)")),
        ),
        requirements = listOf(
            CuratedRequirement(
                DocumentRequirement(
                    id = "application",
                    name = "Passport Seva application & appointment",
                    description = "Online application with its Application Reference Number (ARN) and appointment details.",
                    acceptedTypes = listOf(DocumentType.APPLICATION_FORM),
                    specs = listOf("Carrying a printout of the appointment receipt is optional"),
                    obtainGuide = Guides.passportApplication,
                    citations = listOf(Links.passportSeva),
                ),
            ),
            CuratedRequirement(
                DocumentRequirement(
                    id = "address_proof",
                    name = "Proof of present address",
                    description = "Aadhaar, a recent utility bill, bank passbook/statement of a running account, voter ID, rent agreement or similar.",
                    acceptedTypes = listOf(
                        DocumentType.AADHAAR,
                        DocumentType.UTILITY_BILL,
                        DocumentType.BANK_STATEMENT,
                        DocumentType.VOTER_ID,
                        DocumentType.RENT_AGREEMENT,
                    ),
                    specs = listOf("Original + self-attested copy", "For minors, a parent's address proof is used"),
                    obtainGuide = Guides.aadhaar,
                    citations = listOf(Links.passportSeva),
                ),
            ),
            CuratedRequirement(
                DocumentRequirement(
                    id = "dob_proof",
                    name = "Proof of date of birth",
                    description = "Birth certificate, PAN card, Aadhaar, driving licence, voter ID or school leaving / matriculation certificate.",
                    acceptedTypes = listOf(
                        DocumentType.BIRTH_CERTIFICATE,
                        DocumentType.PAN_CARD,
                        DocumentType.AADHAAR,
                        DocumentType.DRIVING_LICENCE,
                        DocumentType.VOTER_ID,
                        DocumentType.EDUCATION_CERTIFICATE,
                    ),
                    specs = listOf("Original + self-attested copy"),
                    obtainGuide = Guides.birthCertificate,
                    citations = listOf(Links.passportSeva),
                ),
                answer(Q.PASSPORT_TYPE, "Fresh", includeIfUnanswered = true),
            ),
            CuratedRequirement(
                DocumentRequirement(
                    id = "non_ecr_proof",
                    name = "Educational certificate for Non-ECR status",
                    description = "Class 10 or higher certificate; without it, an ECR (Emigration Check Required) notation may be added.",
                    acceptedTypes = listOf(DocumentType.EDUCATION_CERTIFICATE),
                    mandatory = false,
                    citations = listOf(Links.passportSeva),
                ),
                answer(Q.APPLICANT, "Adult (18+)", includeIfUnanswered = true),
            ),
            CuratedRequirement(
                DocumentRequirement(
                    id = "old_passport",
                    name = "Old passport",
                    description = "Your current or expired passport.",
                    acceptedTypes = listOf(DocumentType.PASSPORT),
                    specs = listOf(
                        "Original passport",
                        "Self-attested copies of the first two and last two pages, the ECR/Non-ECR page and any observation page",
                    ),
                    obtainGuide = Guides.passportApplication,
                    citations = listOf(Links.passportSeva),
                ),
                answer(Q.PASSPORT_TYPE, "Re-issue"),
            ),
            CuratedRequirement(
                DocumentRequirement(
                    id = "parents_passports",
                    name = "Parents' passports (if held)",
                    acceptedTypes = listOf(DocumentType.PASSPORT),
                    mandatory = false,
                    specs = listOf("Self-attested copies of the first and last pages"),
                    citations = listOf(Links.passportSeva),
                ),
                answer(Q.APPLICANT, "Minor (below 18)"),
            ),
            CuratedRequirement(
                DocumentRequirement(
                    id = "parental_consent",
                    name = "Parental consent declaration (Annexure D)",
                    description = "Declaration by parents/guardian for a minor's passport application.",
                    acceptedTypes = listOf(DocumentType.APPLICATION_FORM),
                    specs = listOf("Signed by both parents; a different annexure applies if only one parent applies"),
                    citations = listOf(Links.passportSeva),
                ),
                answer(Q.APPLICANT, "Minor (below 18)"),
            ),
        ),
        assumptions = listOf(
            "Photographs and biometrics are captured at the Passport Seva Kendra, so no physical photos are needed.",
            "The full list depends on your case; check the Document Advisor on Passport Seva before the appointment.",
        ),
    )

    val homeLoan = CuratedScenario(
        id = "in.home_loan",
        title = "Home loan application (India)",
        summary = "Applying for a home loan from a bank or housing finance company in India.",
        countryCode = "IN",
        keywords = listOf("home loan", "housing loan", "mortgage", "house loan", "property loan"),
        questions = listOf(
            employmentQuestion,
            FollowUpQuestion(
                Q.PROPERTY,
                "What kind of property are you financing?",
                listOf("Buying from a builder", "Resale property", "Construction on own plot"),
            ),
        ),
        requirements = listOf(
            CuratedRequirement(panRequirement(listOf("Mandatory for loans", "PAN should be linked with Aadhaar"), listOf(Links.rbiKyc))),
            CuratedRequirement(identityProof(listOf(Links.rbiKyc))),
            CuratedRequirement(addressProof(listOf(Links.rbiKyc))),
            CuratedRequirement(photographs(listOf("Recent, usually 2–3 copies"))),
            CuratedRequirement(
                DocumentRequirement(
                    id = "salary_slips",
                    name = "Salary slips",
                    acceptedTypes = listOf(DocumentType.SALARY_SLIP),
                    specs = listOf("Last 3 months"),
                    obtainGuide = Guides.salarySlip,
                ),
                salaried,
            ),
            CuratedRequirement(
                DocumentRequirement(
                    id = "form_16",
                    name = "Form 16",
                    acceptedTypes = listOf(DocumentType.FORM_16),
                    specs = listOf("Last 2 financial years"),
                    obtainGuide = Guides.form16,
                ),
                salaried,
            ),
            CuratedRequirement(
                DocumentRequirement(
                    id = "employment_proof",
                    name = "Employment certificate / appointment letter",
                    acceptedTypes = listOf(DocumentType.EMPLOYMENT_PROOF, DocumentType.OFFER_LETTER),
                    mandatory = false,
                    specs = listOf("Some lenders ask for it, especially for a recent job change"),
                    obtainGuide = Guides.employerLetter,
                ),
                salaried,
            ),
            CuratedRequirement(
                DocumentRequirement(
                    id = "bank_statements",
                    name = "Bank statements",
                    acceptedTypes = listOf(DocumentType.BANK_STATEMENT),
                    specs = listOf(
                        "Salaried: last 6 months of the salary account",
                        "Self-employed: last 12 months of business and personal accounts",
                    ),
                    obtainGuide = Guides.bankStatement,
                ),
            ),
            CuratedRequirement(
                DocumentRequirement(
                    id = "itr",
                    name = "Income tax returns",
                    acceptedTypes = listOf(DocumentType.TAX_RETURN),
                    specs = listOf("Last 3 years, with computation of income"),
                    obtainGuide = Guides.filedReturn,
                ),
                selfEmployed,
            ),
            CuratedRequirement(
                DocumentRequirement(
                    id = "financials",
                    name = "Audited financial statements",
                    acceptedTypes = listOf(DocumentType.FINANCIAL_STATEMENT),
                    specs = listOf("CA-certified / audited balance sheet and P&L for the last 2–3 years"),
                    obtainGuide = Guides.financials,
                ),
                selfEmployed,
            ),
            CuratedRequirement(
                DocumentRequirement(
                    id = "business_proof",
                    name = "Proof of business",
                    acceptedTypes = listOf(DocumentType.BUSINESS_REGISTRATION),
                    specs = listOf("GST registration, Udyam certificate, shop & establishment licence or similar"),
                    obtainGuide = Guides.businessRegistration,
                ),
                selfEmployed,
            ),
            CuratedRequirement(
                DocumentRequirement(
                    id = "property_agreement",
                    name = "Sale agreement / allotment letter",
                    acceptedTypes = listOf(DocumentType.PROPERTY_DOCUMENT),
                    specs = listOf("Builder purchases: allotment letter and builder-buyer agreement"),
                ),
                answer(Q.PROPERTY, "Buying from a builder", "Resale property", includeIfUnanswered = true),
            ),
            CuratedRequirement(
                DocumentRequirement(
                    id = "title_documents",
                    name = "Title documents & previous sale deeds",
                    description = "Chain of ownership documents for the property or plot.",
                    acceptedTypes = listOf(DocumentType.PROPERTY_DOCUMENT),
                ),
                answer(Q.PROPERTY, "Resale property", "Construction on own plot", includeIfUnanswered = true),
            ),
            CuratedRequirement(
                DocumentRequirement(
                    id = "approved_plan",
                    name = "Approved building plan & NOCs",
                    acceptedTypes = listOf(DocumentType.PROPERTY_DOCUMENT),
                    specs = listOf("For own-plot construction, also a construction cost estimate"),
                ),
                answer(Q.PROPERTY, "Buying from a builder", "Construction on own plot", includeIfUnanswered = true),
            ),
            CuratedRequirement(
                DocumentRequirement(
                    id = "own_contribution",
                    name = "Receipts for own contribution paid",
                    acceptedTypes = listOf(DocumentType.PROPERTY_DOCUMENT, DocumentType.BANK_STATEMENT),
                    mandatory = false,
                ),
            ),
        ),
        assumptions = listOf(
            "The exact list varies by lender; lenders also check your credit report.",
            "Property documents are verified by the lender's legal and technical teams.",
        ),
    )

    val personalLoan = CuratedScenario(
        id = "in.personal_loan",
        title = "Personal loan application (India)",
        summary = "Applying for an unsecured personal loan from a bank or NBFC in India.",
        countryCode = "IN",
        keywords = listOf("personal loan", "instant loan", "unsecured loan"),
        questions = listOf(employmentQuestion),
        requirements = listOf(
            CuratedRequirement(panRequirement(listOf("Mandatory for loans"), listOf(Links.rbiKyc))),
            CuratedRequirement(identityProof(listOf(Links.rbiKyc))),
            CuratedRequirement(addressProof(listOf(Links.rbiKyc))),
            CuratedRequirement(photographs(listOf("Recent, 1–2 copies")).copy(mandatory = false)),
            CuratedRequirement(
                DocumentRequirement(
                    id = "salary_slips",
                    name = "Salary slips",
                    acceptedTypes = listOf(DocumentType.SALARY_SLIP),
                    specs = listOf("Last 3 months"),
                    obtainGuide = Guides.salarySlip,
                ),
                salaried,
            ),
            CuratedRequirement(
                DocumentRequirement(
                    id = "employment_proof",
                    name = "Employment proof",
                    acceptedTypes = listOf(DocumentType.EMPLOYMENT_PROOF, DocumentType.OFFER_LETTER),
                    mandatory = false,
                    obtainGuide = Guides.employerLetter,
                ),
                salaried,
            ),
            CuratedRequirement(
                DocumentRequirement(
                    id = "bank_statements",
                    name = "Bank statements",
                    acceptedTypes = listOf(DocumentType.BANK_STATEMENT),
                    specs = listOf("Salaried: last 3–6 months", "Self-employed: last 6–12 months"),
                    obtainGuide = Guides.bankStatement,
                ),
            ),
            CuratedRequirement(
                DocumentRequirement(
                    id = "itr",
                    name = "Income tax returns",
                    acceptedTypes = listOf(DocumentType.TAX_RETURN),
                    specs = listOf("Last 2 years"),
                    obtainGuide = Guides.filedReturn,
                ),
                selfEmployed,
            ),
            CuratedRequirement(
                DocumentRequirement(
                    id = "business_proof",
                    name = "Proof of business",
                    acceptedTypes = listOf(DocumentType.BUSINESS_REGISTRATION),
                    obtainGuide = Guides.businessRegistration,
                ),
                selfEmployed,
            ),
        ),
        assumptions = listOf("Lenders may ask for fewer documents for pre-approved offers and more for larger amounts."),
    )

    val savingsAccount = CuratedScenario(
        id = "in.savings_account",
        title = "Savings bank account opening (India)",
        summary = "Opening a savings bank account in India and completing KYC.",
        countryCode = "IN",
        keywords = listOf("bank account", "savings account", "open account", "account opening", "kyc"),
        questions = listOf(FollowUpQuestion(Q.HAS_PAN, "Do you have a PAN card?", listOf("Yes", "No"))),
        requirements = listOf(
            CuratedRequirement(
                panRequirement(listOf("Or Form 60 if you don't have a PAN"), listOf(Links.rbiKyc)),
                answer(Q.HAS_PAN, "Yes", includeIfUnanswered = true),
            ),
            CuratedRequirement(
                DocumentRequirement(
                    id = "form_60",
                    name = "Form 60",
                    description = "Declaration for people who don't have a PAN; available from the bank.",
                    acceptedTypes = listOf(DocumentType.APPLICATION_FORM),
                    citations = listOf(Links.rbiKyc),
                ),
                answer(Q.HAS_PAN, "No"),
            ),
            CuratedRequirement(
                DocumentRequirement(
                    id = "ovd",
                    name = "Officially Valid Document (identity & address)",
                    description = "One of: passport, driving licence, proof of possession of Aadhaar, voter ID, NREGA job card, or a letter from the National Population Register.",
                    acceptedTypes = kycIdentityTypes,
                    specs = listOf(
                        "If the document doesn't show your current address, banks may accept a recent utility bill temporarily",
                    ),
                    obtainGuide = Guides.aadhaar,
                    citations = listOf(Links.rbiKyc),
                ),
            ),
            CuratedRequirement(
                photographs(listOf("One recent photograph")).copy(citations = listOf(Links.rbiKyc)),
            ),
        ),
        assumptions = listOf("Many banks offer Aadhaar e-KYC or Video KYC, which can replace physical copies."),
    )

    private val oldOrUnsureRegime = answer(Q.REGIME, "Old regime", "Not sure", includeIfUnanswered = true)

    val itrSalaried = CuratedScenario(
        id = "in.itr_salaried",
        title = "Income tax return filing – salaried (India)",
        summary = "Filing an income tax return as a salaried individual in India.",
        countryCode = "IN",
        keywords = listOf("itr", "income tax return", "tax return", "file taxes", "tax filing", "income tax"),
        questions = listOf(
            FollowUpQuestion(Q.REGIME, "Which tax regime will you choose?", listOf("New regime", "Old regime", "Not sure")),
            FollowUpQuestion(Q.CAPITAL_GAINS, "Did you sell shares, mutual funds or property this year?", listOf("Yes", "No")),
            FollowUpQuestion(
                Q.HOUSE_PROPERTY,
                "Do you own a house property?",
                listOf("Yes, with a home loan", "Yes, rented out", "Yes, self-occupied without a loan", "No"),
            ),
            FollowUpQuestion(Q.HRA, "Do you pay rent and want to claim HRA?", listOf("Yes", "No")),
        ),
        requirements = listOf(
            CuratedRequirement(panRequirement(listOf("Must be linked with Aadhaar"), listOf(Links.incomeTax))),
            CuratedRequirement(
                DocumentRequirement(
                    id = "aadhaar",
                    name = "Aadhaar",
                    description = "Used for e-verification of the return via Aadhaar OTP.",
                    acceptedTypes = listOf(DocumentType.AADHAAR),
                    obtainGuide = Guides.aadhaar,
                    citations = listOf(Links.incomeTax),
                ),
            ),
            CuratedRequirement(
                DocumentRequirement(
                    id = "form_16",
                    name = "Form 16",
                    acceptedTypes = listOf(DocumentType.FORM_16),
                    specs = listOf("Part A and Part B from every employer in the financial year"),
                    obtainGuide = Guides.form16,
                    citations = listOf(Links.incomeTax),
                ),
            ),
            CuratedRequirement(
                DocumentRequirement(
                    id = "form_26as_ais",
                    name = "Form 26AS and AIS",
                    description = "Tax credit statement and Annual Information Statement to reconcile TDS and income.",
                    acceptedTypes = listOf(DocumentType.TAX_STATEMENT),
                    obtainGuide = Guides.taxStatement,
                    citations = listOf(Links.incomeTax),
                ),
            ),
            CuratedRequirement(
                DocumentRequirement(
                    id = "interest_statements",
                    name = "Bank interest statements",
                    description = "Savings and fixed deposit interest certificates or statements for the year.",
                    acceptedTypes = listOf(DocumentType.BANK_STATEMENT),
                    obtainGuide = Guides.bankStatement,
                    citations = listOf(Links.incomeTax),
                ),
            ),
            CuratedRequirement(
                DocumentRequirement(
                    id = "investment_proofs",
                    name = "Deduction proofs",
                    description = "Proofs for deductions such as 80C (PPF, ELSS, insurance), 80D (health insurance) and others.",
                    acceptedTypes = listOf(DocumentType.INVESTMENT_PROOF, DocumentType.INSURANCE_POLICY),
                    specs = listOf("Most of these deductions apply only under the old regime"),
                    citations = listOf(Links.incomeTax),
                ),
                oldOrUnsureRegime,
            ),
            CuratedRequirement(
                DocumentRequirement(
                    id = "rent_proofs",
                    name = "Rent receipts & rent agreement",
                    description = "For claiming HRA exemption.",
                    acceptedTypes = listOf(DocumentType.RENT_RECEIPTS, DocumentType.RENT_AGREEMENT),
                    specs = listOf(
                        "HRA exemption is available only under the old regime",
                        "Landlord's PAN is needed if annual rent exceeds ₹1 lakh",
                    ),
                    citations = listOf(Links.incomeTax),
                ),
                answer(Q.HRA, "Yes") and oldOrUnsureRegime,
            ),
            CuratedRequirement(
                DocumentRequirement(
                    id = "home_loan_interest",
                    name = "Home loan interest certificate",
                    acceptedTypes = listOf(DocumentType.LOAN_INTEREST_CERTIFICATE),
                    specs = listOf(
                        "Provisional or final certificate from the lender for the financial year",
                        "Interest on a self-occupied home is deductible only under the old regime",
                    ),
                    citations = listOf(Links.incomeTax),
                ),
                answer(Q.HOUSE_PROPERTY, "Yes, with a home loan"),
            ),
            CuratedRequirement(
                DocumentRequirement(
                    id = "rental_income",
                    name = "Rental income records",
                    description = "Rent agreement, rent received and municipal taxes paid for let-out property.",
                    acceptedTypes = listOf(DocumentType.RENT_AGREEMENT, DocumentType.BANK_STATEMENT),
                    citations = listOf(Links.incomeTax),
                ),
                answer(Q.HOUSE_PROPERTY, "Yes, rented out"),
            ),
            CuratedRequirement(
                DocumentRequirement(
                    id = "capital_gains",
                    name = "Capital gains statements",
                    acceptedTypes = listOf(DocumentType.CAPITAL_GAINS_STATEMENT, DocumentType.PROPERTY_DOCUMENT),
                    specs = listOf("Broker tax P&L, mutual fund capital gains statements, property sale/purchase deeds"),
                    obtainGuide = Guides.capitalGains,
                    citations = listOf(Links.incomeTax),
                ),
                answer(Q.CAPITAL_GAINS, "Yes"),
            ),
            CuratedRequirement(
                DocumentRequirement(
                    id = "previous_itr",
                    name = "Previous year's ITR acknowledgement",
                    acceptedTypes = listOf(DocumentType.TAX_RETURN),
                    mandatory = false,
                    specs = listOf("Useful for carried-forward losses and as a reference"),
                    obtainGuide = Guides.filedReturn,
                ),
            ),
        ),
        assumptions = listOf(
            "The due date for individuals without an audit is usually 31 July of the assessment year; check for extensions.",
            "A pre-validated bank account on the e-Filing portal is needed to receive refunds.",
        ),
    )

    val jobOnboarding = CuratedScenario(
        id = "in.job_onboarding",
        title = "Joining a new job (India)",
        summary = "Documents an employer in India typically asks for when you join.",
        countryCode = "IN",
        keywords = listOf("new job", "joining", "onboarding", "joining formalities", "offer letter", "job switch"),
        questions = listOf(
            FollowUpQuestion(Q.EXPERIENCE, "Is this your first job?", listOf("Fresher", "Experienced")),
            FollowUpQuestion(
                Q.MID_YEAR,
                "Did you work for another employer earlier in this financial year?",
                listOf("Yes", "No"),
            ),
        ),
        requirements = listOf(
            CuratedRequirement(
                DocumentRequirement(
                    id = "offer_letter",
                    name = "Signed offer / appointment letter",
                    acceptedTypes = listOf(DocumentType.OFFER_LETTER),
                ),
            ),
            CuratedRequirement(panRequirement(listOf("Needed for payroll and TDS"), listOf(Links.incomeTax))),
            CuratedRequirement(
                DocumentRequirement(
                    id = "aadhaar",
                    name = "Aadhaar",
                    description = "Used for EPF / UAN KYC.",
                    acceptedTypes = listOf(DocumentType.AADHAAR),
                    obtainGuide = Guides.aadhaar,
                    citations = listOf(Links.epfo),
                ),
            ),
            CuratedRequirement(addressProof(emptyList(), listOf(DocumentType.RENT_AGREEMENT))),
            CuratedRequirement(
                DocumentRequirement(
                    id = "education",
                    name = "Education certificates & marksheets",
                    acceptedTypes = listOf(DocumentType.EDUCATION_CERTIFICATE),
                    specs = listOf("Highest degree; some employers also ask for Class 10 and 12"),
                ),
            ),
            CuratedRequirement(photographs(listOf("Recent, usually 2–4 copies"))),
            CuratedRequirement(
                DocumentRequirement(
                    id = "bank_details",
                    name = "Bank account proof",
                    description = "Cancelled cheque or passbook / statement front page for the salary account.",
                    acceptedTypes = listOf(DocumentType.CANCELLED_CHEQUE, DocumentType.BANK_STATEMENT),
                    obtainGuide = Guides.bankStatement,
                ),
            ),
            CuratedRequirement(
                DocumentRequirement(
                    id = "relieving_letter",
                    name = "Relieving letter from previous employer",
                    acceptedTypes = listOf(DocumentType.RELIEVING_LETTER),
                    specs = listOf("Keep your EPF UAN handy for transfer"),
                    obtainGuide = Guides.uan,
                    citations = listOf(Links.epfo),
                ),
                answer(Q.EXPERIENCE, "Experienced"),
            ),
            CuratedRequirement(
                DocumentRequirement(
                    id = "experience_letter",
                    name = "Experience letter",
                    acceptedTypes = listOf(DocumentType.EXPERIENCE_LETTER, DocumentType.RELIEVING_LETTER),
                    obtainGuide = Guides.employerLetter,
                ),
                answer(Q.EXPERIENCE, "Experienced"),
            ),
            CuratedRequirement(
                DocumentRequirement(
                    id = "previous_payslips",
                    name = "Last payslips from previous employer",
                    acceptedTypes = listOf(DocumentType.SALARY_SLIP),
                    specs = listOf("Usually the last 3 months"),
                    obtainGuide = Guides.salarySlip,
                ),
                answer(Q.EXPERIENCE, "Experienced"),
            ),
            CuratedRequirement(
                DocumentRequirement(
                    id = "previous_income_details",
                    name = "Previous employer's income & TDS details",
                    description = "Form 16 / Form 12B or a salary and TDS summary for this financial year, so your new employer deducts tax correctly.",
                    acceptedTypes = listOf(DocumentType.FORM_16, DocumentType.SALARY_SLIP),
                    obtainGuide = Guides.form16,
                    citations = listOf(Links.incomeTax),
                ),
                answer(Q.EXPERIENCE, "Experienced") and answer(Q.MID_YEAR, "Yes"),
            ),
        ),
        assumptions = listOf("Employers differ; check your onboarding portal or HR email for their exact list."),
    )

    val all: List<CuratedScenario> = listOf(passport, homeLoan, personalLoan, savingsAccount, itrSalaried, jobOnboarding)
}
