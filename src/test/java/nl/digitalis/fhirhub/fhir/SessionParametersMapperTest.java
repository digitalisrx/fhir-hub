package nl.digitalis.fhirhub.fhir;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.LocalDate;
import java.time.LocalTime;
import java.util.Date;
import java.util.GregorianCalendar;
import java.util.List;

import org.hl7.fhir.r4.model.AllergyIntolerance;
import org.hl7.fhir.r4.model.CodeableConcept;
import org.hl7.fhir.r4.model.Coding;
import org.hl7.fhir.r4.model.Condition;
import org.hl7.fhir.r4.model.DateTimeType;
import org.hl7.fhir.r4.model.Enumerations.AdministrativeGender;
import org.hl7.fhir.r4.model.Dosage;
import org.hl7.fhir.r4.model.MedicationRequest;
import org.hl7.fhir.r4.model.MedicationStatement;
import org.hl7.fhir.r4.model.Observation;
import org.hl7.fhir.r4.model.Parameters;
import org.hl7.fhir.r4.model.Parameters.ParametersParameterComponent;
import org.hl7.fhir.r4.model.Patient;
import org.hl7.fhir.r4.model.Quantity;
import org.hl7.fhir.r4.model.StringType;
import org.hl7.fhir.r4.model.UrlType;
import org.junit.jupiter.api.Test;

import ca.uhn.fhir.rest.server.exceptions.InvalidRequestException;
import nl.digitalis.fhirhub.model.ExistingPrescription;
import nl.digitalis.fhirhub.model.LabResult;
import nl.digitalis.fhirhub.model.SessionRequest;
import nl.digitalis.fhirhub.model.SessionType;

class SessionParametersMapperTest {

	private final SessionParametersMapper mapper = new SessionParametersMapper(new CodeSystemRegistry(),
			new ClinicalContextMapper(new CodeSystemRegistry(), new LabDeterminations()));

	@Test
	void mapsAMinimalFormularySession() {
		SessionRequest request = mapper.toSessionRequest(bind(parameters(), SessionType.FORMULARY));

		assertThat(request.icpc()).isEqualTo("A01");
		assertThat(request.endSessionUrl()).isEqualTo("https://someurl.example/done");
		assertThat(request.patient().gender()).isEqualTo("F");
		assertThat(request.patient().dateOfBirth()).isEqualTo("1980-01-01");
	}

	@Test
	void requiresAnIcpcReasonForAFormularySessionButNotForCreateRx() {
		Parameters withoutReason = parameters();
		withoutReason.getParameter().removeIf(p -> SessionParametersMapper.PARAM_REASON.equals(p.getName()));

		assertThatThrownBy(() -> mapper.toSessionRequest(bind(withoutReason, SessionType.FORMULARY)))
				.isInstanceOf(InvalidRequestException.class)
				.hasMessageContaining("reason");

		assertThat(mapper.toSessionRequest(bind(withoutReason, SessionType.CREATE_RX)).icpc()).isNull();
	}

	/** Prescriptor's PatientGender has an X, so a host that does not know can still prescribe. */
	@Test
	void mapsUnknownGenderOntoTheUpstreamX() {
		Parameters parameters = parameters();
		patientIn(parameters).setGender(AdministrativeGender.UNKNOWN);

		assertThat(mapper.toSessionRequest(bind(parameters, SessionType.FORMULARY)).patient().gender())
				.isEqualTo("X");
	}

	/**
	 * 'other' is a different assertion from 'unknown' and has no upstream value, and an absent
	 * gender is a caller omission rather than a statement about the patient. Coercing either
	 * into X would put words in the host's mouth.
	 */
	@Test
	void rejectsGendersPrescriptorCannotRepresent() {
		Parameters parameters = parameters();
		patientIn(parameters).setGender(AdministrativeGender.OTHER);

		assertThatThrownBy(() -> mapper.toSessionRequest(bind(parameters, SessionType.FORMULARY)))
				.isInstanceOf(InvalidRequestException.class)
				.hasMessageContaining("male");

		Parameters absent = parameters();
		patientIn(absent).setGender(null);

		assertThatThrownBy(() -> mapper.toSessionRequest(bind(absent, SessionType.FORMULARY)))
				.isInstanceOf(InvalidRequestException.class)
				.hasMessageContaining("absent gender");
	}

