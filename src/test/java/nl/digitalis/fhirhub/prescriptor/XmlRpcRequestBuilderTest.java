package nl.digitalis.fhirhub.prescriptor;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.LocalDate;
import java.time.LocalTime;
import java.util.List;

import org.junit.jupiter.api.Test;

import nl.digitalis.fhirhub.Fixtures;
import nl.digitalis.fhirhub.model.CodedItem;
import nl.digitalis.fhirhub.model.LabResult;
import java.math.BigDecimal;

import nl.digitalis.fhirhub.model.DrugCode;
import nl.digitalis.fhirhub.model.ExistingPrescription;
import nl.digitalis.fhirhub.model.MedicationCodes;
import nl.digitalis.fhirhub.model.PatientContext;
import nl.digitalis.fhirhub.model.SessionRequest;
import nl.digitalis.fhirhub.model.SessionType;

class XmlRpcRequestBuilderTest {

	private final XmlRpcRequestBuilder builder = new XmlRpcRequestBuilder();

	@Test
	void buildsAnOpenSessionCall() {
		String xml = builder.openSession(Fixtures.formularySession(), Fixtures.CREDENTIALS, List.of());

		assertThat(xml).contains("<methodName>openSession</methodName>");
		assertThat(xml).contains("<name>SearchKey</name><value><string>A01</string></value>");
		assertThat(xml).contains("<dateTime.iso8601>19800101T12:00:00</dateTime.iso8601>");
		assertThat(xml).contains("<name>PracticeID</name><value><string>practice-123</string></value>");
		assertThat(xml).contains("<name>LicenseKey</name><value><string>license-key</string></value>");
	}

	@Test
	void createRxCallOmitsTheSearchKey() {
		SessionRequest request = new SessionRequest(
				SessionType.CREATE_RX, null, Fixtures.patient(), "https://someurl.example/done",
				Fixtures.XIS, null);

		String xml = builder.openSession(request, Fixtures.CREDENTIALS, List.of());

		assertThat(xml).contains("<methodName>createPrescription</methodName>");
		assertThat(xml).doesNotContain("SearchKey");
	}

	@Test
	void routesAllergiesAndContraIndicationsByCodeSystem() {
		String xml = builder.openSession(Fixtures.formularySession(), Fixtures.CREDENTIALS, List.of());

		assertThat(between(xml, "<name>Allergies</name>", "</member>")).contains("77").doesNotContain("10499");
		assertThat(between(xml, "<name>AlStam</name>", "</member>")).contains("10499").doesNotContain("77");
		// AlStof is the SSK member. json-interface always sent it empty, dropping SSK codes
		// before they reached allergy checking; prescriptor-api reads it as SSK.
		assertThat(between(xml, "<name>AlStof</name>", "</member>")).contains("3204").doesNotContain("10499");
		assertThat(between(xml, "<name>Contraindications_ICPC</name>", "</member>")).contains("A01");
		assertThat(between(xml, "<name>Contraindications_Z_Index</name>", "</member>")).contains("228");
	}

	/**
	 * The predecessor interpolated these values into an XML template unescaped, so a single
	 * ampersand produced a malformed request and caller-supplied text could inject markup.
	 */
	@Test
	void escapesMarkupInCallerSuppliedValues() {
		PatientContext patient = new PatientContext(
				"F",
				LocalDate.of(1980, 1, 1),
				List.of(new CodedItem("SNK", "a&b")),
				List.of(),
				List.of(),
				List.of(new LabResult("2823-3", null, "mmol/L", LocalDate.of(2024, 7, 4), null,
						"<10 & \"low\"")));

		SessionRequest request = new SessionRequest(
				SessionType.FORMULARY, "A01", patient, "https://x.example/?a=1&b=2", Fixtures.XIS, null);

		String xml = builder.openSession(request, Fixtures.CREDENTIALS, List.of());

		assertThat(xml).contains("a&amp;b");
		assertThat(xml).contains("https://x.example/?a=1&amp;b=2");
		// The lab value lives in an attribute of the embedded document, inside CDATA.
		assertThat(xml).contains("&lt;10 &amp; &quot;low&quot;");
		assertThat(xml).doesNotContain("value=\"<10");
	}

