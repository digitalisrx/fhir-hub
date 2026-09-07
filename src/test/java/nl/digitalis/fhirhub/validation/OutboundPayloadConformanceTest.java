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
	 * The surveillance Bundle is validated against base R4 and against no profile, because none is
	 * published for it — see {@code SurveillanceBundleMapper}. That is a weaker check than the one
	 * above and still worth having: it is what would catch a DetectedIssue missing its mandatory
	 * status, an identifier without a system, or a reference that is neither a URL nor an
	 * identifier. Every response of this contract is built by that mapper, and nothing else
	 * validates it.
	 */
	@Test
	void theSurveillanceBundleIsValidFhir() {
		Bundle bundle = surveillanceMapper.toBundle(
				new MedicationSurveillanceResponseParser().parse(
						Fixtures.hubXml("medication-surveillance-response.xml")));

		assertThat(errorsIn(bundle, null)).isEmpty();
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
