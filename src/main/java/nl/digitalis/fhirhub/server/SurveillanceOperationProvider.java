package nl.digitalis.fhirhub.server;

import java.time.LocalDate;
import java.util.List;

import org.hl7.fhir.r4.model.AllergyIntolerance;
import org.hl7.fhir.r4.model.Bundle;
import org.hl7.fhir.r4.model.Condition;
import org.hl7.fhir.r4.model.MedicationRequest;
import org.hl7.fhir.r4.model.MedicationStatement;
import org.hl7.fhir.r4.model.Observation;
import org.hl7.fhir.r4.model.Parameters;
import org.hl7.fhir.r4.model.Patient;
import org.hl7.fhir.r4.model.StringType;
import org.springframework.stereotype.Component;

import ca.uhn.fhir.rest.annotation.Operation;
import ca.uhn.fhir.rest.annotation.OperationParam;
import ca.uhn.fhir.rest.annotation.ResourceParam;
import nl.digitalis.fhirhub.auth.CredentialsResolver;
import nl.digitalis.fhirhub.fhir.Profiles;
import nl.digitalis.fhirhub.fhir.StatementCheckInputs;
import nl.digitalis.fhirhub.fhir.SurveillanceBundleMapper;
import nl.digitalis.fhirhub.fhir.SurveillanceBundleMapper.DrugsUnderTest;
import nl.digitalis.fhirhub.fhir.SurveillanceInputs;
import nl.digitalis.fhirhub.fhir.SurveillanceParametersMapper;
import nl.digitalis.fhirhub.hub.HubClient;
import nl.digitalis.fhirhub.model.SurveillanceReport;
import nl.digitalis.fhirhub.validation.ProfileValidator;

/**
 * Medication surveillance as a direct question: given a patient's context and one or more
 * proposed prescriptions, which signals fire?
 *
 * <p>One request, one answer. No session, no browser round trip and nothing stored: the payload is
 * validated, mapped, sent to the Digitalis Hub as a {@code DigitalisRx} document and the report
 * that comes back is returned as a {@code Bundle} of {@code DetectedIssue}. The Hub runs both
 * halves of the check — the G-Standaard's medisch-farmaceutische beslisregels through the
 * clinical-rules engine, and the classic allergy, age, duplicate-medication and dose checks
 * through the G-Standaard service — and merges them into one report.
 *
 * <h2>What may and may not be concluded from a 200</h2>
 * An empty {@code Bundle} means the check ran and no rule fired. It cannot mean anything else:
 * every way for the check not to run — an unreachable Hub, a SOAP fault, a response with no report
 * in it — is a 500 with an {@code OperationOutcome}, and {@code MedicationSurveillanceResponseParser}
 * is where that is enforced. This is the same rule that makes an unresolvable G-Standaard code a
 * 400 rather than a dropped drug, and it is the reason this operation existed as a published 501
 * for a release before it existed as an implementation.
 *
 * <p><strong>What a 200 does not cover</strong> is recorded for integrators in the Implementation
 * Guide rather than only here, because a host has to decide what to show a prescriber:
 * <ul>
 * <li><b>Dose rules that compare the prescribed daily dose against the defined daily dose cannot
 * fire</b>, because computing a PDD means decoding NHG Tabel 25 and this interface passes the
 * coded dosage through undecoded — see {@code MedicationSurveillanceRequestBuilder.drug}.
 * <li><b>Weight and height do not reach the Hub's dose check</b>, which reads them from NHG-coded
 * elements this interface does not send. Where a dose band needs a weight, the check answers with
 * a "data missing" signal rather than silently passing — so this one is visible in the response.
 * <li><b>The credentials are not adjudicated upstream</b> on this base, unlike a session. See
 * {@code HubClient}.
 * </ul>
 *
 * <p>Declared non-idempotent, so HAPI exposes it over POST only. That is not a claim about side
 * effects — the check reads and stores nothing — but about the body: the request carries a
 * patient's medication, allergies and lab results, which have no business in a URL, a proxy cache
 * or an access log.
 */
@Component
public class SurveillanceOperationProvider extends SurveillanceProvider {

	public static final String CHECK_MEDICATION_REQUEST = "$check-medication-request";

	public static final String CHECK_MEDICATION_STATEMENT = "$check-medication-statement";

	private final ProfileValidator profileValidator;

	private final SurveillanceParametersMapper parametersMapper;

	private final SurveillanceBundleMapper bundleMapper;

	private final HubClient hub;

	private final CredentialsResolver credentials;

	public SurveillanceOperationProvider(ProfileValidator profileValidator,
			SurveillanceParametersMapper parametersMapper,
			SurveillanceBundleMapper bundleMapper,
			HubClient hub,
			CredentialsResolver credentials) {
		this.profileValidator = profileValidator;
		this.parametersMapper = parametersMapper;
		this.bundleMapper = bundleMapper;
		this.hub = hub;
		this.credentials = credentials;
	}