	@Test
	void routesCodingsToTheirUpstreamSubsystem() {
		Parameters parameters = parameters();
		parameters.addParameter().setName(SessionParametersMapper.PARAM_ALLERGY)
				.setResource(new AllergyIntolerance().setCode(concept(Systems.G_STANDAARD_SNK, "10499")));
		parameters.addParameter().setName(SessionParametersMapper.PARAM_CONDITION)
				.setResource(new Condition().setCode(concept(Systems.G_STANDAARD_CONTRA_INDICATIE, "228")));

		SessionRequest request = mapper.toSessionRequest(bind(parameters, SessionType.FORMULARY));

		assertThat(request.patient().allergies()).singleElement()
				.satisfies(item -> assertThat(item.codeSystem()).isEqualTo("SNK"));
		assertThat(request.patient().contraIndications()).singleElement()
				.satisfies(item -> assertThat(item.codeSystem()).isEqualTo("CICode"));
	}

	/**
	 * The rejection names the system URIs a caller may send. It used to list the upstream tokens,
	 * which are the one set of strings that must never appear in a {@code Coding.system}.
	 */
	@Test
	void rejectsACodingInASystemThatIsNotRouted() {
		Parameters parameters = parameters();
		parameters.addParameter().setName(SessionParametersMapper.PARAM_ALLERGY)
				.setResource(new AllergyIntolerance().setCode(concept("http://snomed.info/sct", "91936005")));

		assertThatThrownBy(() -> mapper.toSessionRequest(bind(parameters, SessionType.FORMULARY)))
				.isInstanceOf(InvalidRequestException.class)
				.hasMessageContaining("no coding in a system this interface routes")
				.hasMessageContaining(Systems.G_STANDAARD_SSK)
				.hasMessageContaining(Systems.G_STANDAARD_SNK)
				.hasMessageContaining(Systems.G_STANDAARD_OGGRP);
	}

	/** The upstream token is not a URI, so it is not a system a caller may send. */
	@Test
	void rejectsTheBareUpstreamTokenAsASystem() {
		Parameters parameters = parameters();
		parameters.addParameter().setName(SessionParametersMapper.PARAM_ALLERGY)
				.setResource(new AllergyIntolerance().setCode(concept("SNK", "10499")));

		assertThatThrownBy(() -> mapper.toSessionRequest(bind(parameters, SessionType.FORMULARY)))
				.isInstanceOf(InvalidRequestException.class)
				.hasMessageContaining("no coding in a system this interface routes");
	}

	/** The nierfunctie is what surveillance turns on, and the code travels through unchanged. */
	@Test
	void carriesTheLoincCodeAndTheUnitTheRulesEvaluateIn() {
		LabResult lab = labResultFor("62238-1", 32, "mL/min/{1.73_m2}");

		assertThat(lab.loinc()).isEqualTo("62238-1");
		assertThat(lab.unit()).isEqualTo("mL/min/{1.73_m2}");
		assertThat(lab.value()).isEqualTo("32");
	}

	/** Two LOINC codes, one MFB parameter: the G-Standaard lists both for kalium. */
	@Test
	void acceptsBothKaliumCodes() {
		assertThat(labResultFor("2823-3", 5.2, "mmol/L").loinc()).isEqualTo("2823-3");
		assertThat(labResultFor("6298-4", 5.2, "mmol/L").loinc()).isEqualTo("6298-4");
	}

	/**
	 * A determination no rule reads is accepted, not refused — any LOINC code is valid input — but
	 * dropped rather than forwarded: nothing downstream would read it, and forwarding it would leave
	 * the prescriber believing a value had been weighed when nothing read it.
	 */
	@Test
	void dropsADeterminationSurveillanceDoesNotRead() {
		assertThat(laboratoryDataFor("718-7", 8.1, "mmol/L")).isEmpty();
	}

