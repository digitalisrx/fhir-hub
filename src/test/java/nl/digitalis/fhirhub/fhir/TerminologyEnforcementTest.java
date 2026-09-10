package nl.digitalis.fhirhub.fhir;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;

import org.hl7.fhir.r4.model.AllergyIntolerance;
import org.hl7.fhir.r4.model.CodeType;
import org.hl7.fhir.r4.model.CodeableConcept;
import org.hl7.fhir.r4.model.Coding;
import org.hl7.fhir.r4.model.DateType;
import org.hl7.fhir.r4.model.Enumerations.AdministrativeGender;
import org.hl7.fhir.r4.model.Parameters;
import org.hl7.fhir.r4.model.Patient;
import org.hl7.fhir.r4.model.Reference;
import org.hl7.fhir.r4.model.StringType;
import org.hl7.fhir.r4.model.UrlType;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

import ca.uhn.fhir.validation.FhirValidator;
import ca.uhn.fhir.validation.ResultSeverityEnum;
import ca.uhn.fhir.validation.ValidationOptions;
import nl.digitalis.fhirhub.prescriptor.CodeSystemTokens;

/**
 * Which {@code Coding.system} forms a session can actually be opened with.
 *
 * <p>The G-Standaard tables are licensed and are not distributed with the profiles, so a
 * {@code required} binding onto one can never be satisfied by expansion. It is satisfied instead
 * by a property of the validator that is easy to break by accident: a code system nothing defines
 * falls to {@code UnknownCodeSystemWarningValidationSupport} and produces a warning, while a
 * system the IG <em>does</em> define — which for a licensed table would have to be
 * {@code content: not-present} — makes the value set unexpandable and turns every coding in it
 * into an error.
 *
 * <p>So defining a G-Standaard code system in {@code ig/} would narrow what this interface
 * accepts rather than document it. That is the reverse of the intuition, and this test is what
 * fails if someone acts on the intuition.
 *
 * <p>The second half is the boundary: a system no profile binds is rejected, and so is the bare
 * upstream token, which is not a URI and is refused by both layers rather than quietly routed.
 */
@SpringBootTest
class TerminologyEnforcementTest {

	@Autowired
	private FhirValidator validator;

	@Autowired
	private CodeSystemRegistry codeSystems;

	@Test
	void aCodingInABoundGStandaardSystemIsAcceptedWithoutBeingExpandable() {
		assertThat(errorsFor(Systems.G_STANDAARD_SNK, "10499")).isEmpty();
	}

	@Test
	void aCodingInASystemNoProfileBindsIsRejected() {
		assertThat(errorsFor("http://snomed.info/sct", "91936005"))
				.as("SNOMED is not one of the systems this interface routes")
				.isNotEmpty();
	}

	@Test
	void theBareUpstreamTokenIsNotAcceptedAsASystem() {
		assertThat(errorsFor(CodeSystemTokens.SNK, "10499"))
				.as("the token is the JSON interface's form, and is not a FHIR system")
				.isNotEmpty();
	}

	/**
	 * And the layer behind the validator agrees, so switching validation off makes this interface
	 * check less rather than accept more.
	 */
	@Test
	void theMappingLayerRoutesTheSystemAndNotTheToken() {
		assertThat(codeSystems.tokenFor(Systems.G_STANDAARD_SNK)).isEqualTo(CodeSystemTokens.SNK);
		assertThat(codeSystems.tokenFor(CodeSystemTokens.SNK)).isNull();
	}

