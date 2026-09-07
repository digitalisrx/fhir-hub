package nl.digitalis.fhirhub.hub;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.LocalDate;
import java.util.List;

import org.junit.jupiter.api.Test;

import nl.digitalis.fhirhub.Fixtures;
import nl.digitalis.fhirhub.fhir.LabDeterminations;
import nl.digitalis.fhirhub.model.CodedItem;
import nl.digitalis.fhirhub.model.LabResult;
import nl.digitalis.fhirhub.model.MedicationCodes;
import nl.digitalis.fhirhub.model.PrescriptorCredentials;
import nl.digitalis.fhirhub.model.SurveillanceDrug;
import nl.digitalis.fhirhub.model.SurveillanceRequest;

/**
 * Pins the wire format of the {@code MedicationSurveillance} call.
 *
 * <p>Most of these are about a single attribute, and each of those attributes is one whose absence
 * costs a class of check without costing a response: no {@code pending} and the Hub's own
 * G-Standaard checks do not run, no {@code trigger} and the beslisregels do not know what is being
 * prescribed, no {@code date} and a drug is filtered out of the dossier, no {@code ATC} and most
 * rules never look at it. There is nothing in a 200 that would show any of that, which is why they
 * are pinned here.
 */
class MedicationSurveillanceRequestBuilderTest {

	private final MedicationSurveillanceRequestBuilder builder =
			new MedicationSurveillanceRequestBuilder(new LabDeterminations());

	@Test
	void wrapsTheDocumentInTheEnvelopeTheHubResolves() {
		String xml = build();

		assertThat(xml)
				.contains("<Envelope xmlns=\"http://schemas.xmlsoap.org/soap/envelope/\">")
				.contains("<MedicationSurveillance xmlns=\"http://hub.digitalis.nl/call\">")
				// The one element the Hub actually looks for, and the namespace it looks for it in.
				.contains("<DigitalisRx xmlns=\"http://digitalis.nl/schema/DigitalisRx\">");
	}

	/**
	 * The two marks that say which drug is being checked. They are read by different engines —
	 * see the class Javadoc of the builder — so a request that carries one and not the other is
	 * answered by half the checks and looks no different.
	 */
	@Test
	void marksTheProposedPrescriptionForBothEngines() {
		String xml = build();

		assertThat(drugElement(xml, 0))
				.contains("pending=\"true\"")
				.contains("trigger=\"true\"");
	}

	/** And the standing dossier is marked as not pending, which is what makes it the context. */
	@Test
	void marksCurrentMedicationAsNotPending() {
		String xml = build();

		assertThat(drugElement(xml, 1))
				.contains("pending=\"false\"")
				.doesNotContain("trigger");
	}

	@Test
	void writesEveryProductCodeAndTheLevelTheHostUsed() {
		String xml = build();

		assertThat(drugElement(xml, 0))
				.contains("PRK=\"1090\"")
				.contains("GPK=\"3816\"")
				.contains("HPK=\"693332\"")
				.contains("selected=\"HPK\"");

		assertThat(drugElement(xml, 1))
				.contains("PRK=\"27278\"")
				.contains("GPK=\"51004\"")
				.doesNotContain("HPK=")
				.contains("selected=\"PRK\"");
	}

	@Test
	void writesTheAtcTheRulesSelectOn() {
		assertThat(drugElement(build(), 0)).contains("ATC=\"A10BA02\"");
	}

	/**
	 * The schema reserves {@code ZZZZZZ} for a product without an ATC — a bandage, a homeopathic
	 * product — and the attribute is required, so it is written rather than left off.
	 */
	@Test
	void writesTheReservedAtcForAProductThatHasNone() {
		SurveillanceDrug bandage = new SurveillanceDrug("rx-1",
				new MedicationCodes(12345, 333333, null, null),
				null, "WINDSEL", null, null, null, null, null,
				LocalDate.of(2026, 9, 6), null);

		String xml = builder.medicationSurveillance(request(List.of(bandage), List.of()), credentials());

		assertThat(drugElement(xml, 0)).contains("ATC=\"ZZZZZZ\"");
	}

	/**
	 * Dates as {@code yyyy-mm-dd}: the engine's date parser branches on the string being exactly
	 * ten characters, and both engines filter the dossier on this attribute.
	 */
	@Test
	void writesDatesInTheFormTheEngineParses() {
		String xml = build();

		assertThat(drugElement(xml, 0))
				.contains("date=\"2026-09-06\"")
				.contains("dateEnd=\"2026-09-26\"");
		assertThat(drugElement(xml, 1))
				.contains("date=\"2026-09-06\"")
				.as("a host that stated no end has not stated one")
				.doesNotContain("dateEnd");
	}