	private List<LabResult> laboratoryDataFor(String loinc, double value, String ucumCode) {
		Parameters parameters = parameters();
		parameters.addParameter().setName(SessionParametersMapper.PARAM_OBSERVATION)
				.setResource(new Observation()
						.setCode(concept(Systems.LOINC, loinc))
						.setValue(quantity(value, ucumCode))
						.setEffective(new DateTimeType("2024-07-04")));

		return mapper.toSessionRequest(bind(parameters, SessionType.FORMULARY))
				.patient().laboratoryData();
	}

	/**
	 * The G-Standaard lists MDRD and cystatin C for the nierfunctie; Dutch labs report CKD-EPI. Both
	 * codes validate as LOINC, but neither feeds a rule here, so both are dropped rather than
	 * rejected.
	 */
	@Test
	void dropsTheEgfrFormulasDutchLaboratoriesDoNotReport() {
		assertThat(laboratoryDataFor("77147-7", 32, "mL/min/{1.73_m2}")).isEmpty();
		assertThat(laboratoryDataFor("50210-4", 32, "mL/min/{1.73_m2}")).isEmpty();
	}

	@Test
	void rejectsALabCodingThatIsNotLoinc() {
		Parameters parameters = parameters();
		parameters.addParameter().setName(SessionParametersMapper.PARAM_OBSERVATION)
				.setResource(new Observation()
						.setCode(concept("urn:oid:2.16.840.1.113883.2.4.4.30.45", "KREAOMK"))
						.setValue(quantity(32, "mL/min"))
						.setEffective(new DateTimeType("2024-07-04")));

		assertThatThrownBy(() -> mapper.toSessionRequest(bind(parameters, SessionType.FORMULARY)))
				.isInstanceOf(InvalidRequestException.class)
				.hasMessageContaining("requires a coding in http://loinc.org");
	}

	/**
	 * The upstream evaluates the number in the unit the rule was written in, so the wrong one is
	 * not a rounding error: mg/dL and mmol/L differ by a factor nobody downstream could spot.
	 */
	@Test
	void rejectsAValueInAUnitTheDeterminationDoesNotAccept() {
		assertThatThrownBy(() -> labResultFor("2823-3", 20, "mg/dL"))
				.isInstanceOf(InvalidRequestException.class)
				.hasMessageContaining("must be in [mmol/L]");
	}

	/** An eGFR is normalised per 1.73 m2, so it may not arrive claiming plain ml/min. */
	@Test
	void rejectsAnEgfrThatDoesNotDeclareItsNormalisedUnit() {
		assertThatThrownBy(() -> labResultFor("62238-1", 32, "mL/min"))
				.isInstanceOf(InvalidRequestException.class)
				.hasMessageContaining("mL/min/{1.73_m2}");
	}

	/**
	 * A height is centimetres and nothing else. Metres were accepted and converted exactly, which
	 * made this interface the only reader that would take them: R4 applies its own bodyheight
	 * profile to every 8302-2 and binds the unit to cm or inches, so a host validating its own
	 * payload got an error for a value this service was happy with.
	 */
	@Test
	void acceptsAHeightOnlyInCentimetres() {
		assertThat(labResultFor("8302-2", 172, "cm").value()).isEqualTo("172");
		assertThatThrownBy(() -> labResultFor("8302-2", 1.72, "m"))
				.isInstanceOf(InvalidRequestException.class)
				.hasMessageContaining("must be in [cm]");
	}

	@Test
	void requiresAQuantityRatherThanAnyValueType() {
		Parameters parameters = parameters();
		parameters.addParameter().setName(SessionParametersMapper.PARAM_OBSERVATION)
				.setResource(new Observation()
						.setCode(concept(Systems.LOINC, "62238-1"))
						.setValue(new StringType("32"))
						.setEffective(new DateTimeType("2024-07-04")));

		assertThatThrownBy(() -> mapper.toSessionRequest(bind(parameters, SessionType.FORMULARY)))
				.isInstanceOf(InvalidRequestException.class)
				.hasMessageContaining("valueQuantity is required");
	}