	@Test
	void embedsTheDigitalisRxDocumentInCdata() {
		String xml = builder.openSession(Fixtures.formularySession(), Fixtures.CREDENTIALS, List.of());

		assertThat(xml).contains("<name>PresPlus</name>");
		assertThat(xml).contains("<![CDATA[");
		assertThat(xml).contains("<DigitalisRx>");
		assertThat(xml).contains("<GStandaard SNK=\"10499\"");
		assertThat(xml).contains("<LOINC num=\"62238-1\" caption=\"eGFR volgens CKD-EPI\" date=\"2024-07-04\"");
	}

	/**
	 * The rules engine resolves several results for one determination by taking the most recent, and
	 * it compares this attribute alone. Date-only values all sit at midnight, so a host that sends
	 * two eGFRs from one day would have them ordered by the sequence they were listed in.
	 *
	 * <p>Seconds are always written: {@code StringToDate} treats a ten-character string as a date and
	 * anything else as {@code yyyy-mm-ddThh:mm:ss}, so a value that drops zero seconds matches
	 * neither form.
	 */
	@Test
	void writesTheTimeOfDayWhenTheDeterminationCarriesOne() {
		String xml = builder.openSession(sessionWithLabTime(LocalTime.of(13, 9, 4)),
				Fixtures.CREDENTIALS, List.of());

		assertThat(xml).contains("date=\"2024-07-04T13:09:04\"");

		xml = builder.openSession(sessionWithLabTime(LocalTime.of(13, 9)),
				Fixtures.CREDENTIALS, List.of());

		assertThat(xml).contains("date=\"2024-07-04T13:09:00\"");
	}

	/** A host that stated only a date gets the date-only form, which is a different claim. */
	@Test
	void writesADateAloneWhenTheDeterminationCarriesNoTime() {
		String xml = builder.openSession(sessionWithLabTime(null), Fixtures.CREDENTIALS, List.of());

		assertThat(xml).contains("date=\"2024-07-04\"");
	}

	private static SessionRequest sessionWithLabTime(LocalTime time) {
		PatientContext patient = new PatientContext(
				"F",
				LocalDate.of(1980, 1, 1),
				List.of(),
				List.of(),
				List.of(),
				List.of(new LabResult("62238-1", "eGFR volgens CKD-EPI", "mL/min/{1.73_m2}",
						LocalDate.of(2024, 7, 4), time, "65")));

		return new SessionRequest(
				SessionType.FORMULARY, "A01", patient, "https://x.example/end", Fixtures.XIS, null);
	}

	/**
	 * The medication block feeds Prescriptor's medication surveillance. PRK and GPK are always
	 * present; HPK only when the host identified the drug at that level.
	 */
	@Test
	void writesResolvedCurrentMedication() {
		String xml = builder.openSession(Fixtures.formularySession(), Fixtures.CREDENTIALS, List.of(
				new MedicationCodes(18996, 1234, null, "N02BE01"),
				new MedicationCodes(2106, 5678, 999, "N02AA05")));

		assertThat(xml).contains("<drug pending=\"false\"><GStandaard PRK=\"18996\" GPK=\"1234\"");
		assertThat(xml).contains("<drug pending=\"false\"><GStandaard PRK=\"2106\" GPK=\"5678\" HPK=\"999\"");
	}

	@Test
	void writesAnEmptyMedicationBlockWhenThePatientUsesNothing() {
		String xml = builder.openSession(Fixtures.formularySession(), Fixtures.CREDENTIALS, List.of());

		assertThat(xml).contains("<medication></medication>");
		assertThat(xml).doesNotContain("<drug");
	}

	/** Populated since v2; the JSON interface sent this element empty until then. */
	@Test
	void writesTheOrganisationIntoXisInfo() {
		String xml = builder.openSession(Fixtures.formularySession(), Fixtures.CREDENTIALS, List.of());

		assertThat(xml).contains(
				"<licenseKey password=\"license-key\" organisationUnitId=\"practice-123\" key=\"license-key\"");
	}

	/**
	 * MedicationType tells the upstream which attribute to read off every {@code <drug>}, so it
	 * is PRK for any list with anything in it — the one attribute every resolved drug carries.
	 */
	@Test
	void declaresTheWholeCurrentMedicationListAtPrkLevel() {
		String xml = builder.openSession(Fixtures.formularySession(), Fixtures.CREDENTIALS, List.of());

		assertThat(between(xml, "<name>MedicationType</name>", "</member>")).contains("<int>9</int>");
	}

