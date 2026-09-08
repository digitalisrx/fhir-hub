package nl.digitalis.fhirhub.validation;

import static org.assertj.core.api.Assertions.assertThat;

import java.math.BigDecimal;
import java.util.List;

import org.hl7.fhir.r4.model.Bundle;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

import ca.uhn.fhir.validation.FhirValidator;
import ca.uhn.fhir.validation.ResultSeverityEnum;
import ca.uhn.fhir.validation.ValidationOptions;
import ca.uhn.fhir.validation.ValidationResult;
import nl.digitalis.fhirhub.Fixtures;
import nl.digitalis.fhirhub.fhir.Profiles;
import nl.digitalis.fhirhub.fhir.ResultBundleMapper;
import nl.digitalis.fhirhub.fhir.SurveillanceBundleMapper;
import nl.digitalis.fhirhub.hub.MedicationSurveillanceResponseParser;
import nl.digitalis.fhirhub.model.AdviceResult;
import nl.digitalis.fhirhub.model.Directions;
import nl.digitalis.fhirhub.model.DrugCode;
import nl.digitalis.fhirhub.model.DrugResult;
import nl.digitalis.fhirhub.model.SessionResult;

/**
 * What this service <em>emits</em> has to satisfy its own published profile.
 *
 * <p>Inbound payloads are validated at runtime; outbound ones are not, because that would put
 * the reference validator in the path of every response for a payload this service built
 * itself. This test is where that gap is closed instead.
 *
 * <p>Not hypothetical, and cheap to break: {@code Bundle.entry.fullUrl} is mandatory for
 * anything that is not a transaction or a batch, and an assertion about the contents of an entry
 * does not notice the shape of the Bundle around it.
 */
@SpringBootTest
class OutboundPayloadConformanceTest {

	@Autowired
	private FhirValidator validator;

	@Autowired
	private ResultBundleMapper mapper;

	@Autowired
	private SurveillanceBundleMapper surveillanceMapper;

	@Test
	void theResultBundleSatisfiesItsProfile() {
		Bundle bundle = mapper.toBundle(new SessionResult(
				List.of(paracetamol()),
				List.of(new AdviceResult("Neem in met voldoende water.", false),
						new AdviceResult("https://www.thuisarts.nl/koorts", true))));

		assertThat(errorsIn(bundle)).isEmpty();
	}

	/** A care provider may prescribe nothing, and an empty Bundle must still be conformant. */
	@Test
	void anEmptyResultBundleSatisfiesItsProfile() {
		assertThat(errorsIn(mapper.toBundle(new SessionResult(List.of(), List.of())))).isEmpty();
	}

	/**
	 * The surveillance response has a published profile, so this is no longer the weaker check it
	 * was: {@code fhirhub-SurveillanceBundle} states the fixed {@code collection} type, the two
	 * identifier systems, {@code code} with text and no coding, and {@code implicated} as a
	 * logical reference — and every one of those is a claim about what
	 * {@code SurveillanceBundleMapper} emits. This is what fails when the mapper and the profile
	 * drift apart, in either direction.
	 */
	@Test
	void theSurveillanceBundleSatisfiesItsProfile() {
		assertThat(errorsIn(surveillanceBundle(
				"medication-surveillance-response.xml",
				SurveillanceBundleMapper.DrugsUnderTest.MEDICATION_REQUEST),
				Profiles.SURVEILLANCE_BUNDLE)).isEmpty();
	}

	/**
	 * $check-medication-statement answers the same profile, and the one element that differs is the
	 * one a profile can get wrong: {@code implicated.type} names a {@code MedicationStatement}
	 * here where the request check names a {@code MedicationRequest}. Both have to pass
	 * {@code fhirhub-implicated-medication}.
	 */
	@Test
	void theStatementCheckBundleSatisfiesTheSameProfile() {
		assertThat(errorsIn(surveillanceBundle(
				"medication-surveillance-response.xml",
				SurveillanceBundleMapper.DrugsUnderTest.MEDICATION_STATEMENT),
				Profiles.SURVEILLANCE_BUNDLE)).isEmpty();
	}

	/**
	 * The check ran and nothing fired, which is the one outcome that must be a conformant 200. It
	 * is also the shape a cardinality mistake in the profile would break first: an {@code entry}
	 * made 1..* to look thorough would turn every all-clear into a non-conformant response.
	 *
	 * <p>Note what this test does <em>not</em> assert: that an empty Bundle is a legitimate answer
	 * at all. That is enforced in {@code MedicationSurveillanceResponseParser}, which refuses a
	 * response carrying no report rather than passing an empty one on, and pinned by
	 * {@code SurveillanceIntegrationTest.refusesToTurnAFailedCheckIntoAnAllClear}. A profile cannot
	 * tell "no findings" from "no answer".
	 */
	@Test
	void anEmptySurveillanceBundleSatisfiesItsProfile() {
		Bundle bundle = surveillanceBundle("empty-report-response.xml",
				SurveillanceBundleMapper.DrugsUnderTest.MEDICATION_REQUEST);

		assertThat(bundle.getEntry()).isEmpty();
		assertThat(errorsIn(bundle, Profiles.SURVEILLANCE_BUNDLE)).isEmpty();
	}

	private Bundle surveillanceBundle(String fixture, SurveillanceBundleMapper.DrugsUnderTest underTest) {
		return surveillanceMapper.toBundle(
				new MedicationSurveillanceResponseParser().parse(Fixtures.hubXml(fixture)), underTest);
	}

	private List<String> errorsIn(Bundle bundle) {
		return errorsIn(bundle, Profiles.RESULT_BUNDLE);
	}

	private List<String> errorsIn(Bundle bundle, String profile) {
		ValidationOptions options = new ValidationOptions();
		if (profile != null) {
			options.addProfile(profile);
		}

		ValidationResult result = validator.validateWithResult(bundle, options);

		return result.getMessages().stream()
				.filter(message -> message.getSeverity() == ResultSeverityEnum.ERROR
						|| message.getSeverity() == ResultSeverityEnum.FATAL)
				.map(message -> message.getLocationString() + ": " + message.getMessage())
				.toList();
	}

	private DrugResult paracetamol() {
		return new DrugResult(
				List.of(new DrugCode("PRK", 18996, "PARACETAMOL ZETPIL 1000MG",
						new BigDecimal("15"), "ST",
						new Directions("tabel25", "3-4D1S", "3 tot 4 maal daags 1 zetpil"))),
				7, true, "PARACETAMOL ZETPIL 1000MG", "N02BE01");
	}
}