	@Test
	void writesTheCodedDosageSupplyAndReasonForPrescribing() {
		String drug = drugElement(build(), 0);

		assertThat(drug)
				.contains("directionCoded=\"1D1T\"")
				.contains("directionCaption=\"1 X per dag 1 tablet\"")
				.contains("supplyQuantity=\"20\"")
				.contains("supplyUnit=\"ST\"")
				.contains("<RVV codeSystem=\"ICPC1\" codeValue=\"K86\"");
	}

	/**
	 * The caption is what the Hub's duplicate-medication check titles its signal with, and what
	 * the prescriber reads. It is also read positionally there, so an entry without one costs the
	 * classic half of the check.
	 */
	@Test
	void writesTheCaptionEveryDrugCarries() {
		assertThat(drugElement(build(), 0)).contains("caption=\"METFORMINE TABLET   500MG\"");
	}

	/** PDD and DDD are absent, deliberately and documented: see the builder's Javadoc. */
	@Test
	void writesNoPrescribedOrDefinedDailyDose() {
		assertThat(build()).doesNotContain("PDD=").doesNotContain("DDD=");
	}

	@Test
	void routesConditionsToIndicationsAndContraIndicationsByTheirCodeSystem() {
		String xml = build();

		assertThat(xml)
				.contains("<ICPC value=\"K86\"")
				.contains("<GStandaard CICode=\"1320\"");
	}

	@Test
	void writesEveryAllergySubsystemTheClassicCheckReads() {
		String xml = build();

		assertThat(xml)
				.contains("<GStandaard OGGrp=\"35\"")
				.contains("<GStandaard SNK=\"10499\"");
	}

	/**
	 * The Hub writes this caption into the title and the body of the signal it raises, so an empty
	 * one produces "Allergie  (ongewenste groep)" and "In het dossier is een allergie ( (SNK))
	 * geregistreerd" — measured against the live service. The UID comes back in the finding's
	 * context, which is what lets a host point the signal at its own record.
	 */
	@Test
	void writesTheHostsOwnWordingAndRecordIdOnEveryCodedItem() {
		String xml = builder.medicationSurveillance(new SurveillanceRequest(Fixtures.XIS, "F",
				LocalDate.of(2007, 9, 7),
				List.of(new CodedItem("OGGrp", "35", "PENICILLINES", "5469")),
				List.of(new CodedItem("CICode", "1320", "ZWANGERSCHAP", "5468")),
				List.of(new CodedItem("ICPC", "K86", "Diabetes mellitus type 2", "7701")),
				Fixtures.surveillanceRequest().proposed(), List.of(), List.of()),
				credentials());

		assertThat(xml)
				.contains("<GStandaard OGGrp=\"35\" caption=\"PENICILLINES\" UID=\"5469\"")
				.contains("<GStandaard CICode=\"1320\" caption=\"ZWANGERSCHAP\" UID=\"5468\"")
				.contains("caption=\"Diabetes mellitus type 2\" UID=\"7701\"");
	}

	/**
	 * And where the host sent no wording at all, the code goes in the sentence rather than nothing:
	 * "een allergie (SNK 10499)" says something, "een allergie ()" does not.
	 */
	@Test
	void fallsBackToTheCodeRatherThanAnEmptyCaption() {
		String xml = builder.medicationSurveillance(new SurveillanceRequest(Fixtures.XIS, "F",
				LocalDate.of(2007, 9, 7), List.of(new CodedItem("SNK", "10499")), List.of(), List.of(),
				Fixtures.surveillanceRequest().proposed(), List.of(), List.of()),
				credentials());

		assertThat(xml).contains("<GStandaard SNK=\"10499\" caption=\"SNK 10499\" UID=\"\"");
	}

	/**
	 * The time of day goes into {@code date}, because that is the attribute the engine reads:
	 * nothing in either the Hub or the engine looks at {@code time}. Several results for one
	 * determination are resolved by taking the most recent, and a date-only value puts every
	 * result of one day at midnight.
	 */
	@Test
	void foldsTheTimeOfDayIntoTheDateTheEngineCompares() {
		assertThat(build())
				.contains("date=\"2026-09-06T09:05:11\"")
				.contains("num=\"62238-1\"")
				.contains("value=\"35\"");
	}

	/**
	 * The dose check reads a weight by NHG id and cannot read a LOINC code, so both forms go out.
	 * Kilograms on both sides.
	 */
	@Test
	void writesAWeightInItsNhgIdentityAsWellAsItsLoincOne() {
		String xml = withLab(new LabResult("29463-7", "Gewicht", "kg",
				LocalDate.of(2026, 9, 6), null, "70", "obs-1"));

		assertThat(xml)
				.contains("<LOINC num=\"29463-7\" caption=\"Gewicht\" date=\"2026-09-06\" value=\"70\" UID=\"obs-1\"")
				.contains("<NHG id=\"357\" memo=\"GEW\" mat=\"AO\" caption=\"Gewicht\""
						+ " date=\"2026-09-06\" value=\"70\" UID=\"obs-1\"");
	}