	/** The determination's own name stands in when the host sends no display. */
	@Test
	void fallsBackToTheDeterminationNameAsTheCaption() {
		assertThat(labResultFor("62238-1", 32, "mL/min/{1.73_m2}").caption())
				.isEqualTo("eGFR volgens CKD-EPI");
	}

	@Test
	void passesTheHostsDisplayOnAsTheCaption() {
		Parameters parameters = parameters();
		parameters.addParameter().setName(SessionParametersMapper.PARAM_OBSERVATION)
				.setResource(new Observation()
						.setCode(new CodeableConcept().addCoding(new Coding()
								.setSystem(Systems.LOINC).setCode("62238-1").setDisplay("nierfunctie")))
						.setValue(quantity(32, "mL/min/{1.73_m2}"))
						.setEffective(new DateTimeType("2024-07-04")));

		assertThat(mapper.toSessionRequest(bind(parameters, SessionType.FORMULARY))
				.patient().laboratoryData().getFirst().caption())
				.isEqualTo("nierfunctie");
	}

	/**
	 * Several results for one determination are resolved upstream by taking the most recent, and the
	 * comparison reads the date attribute alone — so the time of day the host stated has to survive
	 * the mapping. Truncated to a date, two results from one day tie, and the engine breaks a tie by
	 * the order they were sent in rather than by which is later.
	 */
	@Test
	void keepsTheTimeOfDayTheHostStated() {
		assertThat(labResultAt("2024-07-04T13:09:04").time()).isEqualTo(LocalTime.of(13, 9, 4));
		assertThat(labResultAt("2024-07-04T13:09:00").time()).isEqualTo(LocalTime.of(13, 9));
	}

	/** A date is a claim about precision; a midnight the host did not send would be another one. */
	@Test
	void statesNoTimeWhenTheHostStatedOnlyADate() {
		assertThat(labResultAt("2024-07-04").time()).isNull();
		assertThat(labResultAt("2024-07-04").date()).isEqualTo(LocalDate.of(2024, 7, 4));
	}

	/**
	 * Only one result per determination is ever weighed upstream, so only one is sent: a host that
	 * repeats a determination gets the latest of the series forwarded and the rest dropped.
	 */
	@Test
	void forwardsOnlyTheMostRecentResultPerDetermination() {
		List<LabResult> results = laboratoryDataFor(
				observation("62238-1", quantity(32, "mL/min/{1.73_m2}"), "2024-07-04T09:15:00"),
				observation("62238-1", quantity(28, "mL/min/{1.73_m2}"), "2024-07-04T16:40:00"),
				observation("62238-1", quantity(35, "mL/min/{1.73_m2}"), "2024-06-02T11:00:00"));

		assertThat(results).hasSize(1);
		assertThat(results.getFirst().value()).isEqualTo("28");
		assertThat(results.getFirst().time()).isEqualTo(LocalTime.of(16, 40));
	}

	/** A missing time of day is midnight, as it is upstream — so a timed result of the same day wins. */
	@Test
	void readsAMissingTimeOfDayAsMidnightWhenChoosingTheMostRecent() {
		List<LabResult> results = laboratoryDataFor(
				observation("62238-1", quantity(32, "mL/min/{1.73_m2}"), "2024-07-04T08:00:00"),
				observation("62238-1", quantity(28, "mL/min/{1.73_m2}"), "2024-07-04"));

		assertThat(results.getFirst().value()).isEqualTo("32");
	}

	/** The engine keeps the first of a tie, so the value forwarded is the one it would have read. */
	@Test
	void keepsTheFirstOfTwoResultsTakenAtTheSameMoment() {
		List<LabResult> results = laboratoryDataFor(
				observation("62238-1", quantity(32, "mL/min/{1.73_m2}"), "2024-07-04"),
				observation("62238-1", quantity(28, "mL/min/{1.73_m2}"), "2024-07-04"));

		assertThat(results).hasSize(1);
		assertThat(results.getFirst().value()).isEqualTo("32");
	}

