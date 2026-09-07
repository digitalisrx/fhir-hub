package nl.digitalis.fhirhub.fhir;

import java.net.URI;
import java.util.regex.Pattern;
import java.util.ArrayList;
import java.util.List;

import org.hl7.fhir.r4.model.CodeableConcept;
import org.hl7.fhir.r4.model.Coding;
import org.hl7.fhir.r4.model.Dosage;
import org.hl7.fhir.r4.model.Extension;
import org.hl7.fhir.r4.model.MedicationRequest;
import org.hl7.fhir.r4.model.Patient;
import org.hl7.fhir.r4.model.Quantity;
import org.hl7.fhir.r4.model.UrlType;
import org.springframework.stereotype.Component;

import ca.uhn.fhir.rest.server.exceptions.InvalidRequestException;
import nl.digitalis.fhirhub.model.DrugCode;
import nl.digitalis.fhirhub.model.ExistingPrescription;
import nl.digitalis.fhirhub.model.XisInfo;
import nl.digitalis.fhirhub.model.PatientContext;
import nl.digitalis.fhirhub.model.SessionRequest;
import nl.digitalis.fhirhub.model.SessionType;
import nl.digitalis.fhirhub.prescriptor.CodeSystemTokens;

/**
 * Maps the inbound {@code Parameters} of a session operation onto the internal request model.
 *
 * <p>The patient's dossier — gender, birth date, allergies, conditions, current medication, lab
 * results — is mapped by {@link ClinicalContextMapper}, which the surveillance contract shares.
 * What is here is what a session alone has: the ICPC reason for encounter, the end-session URL,
 * and the prescription CreateRx opens for editing.
 */
@Component
public class SessionParametersMapper {

	public static final String PARAM_PATIENT = "patient";
	public static final String PARAM_ALLERGY = "allergyIntolerance";
	public static final String PARAM_CONDITION = "condition";
	public static final String PARAM_MEDICATION = "medicationStatement";
	public static final String PARAM_OBSERVATION = "observation";
	public static final String PARAM_REASON = "reason";
	public static final String PARAM_END_SESSION_URL = "endSessionUrl";
	public static final String PARAM_XIS_ID = "xisId";
	public static final String PARAM_XIS_VERSION = "xisVersion";
	public static final String PARAM_PRESCRIPTION = "prescription";

	/**
	 * ICPC-1 NL: a letter and two digits, optionally followed by a dot and two more,
	 * e.g. A01 or U71.01. Enforced here so a malformed code is a 400 rather than an opaque
	 * upstream failure.
	 */
	private static final Pattern ICPC = Pattern.compile("^[A-Z][0-9]{2}(\\.[0-9]{2})?$");

	private final CodeSystemRegistry codeSystems;

	private final ClinicalContextMapper context;

	public SessionParametersMapper(CodeSystemRegistry codeSystems, ClinicalContextMapper context) {
		this.codeSystems = codeSystems;
		this.context = context;
	}

	public SessionRequest toSessionRequest(SessionInputs inputs) {
		if (inputs == null) {
			throw new InvalidRequestException("A Parameters resource is required");
		}

		SessionType type = inputs.type();
		Patient patient = inputs.patient();
		if (patient == null) {
			throw new InvalidRequestException(
					"Parameters.parameter:" + PARAM_PATIENT + " is required and must be a Patient");
		}

		String icpc = icpcCode(inputs.reason());
		if (icpc != null && !ICPC.matcher(icpc).matches()) {
			throw new InvalidRequestException(
					"Invalid ICPC code '" + icpc + "'. Must be a letter followed by two digits (e.g. A01), "
							+ "optionally with a dot and two more digits (e.g. U71.01)");
		}

		if (type == SessionType.FORMULARY && icpc == null) {
			throw new InvalidRequestException(
					"Parameters.parameter:" + PARAM_REASON
							+ " is required for a formulary session and must carry an ICPC-1 NL coding");
		}

		String endSessionUrl = endSessionUrl(inputs.endSessionUrl());
		XisInfo xis = context.xis(inputs.xisId(), inputs.xisVersion(), PARAM_XIS_ID, PARAM_XIS_VERSION);

		PatientContext patientContext = new PatientContext(
				context.gender(patient),
				context.birthDate(patient),
				context.allergies(inputs.allergyIntolerance()),
				context.conditions(inputs.condition()),
				context.medications(inputs.medicationStatement()),
				context.laboratoryData(inputs.observation()));

		return new SessionRequest(type, icpc, patientContext, endSessionUrl, xis,
				prescription(inputs.prescription(), type));
	}