	/**
	 * And a length goes out in metres, which is what NHG determination 560 records — the
	 * centimetres this interface holds it in are the LOINC form's unit. The Hub's Mosteller
	 * expression multiplies what it finds by 100 to get the centimetres the formula wants, so
	 * sending centimetres here would be a body surface ten times too large.
	 */
	@Test
	void writesALengthInMetresInItsNhgIdentity() {
		String xml = withLab(new LabResult("8302-2", "Lengte", "cm",
				LocalDate.of(2026, 9, 6), null, "178", "obs-2"));

		assertThat(xml)
				.contains("<LOINC num=\"8302-2\" caption=\"Lengte\" date=\"2026-09-06\" value=\"178\"")
				.contains("<NHG id=\"560\" memo=\"LNGP\" mat=\"AO\" caption=\"Lengte\""
						+ " date=\"2026-09-06\" value=\"1.78\" UID=\"obs-2\"");
	}

	/** Everything the beslisregels read stays LOINC-only: two rows of NHG is the whole exception. */
	@Test
	void writesNoNhgIdentityForADeterminationTheRulesRead() {
		assertThat(build())
				.contains("<LOINC num=\"62238-1\"")
				.doesNotContain("<NHG");
	}

	@Test
	void writesADateOnlyDeterminationAsADate() {
		SurveillanceRequest request = new SurveillanceRequest(Fixtures.XIS, "F",
				LocalDate.of(2007, 9, 7), List.of(), List.of(), List.of(),
				Fixtures.surveillanceRequest().proposed(), List.of(),
				List.of(new LabResult("62238-1", "eGFR", "mL/min/{1.73_m2}",
						LocalDate.of(2026, 9, 6), null, "35")));

		assertThat(builder.medicationSurveillance(request, credentials()))
				.contains("<LOINC num=\"62238-1\" caption=\"eGFR\" date=\"2026-09-06\"");
	}

	/**
	 * The Hub replaces this licence with its own before calling the rules engine, but the element
	 * has to be there for it to replace — and a future check upstream would read it.
	 */
	@Test
	void carriesTheCredentialsFromTheAuthorizationHeader() {
		assertThat(build())
				.contains("organisationUnitId=\"practice-123\"")
				.contains("password=\"license-key\"")
				.contains("key=\"license-key\"");
	}

	/** Nothing identifying about the patient is forwarded, on this contract as on the other. */
	@Test
	void forwardsNoPatientIdentity() {
		assertThat(build()).contains("<patientId></patientId>");
	}

	/**
	 * The same hazard {@code XmlRpcRequestBuilderTest} guards on the other contract: everything
	 * caller-supplied goes out through StAX, which escapes it. The predecessor built this XML by
	 * string templating, so an ampersand in a drug name produced a malformed request and the same
	 * hole let caller-supplied text inject markup.
	 */
	@Test
	void escapesMarkupInCallerSuppliedValues() {
		SurveillanceDrug drug = new SurveillanceDrug("rx-1",
				new MedicationCodes(1090, 3816, null, "A10BA02"),
				"A10BA02",
				"PARACETAMOL & CODEINE <script>",
				"1D1T\"",
				null, null, null, null,
				LocalDate.of(2026, 9, 6), null);

		String xml = builder.medicationSurveillance(request(List.of(drug), List.of()),
				new PrescriptorCredentials("practice&1", "key\"<>"));

		// StAX escapes what has to be escaped in an attribute value and leaves ">" alone, which is
		// well-formed either way: it is the "<" that would otherwise open an element.
		assertThat(xml)
				.contains("caption=\"PARACETAMOL &amp; CODEINE &lt;script>\"")
				.contains("organisationUnitId=\"practice&amp;1\"")
				.contains("directionCoded=\"1D1T&quot;\"")
				.doesNotContain("<script>");
	}

	private String build() {
		return builder.medicationSurveillance(Fixtures.surveillanceRequest(), credentials());
	}

	private String withLab(LabResult lab) {
		return builder.medicationSurveillance(new SurveillanceRequest(Fixtures.XIS, "F",
				LocalDate.of(2007, 9, 7), List.of(), List.of(), List.of(),
				Fixtures.surveillanceRequest().proposed(), List.of(), List.of(lab)),
				credentials());
	}

	private PrescriptorCredentials credentials() {
		return Fixtures.CREDENTIALS;
	}

	private SurveillanceRequest request(List<SurveillanceDrug> proposed, List<SurveillanceDrug> current) {
		return new SurveillanceRequest(Fixtures.XIS, "F", LocalDate.of(2007, 9, 7),
				List.<CodedItem>of(), List.of(), List.of(), proposed, current, List.of());
	}

	/** The nth {@code <drug>} element, so an assertion cannot pass on a different drug's attribute. */
	private String drugElement(String xml, int index) {
		List<String> drugs = List.of(xml.split("<drug ")).subList(1, xml.split("<drug ").length);

		return drugs.get(index).split("</drug>")[0];
	}
}