	/**
	 * Selection is per LOINC code, because that is what the upstream tests on: a kalium in blood
	 * does not supersede a kalium in serum, and deciding that it did is a clinical judgement this
	 * interface was not asked to make. Survivors keep the order they were first sent in.
	 */
	@Test
	void keepsOneResultForEachDeterminationTheHostSent() {
		List<LabResult> results = laboratoryDataFor(
				observation("2823-3", quantity(4.1, "mmol/L"), "2024-07-04T09:15:00"),
				observation("6298-4", quantity(3.8, "mmol/L"), "2024-07-04T09:15:00"),
				observation("2823-3", quantity(4.4, "mmol/L"), "2024-07-05T09:15:00"));

		assertThat(results).extracting(LabResult::loinc).containsExactly("2823-3", "6298-4");
		assertThat(results.getFirst().value()).isEqualTo("4.4");
	}

	private List<LabResult> laboratoryDataFor(Observation... observations) {
		Parameters parameters = parameters();
		for (Observation observation : observations) {
			parameters.addParameter().setName(SessionParametersMapper.PARAM_OBSERVATION)
					.setResource(observation);
		}

		return mapper.toSessionRequest(bind(parameters, SessionType.FORMULARY))
				.patient().laboratoryData();
	}

	private LabResult labResultAt(String effectiveDateTime) {
		Parameters parameters = parameters();
		parameters.addParameter().setName(SessionParametersMapper.PARAM_OBSERVATION)
				.setResource(observation("62238-1", quantity(32, "mL/min/{1.73_m2}"), effectiveDateTime));

		return mapper.toSessionRequest(bind(parameters, SessionType.FORMULARY))
				.patient().laboratoryData().getFirst();
	}

	private Observation observation(String loinc, Quantity value, String effectiveDateTime) {
		return new Observation()
				.setCode(concept(Systems.LOINC, loinc))
				.setValue(value)
				.setEffective(new DateTimeType(effectiveDateTime));
	}

	private LabResult labResultFor(String loinc, double value, String ucumCode) {
		Parameters parameters = parameters();
		parameters.addParameter().setName(SessionParametersMapper.PARAM_OBSERVATION)
				.setResource(new Observation()
						.setCode(concept(Systems.LOINC, loinc))
						.setValue(quantity(value, ucumCode))
						.setEffective(new DateTimeType("2024-07-04")));

		return mapper.toSessionRequest(bind(parameters, SessionType.FORMULARY))
				.patient().laboratoryData().getFirst();
	}

	private Quantity quantity(double value, String ucumCode) {
		return new Quantity().setValue(value).setSystem(Systems.UCUM).setCode(ucumCode).setUnit(ucumCode);
	}

	/** ICPC-1 NL shape, enforced since v2 so a malformed code is a 400 not an upstream failure. */
	@Test
	void rejectsAnIcpcCodeThatIsNotWellFormed() {
		Parameters parameters = parameters();
		parameters.getParameter().removeIf(p -> SessionParametersMapper.PARAM_REASON.equals(p.getName()));
		parameters.addParameter().setName(SessionParametersMapper.PARAM_REASON)
				.setValue(concept(Systems.ICPC_1_NL, "AA1"));

		assertThatThrownBy(() -> mapper.toSessionRequest(bind(parameters, SessionType.FORMULARY)))
				.isInstanceOf(InvalidRequestException.class)
				.hasMessageContaining("Invalid ICPC code");
	}

	@Test
	void acceptsASubdividedIcpcCode() {
		Parameters parameters = parameters();
		parameters.getParameter().removeIf(p -> SessionParametersMapper.PARAM_REASON.equals(p.getName()));
		parameters.addParameter().setName(SessionParametersMapper.PARAM_REASON)
				.setValue(concept(Systems.ICPC_1_NL, "U71.01"));

		assertThat(mapper.toSessionRequest(bind(parameters, SessionType.FORMULARY)).icpc()).isEqualTo("U71.01");
	}

	/** The user is redirected there, so a non-http scheme must not reach Prescriptor. */
	@Test
	void rejectsAnEndSessionUrlThatIsNotHttp() {
		Parameters parameters = parameters();
		parameters.getParameter().removeIf(p -> SessionParametersMapper.PARAM_END_SESSION_URL.equals(p.getName()));
		parameters.addParameter().setName(SessionParametersMapper.PARAM_END_SESSION_URL)
				.setValue(new UrlType("javascript:alert(1)"));

		assertThatThrownBy(() -> mapper.toSessionRequest(bind(parameters, SessionType.FORMULARY)))
				.isInstanceOf(InvalidRequestException.class)
				.hasMessageContaining("http or https");
	}