	/**
	 * The case this is really about: a host that identifies one drug by HPK and the next by PRK.
	 * Announcing HPK would make the upstream read an absent attribute on the second drug and drop
	 * it from medication surveillance without an error, so the level follows what fhir-hub always
	 * sends rather than what the host happened to send first.
	 */
	@Test
	void declaresPrkForAMixedHpkAndPrkList() {
		PatientContext patient = new PatientContext(
				"F", LocalDate.of(1980, 1, 1), List.of(), List.of(),
				List.of(new CodedItem("HPK", "2106"), new CodedItem("PRK", "18996")), List.of());

		String xml = builder.openSession(
				new SessionRequest(SessionType.FORMULARY, "A01", patient, "https://x.example/d",
						Fixtures.XIS, null),
				Fixtures.CREDENTIALS,
				List.of(new MedicationCodes(3689, 111111, 2106, "N02AA05"), new MedicationCodes(18996, 222222, null, "N02BE01")));

		assertThat(between(xml, "<name>MedicationType</name>", "</member>")).contains("<int>9</int>");

		// Both drugs carry the attribute that value points at.
		assertThat(xml)
				.contains("<GStandaard PRK=\"3689\" GPK=\"111111\" HPK=\"2106\"")
				.contains("<GStandaard PRK=\"18996\" GPK=\"222222\"");
	}

	/** An HPK-only list is read at PRK level too: every entry was resolved to a PRK. */
	@Test
	void declaresPrkForAnHpkOnlyList() {
		PatientContext patient = new PatientContext(
				"F", LocalDate.of(1980, 1, 1), List.of(), List.of(),
				List.of(new CodedItem("HPK", "2106")), List.of());

		String xml = builder.openSession(
				new SessionRequest(SessionType.FORMULARY, "A01", patient, "https://x.example/d",
						Fixtures.XIS, null),
				Fixtures.CREDENTIALS, List.of(new MedicationCodes(3689, 111111, 2106, "N02AA05")));

		assertThat(between(xml, "<name>MedicationType</name>", "</member>")).contains("<int>9</int>");
	}

	@Test
	void declaresMedicationTypeZeroWhenThePatientUsesNothing() {
		PatientContext patient = new PatientContext(
				"F", LocalDate.of(1980, 1, 1), List.of(), List.of(), List.of(), List.of());

		String xml = builder.openSession(
				new SessionRequest(SessionType.FORMULARY, "A01", patient, "https://x.example/d",
						Fixtures.XIS, null),
				Fixtures.CREDENTIALS, List.of());

		assertThat(between(xml, "<name>MedicationType</name>", "</member>")).contains("<int>0</int>");
	}

	/** An existing prescription handed to CreateRx for editing; new in v2. */
	@Test
	void writesAnExistingPrescription() {
		ExistingPrescription prescription = new ExistingPrescription(
				List.of(new DrugCode("PRK", 18996, "PARACETAMOL ZETPIL 1000MG", null, null, null)),
				"N02BE01",
				new BigDecimal("15"),
				"ST",
				"3-4D1S; gedurende max. 1 maand");

		String xml = builder.openSession(
				new SessionRequest(SessionType.CREATE_RX, null, Fixtures.patient(), "https://x.example/d",
						Fixtures.XIS, prescription),
				Fixtures.CREDENTIALS, List.of());

		String member = between(xml, "<name>Prescription</name>", "<name>Authorization</name>");
		assertThat(member).contains("<name>DrugCodePRK</name><value><string>18996</string>");
		assertThat(member).contains("<name>CodedDirection</name><value><string>3-4D1S; gedurende max. 1 maand</string>");
		assertThat(member).contains("<name>SupplyUnit</name><value><string>ST</string>");
		assertThat(member).contains("<name>ATC</name><value><string>N02BE01</string>");
		assertThat(member).contains("<name>NumbersSupplied</name><value><double>15</double>");
		assertThat(member).contains("<name>PrescriptionType</name><value><int>9</int>");
	}

	@Test
	void omitsThePrescriptionMemberWhenThereIsNone() {
		String xml = builder.openSession(Fixtures.formularySession(), Fixtures.CREDENTIALS, List.of());

		assertThat(xml).doesNotContain("<name>Prescription</name>");
	}

	@Test
	void buildsARequestResultCall() {
		String xml = builder.requestResult("sess-abc-123");

		assertThat(xml).contains("<methodName>requestResult</methodName>");
		assertThat(xml).contains("<name>PrescriptorSessionKey</name><value><string>sess-abc-123</string></value>");
	}

	private String between(String haystack, String start, String end) {
		int from = haystack.indexOf(start);
		assertThat(from).as("expected to find %s", start).isNotNegative();

		return haystack.substring(from, haystack.indexOf(end, from));
	}
}
