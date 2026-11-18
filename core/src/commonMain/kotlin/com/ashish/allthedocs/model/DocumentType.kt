package com.ashish.allthedocs.model

import kotlinx.serialization.KSerializer
import kotlinx.serialization.Serializable
import kotlinx.serialization.descriptors.PrimitiveKind
import kotlinx.serialization.descriptors.PrimitiveSerialDescriptor
import kotlinx.serialization.descriptors.SerialDescriptor
import kotlinx.serialization.encoding.Decoder
import kotlinx.serialization.encoding.Encoder

@Serializable(with = DocumentTypeSerializer::class)
enum class DocumentType(
    val key: String,
    val displayName: String,
    val category: DocumentCategory,
    val hasExpiry: Boolean = false,
    val isIssuable: Boolean = true,
) {
    PASSPORT("passport", "Passport", DocumentCategory.IDENTITY, hasExpiry = true),
    NATIONAL_ID("national_id", "National ID card", DocumentCategory.IDENTITY, hasExpiry = true),
    AADHAAR("aadhaar", "Aadhaar card", DocumentCategory.IDENTITY),
    PAN_CARD("pan_card", "PAN card", DocumentCategory.IDENTITY),
    VOTER_ID("voter_id", "Voter ID", DocumentCategory.IDENTITY),
    DRIVING_LICENCE("driving_licence", "Driving licence", DocumentCategory.IDENTITY, hasExpiry = true),
    BIRTH_CERTIFICATE("birth_certificate", "Birth certificate", DocumentCategory.IDENTITY),
    PHOTOGRAPH("photograph", "Passport-size photograph", DocumentCategory.IDENTITY, isIssuable = false),
    VISA("visa", "Visa / residence permit", DocumentCategory.TRAVEL, hasExpiry = true),
    UTILITY_BILL("utility_bill", "Utility bill", DocumentCategory.PROPERTY),
    RENT_AGREEMENT("rent_agreement", "Rent agreement", DocumentCategory.PROPERTY, hasExpiry = true, isIssuable = false),
    RENT_RECEIPTS("rent_receipts", "Rent receipts", DocumentCategory.PROPERTY, isIssuable = false),
    BANK_STATEMENT("bank_statement", "Bank statement", DocumentCategory.FINANCIAL),
    CANCELLED_CHEQUE("cancelled_cheque", "Cancelled cheque", DocumentCategory.FINANCIAL, isIssuable = false),
    SALARY_SLIP("salary_slip", "Salary slip", DocumentCategory.EMPLOYMENT),
    FORM_16("form_16", "Form 16", DocumentCategory.TAX),
    TAX_STATEMENT("tax_statement", "Tax statement (Form 26AS / AIS)", DocumentCategory.TAX),
    TAX_RETURN("tax_return", "Income tax return / ITR-V", DocumentCategory.TAX),
    INVESTMENT_PROOF("investment_proof", "Investment / deduction proof", DocumentCategory.TAX),
    LOAN_INTEREST_CERTIFICATE("loan_interest_certificate", "Loan interest certificate", DocumentCategory.FINANCIAL),
    CAPITAL_GAINS_STATEMENT("capital_gains_statement", "Capital gains statement", DocumentCategory.TAX),
    OFFER_LETTER("offer_letter", "Offer / appointment letter", DocumentCategory.EMPLOYMENT),
    RELIEVING_LETTER("relieving_letter", "Relieving letter", DocumentCategory.EMPLOYMENT),
    EXPERIENCE_LETTER("experience_letter", "Experience letter", DocumentCategory.EMPLOYMENT),
    EMPLOYMENT_PROOF("employment_proof", "Employment proof", DocumentCategory.EMPLOYMENT),
    EDUCATION_CERTIFICATE("education_certificate", "Education certificate / marksheet", DocumentCategory.EDUCATION),
    PROPERTY_DOCUMENT("property_document", "Property document", DocumentCategory.PROPERTY),
    BUSINESS_REGISTRATION("business_registration", "Business registration (GST / Udyam)", DocumentCategory.FINANCIAL),
    FINANCIAL_STATEMENT("financial_statement", "Audited financial statement", DocumentCategory.FINANCIAL),
    INSURANCE_POLICY("insurance_policy", "Insurance policy", DocumentCategory.HEALTH_INSURANCE, hasExpiry = true),
    MEDICAL_RECORD("medical_record", "Medical record", DocumentCategory.HEALTH_INSURANCE),
    TRAVEL_BOOKING("travel_booking", "Travel booking / itinerary", DocumentCategory.TRAVEL),
    APPLICATION_FORM("application_form", "Application form", DocumentCategory.OTHER, isIssuable = false),
    OTHER("other", "Other document", DocumentCategory.OTHER, isIssuable = false),
    ;

    companion object {
        private val byKey = entries.associateBy { it.key }

        fun fromKey(key: String?): DocumentType = byKey[key?.trim()?.lowercase()] ?: OTHER
    }
}

enum class DocumentCategory(val displayName: String) {
    IDENTITY("Identity"),
    EDUCATION("Education"),
    EMPLOYMENT("Employment"),
    FINANCIAL("Banking & finance"),
    TAX("Tax"),
    PROPERTY("Property & residence"),
    TRAVEL("Travel & immigration"),
    HEALTH_INSURANCE("Health & insurance"),
    OTHER("Other"),
}

enum class VaultSection(val displayName: String) {
    ISSUED("Issued"),
    UPLOADED("Uploaded"),
    ;

    companion object {
        fun of(type: DocumentType, verified: Boolean, hasIssues: Boolean, signatureValid: Boolean = false): VaultSection =
            if (type.isIssuable && !hasIssues && (verified || signatureValid)) ISSUED else UPLOADED
    }
}