	@Test
	void requiresTheCallingSystemToIdentifyItself() {
		Parameters parameters = parameters();
		parameters.getParameter().removeIf(p -> SessionParametersMapper.PARAM_XIS_ID.equals(p.getName()));

		assertThatThrownBy(() -> mapper.toSessionRequest(bind(parameters, SessionType.FORMULARY)))
				.isInstanceOf(InvalidRequestException.class)
				.hasMessageContaining("xisId");
	}

	@Test
	void readsTheCallingSystemIdAndVersion() {
		SessionRequest request = mapper.toSessionRequest(bind(parameters(), SessionType.FORMULARY));

		assertThat(request.xis().id()).isEqualTo("xis-001");
		assertThat(request.xis().version()).isEqualTo("1.0");
	}

	/** An existing prescription is modelled as the MedicationRequest $session-result returns. */
	@Test
	void readsAnExistingPrescriptionForCreateRx() {
		Parameters parameters = parameters();
		parameters.addParameter().setName(SessionParametersMapper.PARAM_PRESCRIPTION)
				.setResource(existingPrescription());

		ExistingPrescription prescription =
				mapper.toSessionRequest(bind(parameters, SessionType.CREATE_RX)).prescription();

		assertThat(prescription.codes()).singleElement().satisfies(code -> {
			assertThat(code.type()).isEqualTo("PRK");
			assertThat(code.value()).isEqualTo(18996);
			assertThat(code.description()).isEqualTo("PARACETAMOL ZETPIL 1000MG");
		});
		assertThat(prescription.atc()).isEqualTo("N02BE01");
		assertThat(prescription.quantity()).isEqualByComparingTo("15");
		assertThat(prescription.unit()).isEqualTo("ST");
		assertThat(prescription.directions()).isEqualTo("3-4D1S; gedurende max. 1 maand");
	}

	/** The URL the extension is emitted at is on the wire, so it is pinned rather than assumed. */
	@Test
	void emitsTheCodedDirectionsExtensionAtItsCanonicalUrl() {
		assertThat(new T25DosageMapper()
				.toDosage(new nl.digitalis.fhirhub.model.Directions("tabel25", "3-4D1S", "drie maal daags"))
				.getExtension())
				.singleElement()
				.satisfies(extension ->
						assertThat(extension.getUrl()).isEqualTo(DigitalisExtensions.CODED_DIRECTIONS));
	}

	@Test
	void refusesAnExistingPrescriptionOnAFormularySession() {
		Parameters parameters = parameters();
		parameters.addParameter().setName(SessionParametersMapper.PARAM_PRESCRIPTION)
				.setResource(existingPrescription());

		assertThatThrownBy(() -> mapper.toSessionRequest(bind(parameters, SessionType.FORMULARY)))
				.isInstanceOf(InvalidRequestException.class)
				.hasMessageContaining("$createrx-session");
	}

	private MedicationRequest existingPrescription() {
		MedicationRequest prescription = new MedicationRequest();
		prescription.setMedication(new CodeableConcept()
				.addCoding(new Coding().setSystem(Systems.PRK).setCode("18996")
						.setDisplay("PARACETAMOL ZETPIL 1000MG"))
				.addCoding(new Coding().setSystem(Systems.ATC).setCode("N02BE01")));
		prescription.getDispenseRequest().setQuantity(new Quantity()
				.setValue(15).setCode("ST").setSystem(Systems.G_STANDAARD_BASISEENHEID));
		Dosage dosage = new Dosage();
		dosage.addExtension(DigitalisExtensions.CODED_DIRECTIONS,
				new StringType("3-4D1S; gedurende max. 1 maand"));
		prescription.addDosageInstruction(dosage);

		return prescription;
	}