	/**
	 * A prescription the host already holds, for CreateRx to open for editing.
	 *
	 * <p>Modelled as a MedicationRequest, which is the mirror image of what
	 * {@code $session-result} returns — a host can hand back a prescription it received
	 * earlier without reshaping it.
	 */
	private ExistingPrescription prescription(MedicationRequest prescription, SessionType type) {
		if (prescription == null) {
			return null;
		}

		if (type != SessionType.CREATE_RX) {
			throw new InvalidRequestException(
					"Parameters.parameter:" + PARAM_PRESCRIPTION + " is only accepted by $createrx-session");
		}

		if (!(prescription.getMedication() instanceof CodeableConcept concept)) {
			throw new InvalidRequestException(
					PARAM_PRESCRIPTION + " requires medicationCodeableConcept");
		}

		List<DrugCode> codes = new ArrayList<>();
		String atc = null;
		for (Coding coding : concept.getCoding()) {
			if (Systems.ATC.equals(coding.getSystem())) {
				atc = coding.getCode();
				continue;
			}

			String token = codeSystems.tokenFor(coding.getSystem());
			if (token != null && CodeSystemTokens.MEDICATION.contains(token)) {
				codes.add(new DrugCode(token, toInteger(coding.getCode(), token),
						coding.hasDisplay() ? coding.getDisplay() : concept.getText(),
						null, null, null));
			}
		}

		if (codes.isEmpty()) {
			throw new InvalidRequestException(PARAM_PRESCRIPTION
					+ " requires a PRK or HPK coding on medicationCodeableConcept; expected one of "
					+ codeSystems.systemsFor(CodeSystemTokens.MEDICATION));
		}

		Quantity quantity = prescription.getDispenseRequest().getQuantity();

		return new ExistingPrescription(
				List.copyOf(codes),
				atc,
				quantity.getValue(),
				quantity.hasCode() ? quantity.getCode() : quantity.getUnit(),
				context.codedDirections(prescription));
	}

	private Integer toInteger(String code, String token) {
		try {
			return Integer.valueOf(code);
		}
		catch (RuntimeException e) {
			throw new InvalidRequestException("%s code '%s' is not numeric".formatted(token, code));
		}
	}

	/**
	 * The reason for encounter, as a {@code CodeableConcept} and nothing else:
	 * {@code OperationDefinition.parameter.type} is a single code, so a parameter cannot be both
	 * declared and polymorphic.
	 */
	private String icpcCode(CodeableConcept reason) {
		return reason == null ? null : firstCodeFor(reason, CodeSystemTokens.ICPC);
	}

	private String endSessionUrl(UrlType endSessionUrl) {
		String url = endSessionUrl == null ? null : endSessionUrl.getValue();
		if (url == null) {
			throw new InvalidRequestException(
					"Parameters.parameter:" + PARAM_END_SESSION_URL + " is required");
		}

		return requireHttpUrl(url);
	}

	/** The user is redirected here, so anything but http(s) is rejected rather than forwarded. */
	private String requireHttpUrl(String url) {
		try {
			String scheme = URI.create(url).getScheme();
			if ("http".equals(scheme) || "https".equals(scheme)) {
				return url;
			}
		}
		catch (IllegalArgumentException e) {
			// fall through to the same rejection
		}

		throw new InvalidRequestException("Invalid endSessionUrl. Must be a valid http or https URL.");
	}

	private String firstCodeFor(CodeableConcept concept, String token) {
		for (Coding coding : concept.getCoding()) {
			if (token.equals(codeSystems.tokenFor(coding.getSystem()))) {
				return coding.getCode();
			}
		}

		return null;
	}

}