enum class DocumentSource(val displayName: String) {
    SCAN("Scanned"),
    FILE("Imported file"),
    DIGILOCKER("DigiLocker"),
    SHARED("Shared from another app"),
    GMAIL("Gmail"),
    GOOGLE_DRIVE("Google Drive"),
    OUTLOOK("Outlook"),
    ONEDRIVE("OneDrive"),
    CONNECTED_APP("Connected app"),
}

object DocumentSearchKeywords {
    private val keywords: Map<DocumentType, List<String>> = mapOf(
        DocumentType.PASSPORT to listOf("passport"),
        DocumentType.NATIONAL_ID to listOf("national id", "identity card"),
        DocumentType.AADHAAR to listOf("aadhaar", "aadhar", "e-aadhaar"),
        DocumentType.PAN_CARD to listOf("PAN card", "e-PAN", "permanent account number"),
        DocumentType.VOTER_ID to listOf("voter id", "EPIC"),
        DocumentType.DRIVING_LICENCE to listOf("driving licence", "driving license"),
        DocumentType.BIRTH_CERTIFICATE to listOf("birth certificate"),
        DocumentType.PHOTOGRAPH to listOf("passport photo", "photograph"),
        DocumentType.VISA to listOf("visa", "residence permit"),
        DocumentType.UTILITY_BILL to listOf("electricity bill", "utility bill", "gas bill", "broadband bill"),
        DocumentType.RENT_AGREEMENT to listOf("rent agreement", "lease agreement", "rental agreement"),
        DocumentType.RENT_RECEIPTS to listOf("rent receipt"),
        DocumentType.BANK_STATEMENT to listOf("bank statement", "account statement", "e-statement"),
        DocumentType.CANCELLED_CHEQUE to listOf("cancelled cheque", "canceled cheque"),
        DocumentType.SALARY_SLIP to listOf("payslip", "salary slip", "pay slip"),
        DocumentType.FORM_16 to listOf("Form 16", "Form16"),
        DocumentType.TAX_STATEMENT to listOf("Form 26AS", "Annual Information Statement", "AIS"),
        DocumentType.TAX_RETURN to listOf("ITR-V", "income tax return", "ITR acknowledgement"),
        DocumentType.INVESTMENT_PROOF to listOf("investment proof", "80C", "premium receipt"),
        DocumentType.LOAN_INTEREST_CERTIFICATE to listOf("interest certificate"),
        DocumentType.CAPITAL_GAINS_STATEMENT to listOf("capital gains", "tax P&L"),
        DocumentType.OFFER_LETTER to listOf("offer letter", "appointment letter"),
        DocumentType.RELIEVING_LETTER to listOf("relieving letter"),
        DocumentType.EXPERIENCE_LETTER to listOf("experience letter", "service certificate"),
        DocumentType.EMPLOYMENT_PROOF to listOf("employment certificate", "employment verification"),
        DocumentType.EDUCATION_CERTIFICATE to listOf("marksheet", "degree certificate", "transcript", "mark sheet"),
        DocumentType.PROPERTY_DOCUMENT to listOf("sale deed", "allotment letter", "property"),
        DocumentType.BUSINESS_REGISTRATION to listOf("GST registration", "Udyam"),
        DocumentType.FINANCIAL_STATEMENT to listOf("balance sheet", "audited financial"),
        DocumentType.INSURANCE_POLICY to listOf("insurance policy", "policy schedule"),
        DocumentType.MEDICAL_RECORD to listOf("medical report", "prescription", "discharge summary"),
        DocumentType.TRAVEL_BOOKING to listOf("e-ticket", "itinerary", "booking confirmation"),
        DocumentType.APPLICATION_FORM to listOf("application form"),
    )

    fun forTypes(types: List<DocumentType>): List<String> =
        types.flatMap { keywords[it] ?: listOf(it.displayName) }.distinct()
}

object DocumentTypeGuesser {
    private val rules: List<Pair<Regex, DocumentType>> = listOf(
        Regex("pancr|\\bpan\\b|pan[ _-]?card") to DocumentType.PAN_CARD,
        Regex("adhar|aadhaar|aadhar") to DocumentType.AADHAAR,
        Regex("drvlc|driving|licen[cs]e") to DocumentType.DRIVING_LICENCE,
        Regex("passport") to DocumentType.PASSPORT,
        Regex("voter|epic") to DocumentType.VOTER_ID,
        Regex("birth") to DocumentType.BIRTH_CERTIFICATE,
        Regex("sscer|hscer|marksheet|mark[ _-]?sheet|degree|diploma|certificate") to DocumentType.EDUCATION_CERTIFICATE,
        Regex("form[ _-]?16") to DocumentType.FORM_16,
        Regex("26as|\\bais\\b") to DocumentType.TAX_STATEMENT,
        Regex("itr") to DocumentType.TAX_RETURN,
        Regex("payslip|salary") to DocumentType.SALARY_SLIP,
        Regex("statement") to DocumentType.BANK_STATEMENT,
        Regex("insurance|policy") to DocumentType.INSURANCE_POLICY,
    )

    fun fromFileName(fileName: String): DocumentType? {
        val name = fileName.lowercase().substringBeforeLast('.')
        return rules.firstOrNull { (pattern, _) -> pattern.containsMatchIn(name) }?.second
    }
}

object DocumentTypeSerializer : KSerializer<DocumentType> {
    override val descriptor: SerialDescriptor =
        PrimitiveSerialDescriptor("com.ashish.allthedocs.model.DocumentType", PrimitiveKind.STRING)

    override fun serialize(encoder: Encoder, value: DocumentType) = encoder.encodeString(value.key)

    override fun deserialize(decoder: Decoder): DocumentType = DocumentType.fromKey(decoder.decodeString())
}