	@Test
	void requiresAnEndSessionUrl() {
		Parameters parameters = parameters();
		parameters.getParameter().removeIf(p -> SessionParametersMapper.PARAM_END_SESSION_URL.equals(p.getName()));

		assertThatThrownBy(() -> mapper.toSessionRequest(bind(parameters, SessionType.FORMULARY)))
				.isInstanceOf(InvalidRequestException.class)
				.hasMessageContaining("endSessionUrl");
	}

	private Parameters parameters() {
		Patient patient = new Patient();
		patient.setGender(AdministrativeGender.FEMALE);
		patient.setBirthDate(date(1980, 1, 1));

		Parameters parameters = new Parameters();
		parameters.addParameter().setName(SessionParametersMapper.PARAM_PATIENT).setResource(patient);
		parameters.addParameter().setName(SessionParametersMapper.PARAM_REASON)
				.setValue(concept(Systems.ICPC_1_NL, "A01"));
		parameters.addParameter().setName(SessionParametersMapper.PARAM_END_SESSION_URL)
				.setValue(new UrlType("https://someurl.example/done"));

		parameters.addParameter().setName(SessionParametersMapper.PARAM_XIS_ID)
				.setValue(new StringType("xis-001"));
		parameters.addParameter().setName(SessionParametersMapper.PARAM_XIS_VERSION)
				.setValue(new StringType("1.0"));

		return parameters;
	}

	private Patient patientIn(Parameters parameters) {
		return (Patient) parameters.getParameter().stream()
				.filter(p -> SessionParametersMapper.PARAM_PATIENT.equals(p.getName()))
				.findFirst()
				.orElseThrow()
				.getResource();
	}

	private CodeableConcept concept(String system, String code) {
		return new CodeableConcept().addCoding(new Coding().setSystem(system).setCode(code));
	}

	/**
	 * Stands in for HAPI's operation-parameter binder, the way H2 stands in for the medcode
	 * view: these tests are about the mapping and validation rules, not about the binding.
	 * Real binding is exercised over HTTP in {@code FhirHubIntegrationTest}.
	 *
	 * <p>It mirrors what HAPI actually does: match on {@code name}, then read {@code resource} or
	 * {@code value[x]}, and reject a value of the wrong type rather than coercing it.
	 */
	private SessionInputs bind(Parameters parameters, SessionType type) {
		return new SessionInputs(type,
				first(parameters, SessionParametersMapper.PARAM_PATIENT, Patient.class),
				value(parameters, SessionParametersMapper.PARAM_REASON, CodeableConcept.class),
				value(parameters, SessionParametersMapper.PARAM_END_SESSION_URL, UrlType.class),
				value(parameters, SessionParametersMapper.PARAM_XIS_ID, StringType.class),
				value(parameters, SessionParametersMapper.PARAM_XIS_VERSION, StringType.class),
				all(parameters, SessionParametersMapper.PARAM_ALLERGY, AllergyIntolerance.class),
				all(parameters, SessionParametersMapper.PARAM_CONDITION, Condition.class),
				all(parameters, SessionParametersMapper.PARAM_MEDICATION, MedicationStatement.class),
				all(parameters, SessionParametersMapper.PARAM_OBSERVATION, Observation.class),
				first(parameters, SessionParametersMapper.PARAM_PRESCRIPTION, MedicationRequest.class));
	}

	private <T> List<T> all(Parameters parameters, String name, Class<T> type) {
		return parameters.getParameter().stream()
				.filter(p -> name.equals(p.getName()))
				.map(ParametersParameterComponent::getResource)
				.filter(type::isInstance)
				.map(type::cast)
				.toList();
	}

	private <T> T first(Parameters parameters, String name, Class<T> type) {
		return all(parameters, name, type).stream().findFirst().orElse(null);
	}

	@SuppressWarnings("unchecked")
	private <T> T value(Parameters parameters, String name, Class<? super T> type) {
		return (T) parameters.getParameter().stream()
				.filter(p -> name.equals(p.getName()))
				.map(ParametersParameterComponent::getValue)
				.filter(type::isInstance)
				.findFirst()
				.orElse(null);
	}

	private Date date(int year, int month, int day) {
		return new GregorianCalendar(year, month - 1, day).getTime();
	}
}
