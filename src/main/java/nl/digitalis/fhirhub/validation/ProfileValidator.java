package nl.digitalis.fhirhub.validation;

import java.util.List;

import org.hl7.fhir.instance.model.api.IBaseOperationOutcome;
import org.hl7.fhir.instance.model.api.IBaseResource;
import org.hl7.fhir.r4.model.Parameters;
import org.hl7.fhir.r4.model.Parameters.ParametersParameterComponent;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import ca.uhn.fhir.context.FhirContext;
import ca.uhn.fhir.rest.server.exceptions.InvalidRequestException;
import ca.uhn.fhir.validation.FhirValidator;
import ca.uhn.fhir.validation.ResultSeverityEnum;
import ca.uhn.fhir.validation.SingleValidationMessage;
import ca.uhn.fhir.validation.ValidationOptions;
import ca.uhn.fhir.validation.ValidationResult;

/**
 * Validates a payload against one of the profiles in {@code ig/} and turns a failure into a 400.
 *
 * <p>This runs <em>before</em> the interface's own checks — see
 * {@code SessionOperationProvider.openSession} — so an integrator gets every problem in one
 * OperationOutcome rather than one per round trip, and nothing reaches the G-Standaard lookup or
 * the call upstream until the body conforms.
 *
 * <p>The order costs something, and it is a deliberate trade rather than an oversight. For the
 * handful of rules both layers check, the validator's generic message is the one the caller sees:
 * {@code SessionParametersMapper} would have said "Patient.gender must be 'male', 'female' or
 * 'unknown'; Prescriptor cannot interpret 'other'", naming a consequence the validator cannot
 * know, and instead the caller gets a binding error. The mapper's messages still surface for what
 * a profile cannot express — an unresolvable G-Standaard code, a non-numeric product code. Swap
 * the two calls in the provider to prefer the specific message over the complete list.
 *
 * <p>Only {@code ERROR} and {@code FATAL} are rejected. Warnings are expected in normal
 * operation — the G-Standaard code systems cannot be expanded, so every coding in one produces
 * a "could not be validated" note — and treating those as failures would reject every real
 * request.
 */
@Component
public class ProfileValidator {

	private static final Logger log = LoggerFactory.getLogger(ProfileValidator.class);

	private final FhirValidator validator;
	private final FhirContext fhirContext;
	private final boolean enabled;

	public ProfileValidator(FhirValidator validator, FhirContext fhirContext,
			@Value("${fhirhub.validation.enabled:true}") boolean enabled) {
		this.validator = validator;
		this.fhirContext = fhirContext;
		this.enabled = enabled;

		if (!enabled) {
			log.warn("Profile validation is DISABLED. Payloads are accepted without being checked "
					+ "against the profiles; only this interface's own rules apply.");
		}
	}

	/**
	 * @throws InvalidRequestException rendered by HAPI as an OperationOutcome carrying one issue
	 *                                 per validation error, each with its location
	 */
	public void validate(IBaseResource resource, String profileUrl) {
		refuseNamelessParameters(resource);

		if (!enabled) {
			return;
		}

		ValidationResult result = validator.validateWithResult(resource,
				new ValidationOptions().addProfile(profileUrl));

		List<SingleValidationMessage> errors = result.getMessages().stream()
				.filter(message -> message.getSeverity() == ResultSeverityEnum.ERROR
						|| message.getSeverity() == ResultSeverityEnum.FATAL)
				.toList();

		if (errors.isEmpty()) {
			return;
		}

		log.info("Rejected a payload failing {}: {} error(s), first at {}",
				profileUrl, errors.size(), errors.getFirst().getLocationString());

		// The OperationOutcome is built from the errors alone, so the caller is not handed a
		// page of warnings about code systems they cannot do anything about.
		IBaseOperationOutcome outcome =
				new ValidationResult(fhirContext, errors).toOperationOutcome();

		throw new InvalidRequestException(
				"Payload does not conform to " + profileUrl + ": " + errors.getFirst().getMessage(),
				outcome);
	}

	/**
	 * Rejects a {@code Parameters.parameter} with no name, which the reference validator cannot
	 * report because it crashes on one.
	 *
	 * <p>{@code ParametersValidator.validateParameter} reads the name and looks it up in a
	 * {@code Map.ofEntries} of the standard parameter names; that map throws on a null key rather
	 * than missing, so org.hl7.fhir.validation 6.9.12 dies with
	 * {@code NullPointerException: Cannot invoke "Object.hashCode()" because "pk" is null} before
	 * it can report the {@code name} that base R4 requires. HAPI renders that as HAPI-0389, so a
	 * malformed request arrives as a 500 saying this service failed.
	 *
	 * <p>Deliberately before the {@code enabled} check: every mapper looks parameters up by name,
	 * so with validation off a nameless parameter is not an error but an omission — a session
	 * opened on a payload thinner than the one that was sent. Remove this when the upstream map
	 * lookup is null-safe, not before.
	 */
	private void refuseNamelessParameters(IBaseResource resource) {
		if (!(resource instanceof Parameters parameters)) {
			return;
		}

		List<ParametersParameterComponent> all = parameters.getParameter();
		for (int i = 0; i < all.size(); i++) {
			if (all.get(i).getName() == null || all.get(i).getName().isBlank()) {
				throw new InvalidRequestException("Parameters.parameter[" + i
						+ "] has no name, so nothing here could tell what it is; every parameter"
						+ " must carry the name its profile defines");
			}
		}
	}
}
