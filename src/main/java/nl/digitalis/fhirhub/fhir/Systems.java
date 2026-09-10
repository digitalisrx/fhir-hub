package nl.digitalis.fhirhub.fhir;

/**
 * Canonical system URIs for the code systems this interface exchanges.
 *
 * <p>Every OID here was taken from the HL7 NL OID register
 * (https://hl7.nl/images/downloads/openbaar/OID/HL7NL_OID_register.pdf) rather than from
 * documentation or memory. A wrong digit in a {@code system} is a silent interoperability
 * bug, so do not edit these without checking the register.
 */
public final class Systems {

	/** G-Standaard GPK (Generieke Product Kode), bestand 711. */
	public static final String GPK = "urn:oid:2.16.840.1.113883.2.4.4.1";

	/** G-Standaard HPK (Handels Product Kode). */
	public static final String HPK = "urn:oid:2.16.840.1.113883.2.4.4.7";

	/** G-Standaard PRK (Voorschrijfproducten). */
	public static final String PRK = "urn:oid:2.16.840.1.113883.2.4.4.10";

	/** G-Standaard thesaurus basiseenheden — the system for supply units such as "ST". */
	public static final String G_STANDAARD_BASISEENHEID = "urn:oid:2.16.840.1.113883.2.4.4.1.900.2";

	/**
	 * G-Standaard SSK — stofnaamcode in combination with route of administration.
	 *
	 * <p>Pinned from Nictiz's own published artifacts rather than the HL7 NL register summary,
	 * which does not list it: the {@code zib2020} package carries a CodeSystem at this OID
	 * titled "G-standaard Stofnaamcode i.c.m. toedieningsweg (SSK)", and the DECOR ValueSet
	 * {@code …60.40.2.8.2.13} (VeroorzakendeStofSSKCodelijst) includes it. That ValueSet is one
	 * of the five that make up the AllergyIntolerance CausativeAgent binding
	 * {@code …60.121.11.2}.
	 */
	public static final String G_STANDAARD_SSK = "urn:oid:2.16.840.1.113883.2.4.4.1.725";

	/**
	 * G-Standaard SNK — stofnaamcode (generic name, bestand 750).
	 *
	 * <p>The only one of the four that the public HL7 NL OID register does list, as "G-Standaard
	 * generieke namen (Bestand 750)". Nictiz's VeroorzakendeStofSNKCodelijst
	 * ({@code …60.40.2.8.2.14}) uses the same OID.
	 */
	public static final String G_STANDAARD_SNK = "urn:oid:2.16.840.1.113883.2.4.4.1.750";

	/**
	 * G-Standaard OGGrp — thesaurus 122, "Ongewenste medicatiegroepen".
	 *
	 * <p><strong>The one mapping here that is inferred rather than stated.</strong> Nothing
	 * published by Nictiz or HL7 NL uses the token {@code OGGrp}, so the identification rests on
	 * three things: thesaurus 122 is the only group-level G-Standaard system Nictiz publishes at
	 * all; it is the third G-Standaard member of the CausativeAgent binding, alongside exactly
	 * the SSK and SNK that {@code prescriptor-api}'s {@code getAllergies} pairs with it; and the
	 * abbreviation fits "ongewenste groep" rather than "overgevoeligheidsgroep". Confirm against
	 * a G-Standaard bestandsbeschrijving before relying on it for anything that cannot be
	 * corrected later.
	 */
	public static final String G_STANDAARD_OGGRP = "urn:oid:2.16.840.1.113883.2.4.4.1.902.122";

	/**
	 * G-Standaard contra-indications, thesaurus 40.
	 *
	 * <p>From the CodeSystem in Nictiz's {@code zib2020} package titled "G-Standaard Contra
	 * Indicaties (Tabel 40)", which is the sole member of MedicatieContraIndicatieNaamCodelijst
	 * ({@code …60.40.2.9.14.1}) — the required binding on {@code nl-core-MedicationContraIndication}.
	 */
	public static final String G_STANDAARD_CONTRA_INDICATIE = "urn:oid:2.16.840.1.113883.2.4.4.1.902.40";

	/** ICPC-1 NL, including thesaurus. */
	public static final String ICPC_1_NL = "urn:oid:2.16.840.1.113883.2.4.4.31.1";

	/**
	 * LOINC, the system laboratory determinations are coded in on the way in.
	 *
	 * <p>Any LOINC code is accepted; only the determinations in {@link LabDeterminations} feed a
	 * beslisregel or the dose-band model, so a code outside that list is accepted but forwarded
	 * nowhere. A code the upstream does read is forwarded as it arrives — the upstream tests LOINC
	 * numbers itself.
	 */
	public static final String LOINC = "http://loinc.org";

	/** WHO ATC classification. */
	public static final String ATC = "http://www.whocc.no/atc";

	/** UCUM, used for the supply duration in days. */
	public static final String UCUM = "http://unitsofmeasure.org";

	/** HL7 communication categories, for the {@code instruction} category on patient advice. */
	public static final String COMMUNICATION_CATEGORY =
			"http://terminology.hl7.org/CodeSystem/communication-category";

	/**
	 * G-Standaard bijzondere kenmerken (bestand BST401T, thesaurus BST922T).
	 *
	 * <p>No OID is registered for this table, so a Digitalis-local system URI is used.
	 * Relevant codes: 2 = product valt onder Opiumwet in volle omvang, 65 = product valt
	 * onder Opiumwet; afhandeling als UR, 107 = grondstof valt onder Opiumwet in volle omvang.
	 */
	public static final String G_STANDAARD_BIJZONDER_KENMERK =
			"http://spec.digitalis.nl/fhir/CodeSystem/gstandaard-bijzonder-kenmerk";

	/**
	 * The clinical-rules service's own report id — the {@code CRID} — as the identifier system of
	 * the Bundle a medication-surveillance check returns.
	 *
	 * <p>Two identifier systems are Digitalis-local for the same reason the one above is: nothing
	 * is registered for either, and both name something the upstream assigns rather than a
	 * terminology anybody publishes. They are {@code /sid/} rather than {@code /CodeSystem/}
	 * because that is what FHIR calls the namespace of an identifier as opposed to a code.
	 *
	 * <p>This one is worth carrying: it is what Digitalis support asks for when a prescriber
	 * queries a signal, and it is the only handle that ties a response to the run that produced
	 * it.
	 */
	public static final String CRS_REPORT = "http://spec.digitalis.nl/fhir/sid/crs-report";

	/**
	 * The identifier system of one rule: {@code MFB-0000000068-v000006} for a
	 * medisch-farmaceutische beslisregel, {@code hub-doublemedication-prkA-1090} and its siblings
	 * for the G-Standaard checks the Hub runs itself.
	 *
	 * <p>An identifier and not a {@code Coding}, deliberately. The set is open — a beslisregel is
	 * published, revised and withdrawn by the G-Standaard's editors on their own schedule — so
	 * there is no code system anyone could define here that would not be out of date, and
	 * {@code DetectedIssue.identifier} is what FHIR has for "the id the source gave this".
	 */
	public static final String CRS_RULE = "http://spec.digitalis.nl/fhir/sid/crs-rule";

	private Systems() {
	}
}