	/**
	 * The parameters are declared individually rather than the body being taken whole, so that
	 * HAPI generates an {@code OperationDefinition} listing every name, type and cardinality —
	 * which is what an integrator can generate a request from.
	 *
	 * <p>{@code prescription} and {@code medicationStatement} reuse the resource profiles of the
	 * EVS contract, so a host that already builds a session payload has nothing new to shape.
	 */
	@Operation(name = CHECK_MEDICATION_REQUEST, idempotent = false)
	public Bundle checkMedication(
			@OperationParam(name = "patient", min = 1, max = 1) Patient patient,
			@OperationParam(name = "xisId", min = 1, max = 1) StringType xisId,
			@OperationParam(name = "xisVersion", min = 1, max = 1) StringType xisVersion,
			@OperationParam(name = "prescription", min = 1, max = OperationParam.MAX_UNLIMITED) List<MedicationRequest> prescription,
			@OperationParam(name = "medicationStatement", min = 0, max = OperationParam.MAX_UNLIMITED) List<MedicationStatement> medicationStatement,
			@OperationParam(name = "allergyIntolerance", min = 0, max = OperationParam.MAX_UNLIMITED) List<AllergyIntolerance> allergyIntolerance,
			@OperationParam(name = "condition", min = 0, max = OperationParam.MAX_UNLIMITED) List<Condition> condition,
			@OperationParam(name = "observation", min = 0, max = OperationParam.MAX_UNLIMITED) List<Observation> observation,
			@ResourceParam Parameters body) {

		profileValidator.validate(body, Profiles.SURVEILLANCE_INPUT);

		SurveillanceInputs inputs = new SurveillanceInputs(patient, xisId, xisVersion,
				prescription, medicationStatement, allergyIntolerance, condition, observation);

		// Today is passed in rather than read inside the mapper, so that one request is weighed
		// against one date however long it takes, and so the mapper can be tested without a clock.
		SurveillanceReport report = hub.checkMedication(
				parametersMapper.toSurveillanceRequest(inputs, LocalDate.now()),
				credentials.current());

		return bundleMapper.toBundle(report, DrugsUnderTest.MEDICATION_REQUEST);
	}

	/**
	 * The same check with the dossier as its subject: no prescription is proposed, and every
	 * {@code medicationStatement} is examined rather than serving as the context for something
	 * else.
	 *
	 * <p><strong>What this answers that the other operation does not</strong> is "what is wrong
	 * with what this patient is already taking" — an allergy or a contra-indication that was
	 * recorded after the medication was started, a dose that no longer fits a nierfunctie that has
	 * since dropped, a duplicate between two drugs neither of which is new. A periodic medication
	 * review asks exactly this, and it cannot be phrased as a proposal without inventing one.
	 *
	 * <p><strong>It takes no {@code prescription}</strong>, and the profile's closed slicing
	 * refuses one rather than ignoring it: a request that named a proposed drug and had it dropped
	 * would be answered for its context alone, which is a check of the wrong thing reported as a
	 * check. A host with a drug to weigh wants {@code $check-medication-request}.
	 *
	 * <p>Everything downstream is shared — the same profiles on the resources, the same
	 * {@code DigitalisRx} document, the same upstream, the same {@code Bundle} of
	 * {@code DetectedIssue}, and the same rule that an empty {@code Bundle} can only mean the check
	 * ran and nothing fired. The one thing that differs in the answer is that
	 * {@code DetectedIssue.implicated} names {@code MedicationStatement}, because that is what the
	 * host sent; see {@code SurveillanceBundleMapper.DrugsUnderTest}.
	 */
	@Operation(name = CHECK_MEDICATION_STATEMENT, idempotent = false)
	public Bundle checkMedicationStatement(
			@OperationParam(name = "patient", min = 1, max = 1) Patient patient,
			@OperationParam(name = "xisId", min = 1, max = 1) StringType xisId,
			@OperationParam(name = "xisVersion", min = 1, max = 1) StringType xisVersion,
			@OperationParam(name = "medicationStatement", min = 1, max = OperationParam.MAX_UNLIMITED) List<MedicationStatement> medicationStatement,
			@OperationParam(name = "allergyIntolerance", min = 0, max = OperationParam.MAX_UNLIMITED) List<AllergyIntolerance> allergyIntolerance,
			@OperationParam(name = "condition", min = 0, max = OperationParam.MAX_UNLIMITED) List<Condition> condition,
			@OperationParam(name = "observation", min = 0, max = OperationParam.MAX_UNLIMITED) List<Observation> observation,
			@ResourceParam Parameters body) {

		profileValidator.validate(body, Profiles.SURVEILLANCE_STATEMENT_INPUT);

		StatementCheckInputs inputs = new StatementCheckInputs(patient, xisId, xisVersion,
				medicationStatement, allergyIntolerance, condition, observation);

		SurveillanceReport report = hub.checkMedication(
				parametersMapper.toStatementCheckRequest(inputs, LocalDate.now()),
				credentials.current());

		return bundleMapper.toBundle(report, DrugsUnderTest.MEDICATION_STATEMENT);
	}
}