	/**
	 * The lab side is deliberately open, unlike the G-Standaard side above: {@code LoincVS} binds
	 * the whole LOINC system rather than an enumerated list, so a LOINC code no beslisregel reads
	 * still validates. Which codes are actually meaningful to medication surveillance is documented
	 * in {@code LabDeterminationVS} and enforced by {@link ClinicalContextMapper} instead — a code
	 * outside that narrower list is accepted here and then silently forwarded nowhere.
	 */
	@Test
	void aLabDeterminationOutsideTheMeaningfulListStillValidates() {
		assertThat(errorsIn(sessionWithLabDetermination("62238-1")))
				.as("the nierfunctie, which 666 current MFB rules read")
				.isEmpty();

		assertThat(errorsIn(sessionWithLabDetermination("718-7")))
				.as("hemoglobine: a real LOINC code that no rule reads, but still a real LOINC code")
				.isEmpty();
	}

	private Parameters sessionWithLabDetermination(String loinc) {
		Parameters parameters = sessionWithAllergy(Systems.G_STANDAARD_SNK, "10499");
		org.hl7.fhir.r4.model.Observation lab = new org.hl7.fhir.r4.model.Observation();
		lab.setStatus(org.hl7.fhir.r4.model.Observation.ObservationStatus.FINAL);
		lab.getCode().addCoding(new Coding().setSystem(Systems.LOINC).setCode(loinc));
		lab.setValue(new org.hl7.fhir.r4.model.Quantity().setValue(32)
				.setSystem(Systems.UCUM).setCode("mL/min/{1.73_m2}").setUnit("mL/min/1.73m2"));
		lab.setEffective(new org.hl7.fhir.r4.model.DateTimeType("2026-08-20"));
		parameters.addParameter().setName("observation").setResource(lab);

		return parameters;
	}

	private List<String> errorsIn(Parameters body) {
		return validator.validateWithResult(body,
				new ValidationOptions().addProfile(Profiles.FORMULARY_SESSION_INPUT))
				.getMessages().stream()
				.filter(message -> message.getSeverity() == ResultSeverityEnum.ERROR
						|| message.getSeverity() == ResultSeverityEnum.FATAL)
				.map(message -> message.getLocationString() + ": " + message.getMessage())
				.toList();
	}

	private List<String> errorsFor(String system, String code) {
		return validator.validateWithResult(sessionWithAllergy(system, code),
				new ValidationOptions().addProfile(Profiles.FORMULARY_SESSION_INPUT))
				.getMessages().stream()
				.filter(message -> message.getSeverity() == ResultSeverityEnum.ERROR
						|| message.getSeverity() == ResultSeverityEnum.FATAL)
				.map(message -> message.getLocationString() + ": " + message.getMessage())
				.toList();
	}

	/** A minimal conformant session, so the only thing under test is the allergy's system. */
	private Parameters sessionWithAllergy(String system, String code) {
		Patient patient = new Patient();
		patient.setGender(AdministrativeGender.FEMALE);
		patient.setBirthDateElement(new DateType("1980-01-01"));

		AllergyIntolerance allergy = new AllergyIntolerance();
		allergy.setClinicalStatus(new CodeableConcept().addCoding(new Coding()
				.setSystem("http://terminology.hl7.org/CodeSystem/allergyintolerance-clinical")
				.setCode("active")));
		allergy.setPatient(unknownReference());
		allergy.setCode(new CodeableConcept().addCoding(new Coding().setSystem(system).setCode(code)));

		Parameters parameters = new Parameters();
		parameters.addParameter().setName("patient").setResource(patient);
		parameters.addParameter().setName("reason").setValue(new CodeableConcept().addCoding(
				new Coding().setSystem(Systems.ICPC_1_NL).setCode("A01")));
		parameters.addParameter().setName("endSessionUrl").setValue(new UrlType("https://host.example/done"));
		parameters.addParameter().setName("xisId").setValue(new StringType("xis-001"));
		parameters.addParameter().setName("xisVersion").setValue(new StringType("1.0"));
		parameters.addParameter().setName("allergyIntolerance").setResource(allergy);

		return parameters;
	}

	private Reference unknownReference() {
		Reference reference = new Reference();
		reference.addExtension("http://hl7.org/fhir/StructureDefinition/data-absent-reason",
				new CodeType("unknown"));

		return reference;
	}
}
