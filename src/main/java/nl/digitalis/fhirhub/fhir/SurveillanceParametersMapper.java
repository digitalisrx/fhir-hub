package nl.digitalis.fhirhub.fhir;

import java.time.LocalDate;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;

import org.hl7.fhir.r4.model.CodeableConcept;
import org.hl7.fhir.r4.model.Coding;
import org.hl7.fhir.r4.model.MedicationRequest;
import org.hl7.fhir.r4.model.MedicationStatement;
import org.hl7.fhir.r4.model.Patient;
import org.hl7.fhir.r4.model.Period;
import org.hl7.fhir.r4.model.Quantity;
import org.hl7.fhir.r4.model.Type;
import org.springframework.stereotype.Component;

import ca.uhn.fhir.rest.server.exceptions.InvalidRequestException;
import nl.digitalis.fhirhub.gstandaard.MedicationCodeResolver;
import nl.digitalis.fhirhub.model.CodedItem;
import nl.digitalis.fhirhub.model.MedicationCodes;
import nl.digitalis.fhirhub.model.SurveillanceDrug;
import nl.digitalis.fhirhub.model.SurveillanceRequest;
import nl.digitalis.fhirhub.prescriptor.CodeSystemTokens;

/**
 * Maps the inbound {@code Parameters} of {@code $check-medication-request} onto the internal request
 * model.
 *
 * <p>The patient's dossier is {@link ClinicalContextMapper}'s, shared with the session contract.
 * What is here is what only a check has: the split of the proposals from the standing medication,
 * the dosing and supply that travel with a proposal, and the dates that decide whether an entry is
 * weighed at all.
 *
 * <p><strong>The G-Standaard lookup happens here</strong>, rather than in {@code HubClient} where
 * the session flow does it, because a drug is only a {@link SurveillanceDrug} once it has its PRK,
 * GPK and ATC — there is no half-resolved shape to hand on. The rule it enforces is unchanged and
 * is the same code: an unresolvable code fails the whole request with a 400 naming it, because a
 * check run over a medication list one drug short answers "no interaction found".
 */
@Component
public class SurveillanceParametersMapper {

	public static final String PARAM_PATIENT = "patient";
	public static final String PARAM_XIS_ID = "xisId";
	public static final String PARAM_XIS_VERSION = "xisVersion";
	public static final String PARAM_PRESCRIPTION = "prescription";
	public static final String PARAM_MEDICATION = "medicationStatement";

	private final ClinicalContextMapper context;

	private final CodeSystemRegistry codeSystems;

	private final MedicationCodeResolver medicationCodes;

	public SurveillanceParametersMapper(ClinicalContextMapper context,
			CodeSystemRegistry codeSystems,
			MedicationCodeResolver medicationCodes) {
		this.context = context;
		this.codeSystems = codeSystems;
		this.medicationCodes = medicationCodes;
	}

	public SurveillanceRequest toSurveillanceRequest(SurveillanceInputs inputs, LocalDate today) {
		if (inputs == null) {
			throw new InvalidRequestException("A Parameters resource is required");
		}

		Patient patient = inputs.patient();
		if (patient == null) {
			throw new InvalidRequestException(
					"Parameters.parameter:" + PARAM_PATIENT + " is required and must be a Patient");
		}

		// The profile says this too, as a 1..* on the slice, and says it better: it names the
		// element. This is the same rule for a deployment with validation switched off — a check
		// with nothing under test reports no signals, which reads as an all-clear. Note that a
		// dossier-only request was accepted until 0.4.0; it is a 400 now.
		if (inputs.prescription().isEmpty()) {
			throw new InvalidRequestException(
					"Send at least one " + PARAM_PRESCRIPTION + " to check: this operation weighs"
							+ " proposed prescriptions against the patient's context, so a request"
							+ " carrying none has nothing under test.");
		}

		List<CodedItem> conditions = context.conditions(inputs.condition());

		return new SurveillanceRequest(
				context.xis(inputs.xisId(), inputs.xisVersion(), PARAM_XIS_ID, PARAM_XIS_VERSION),
				context.gender(patient),
				context.birthDate(patient),
				context.allergies(inputs.allergyIntolerance()),
				codedWith(conditions, CodeSystemTokens.CI_CODE),
				codedWith(conditions, CodeSystemTokens.ICPC),
				proposals(inputs.prescription(), today),
				currentMedication(inputs.medicationStatement(), today),
				context.laboratoryData(inputs.observation()));
	}

	/**
	 * The same question asked of a dossier alone: every {@code medicationStatement} is a subject of
	 * the check rather than the background to a proposal.
	 *
	 * <p>The list goes into {@link SurveillanceRequest#proposed()}, which is not a misuse of the
	 * field but the whole mechanism: {@code proposed} is what
	 * {@code MedicationSurveillanceRequestBuilder} marks {@code pending="true"} and
	 * {@code trigger="true"}, and the Hub gates every one of its G-Standaard checks on at least one
	 * drug carrying {@code pending} — {@code MbFactory.process()} returns immediately otherwise. So
	 * sending this list as the standing dossier instead would produce a well-formed 200 with an
	 * empty {@code Bundle} for a check that never ran, which is the one answer this contract must
	 * never give. {@code currentMedication} is left empty because there is no separate context
	 * here: the dossier is the subject.
	 */
	public SurveillanceRequest toStatementCheckRequest(StatementCheckInputs inputs, LocalDate today) {
		if (inputs == null) {
			throw new InvalidRequestException("A Parameters resource is required");
		}

		Patient patient = inputs.patient();
		if (patient == null) {
			throw new InvalidRequestException(
					"Parameters.parameter:" + PARAM_PATIENT + " is required and must be a Patient");
		}

		// The profile says this too, as a 1..* on the slice. Same rule as the mandatory
		// prescription on the other operation, and for the same reason.
		if (inputs.medicationStatement().isEmpty()) {
			throw new InvalidRequestException(
					"Send at least one " + PARAM_MEDICATION + " to check: this operation weighs a"
							+ " patient's current medication against itself and their context, so a"
							+ " request carrying none has nothing under test.");
		}

		List<CodedItem> conditions = context.conditions(inputs.condition());

		return new SurveillanceRequest(
				context.xis(inputs.xisId(), inputs.xisVersion(), PARAM_XIS_ID, PARAM_XIS_VERSION),
				context.gender(patient),
				context.birthDate(patient),
				context.allergies(inputs.allergyIntolerance()),
				codedWith(conditions, CodeSystemTokens.CI_CODE),
				codedWith(conditions, CodeSystemTokens.ICPC),
				currentMedication(inputs.medicationStatement(), today),
				List.of(),
				context.laboratoryData(inputs.observation()));
	}

	private List<CodedItem> codedWith(List<CodedItem> items, String token) {
		return items.stream().filter(item -> token.equals(item.codeSystem())).toList();
	}

	private List<SurveillanceDrug> proposals(List<MedicationRequest> prescriptions, LocalDate today) {
		List<SurveillanceDrug> drugs = new ArrayList<>();
		int index = 0;
		for (MedicationRequest prescription : prescriptions) {
			index++;
			CodeableConcept concept = medicationConcept(prescription.getMedication(),
					PARAM_PRESCRIPTION + ".medicationCodeableConcept");
			CodedItem code = routedCode(concept, PARAM_PRESCRIPTION + ".medicationCodeableConcept");
			MedicationCodes codes = resolve(code);
			Quantity quantity = prescription.getDispenseRequest().getQuantity();
			Period validity = prescription.getDispenseRequest().getValidityPeriod();

			drugs.add(new SurveillanceDrug(
					uid(prescription.getIdPart(), "prescription", index),
					codes,
					atc(concept, codes),
					caption(concept, code),
					context.codedDirections(prescription),
					prescription.getDosageInstructionFirstRep().getText(),
					quantity.hasValue() ? quantity.getValue() : null,
					quantity.hasCode() ? quantity.getCode() : quantity.getUnit(),
					reasonIcpc(prescription),
					// A prescription being weighed is being written now unless the host says
					// otherwise. Its dispense validity is what it stated about when it applies.
					start(validity, prescription.hasAuthoredOn() ? prescription.getAuthoredOn() : null, today),
					end(validity)));
		}

		return drugs;
	}

	private List<SurveillanceDrug> currentMedication(List<MedicationStatement> statements, LocalDate today) {
		List<SurveillanceDrug> drugs = new ArrayList<>();
		int index = 0;
		for (MedicationStatement statement : statements) {
			index++;
			CodeableConcept concept = medicationConcept(statement.getMedication(),
					PARAM_MEDICATION + ".medicationCodeableConcept");
			CodedItem code = routedCode(concept, PARAM_MEDICATION + ".medicationCodeableConcept");
			MedicationCodes codes = resolve(code);
			Period effective = statement.hasEffectivePeriod() ? statement.getEffectivePeriod() : null;

			drugs.add(new SurveillanceDrug(
					uid(statement.getIdPart(), "medicationStatement", index),
					codes,
					atc(concept, codes),
					caption(concept, code),
					null,
					null,
					null,
					null,
					null,
					start(effective,
							statement.hasEffectiveDateTimeType() ? statement.getEffectiveDateTimeType().getValue() : null,
							today),
					end(effective)));
		}

		return drugs;
	}

	private CodeableConcept medicationConcept(Type medication, String path) {
		if (medication instanceof CodeableConcept concept) {
			return concept;
		}

		throw new InvalidRequestException(path + " is required: medicationReference is not accepted");
	}

	private CodedItem routedCode(CodeableConcept concept, String path) {
		for (Coding coding : concept.getCoding()) {
			String token = codeSystems.tokenFor(coding.getSystem());
			if (token != null && CodeSystemTokens.MEDICATION.contains(token)) {
				return new CodedItem(token, coding.getCode());
			}
		}

		throw new InvalidRequestException(path
				+ " requires a PRK or HPK coding; expected one of "
				+ codeSystems.systemsFor(CodeSystemTokens.MEDICATION));
	}

	private MedicationCodes resolve(CodedItem code) {
		return medicationCodes.resolve(List.of(code)).getFirst();
	}

	/**
	 * The ATC the drug goes out with: the G-Standaard's for the product, and the host's own coding
	 * only where the G-Standaard has none.
	 *
	 * <p>That order round, because the G-Standaard's is the one the rules were written against —
	 * a host's ATC is a copy of the same fact at best and a different classification at worst, and
	 * the drug it belongs to has already been identified by its product code.
	 */
	private String atc(CodeableConcept concept, MedicationCodes codes) {
		if (codes.atc() != null && !codes.atc().isBlank()) {
			return codes.atc();
		}

		for (Coding coding : concept.getCoding()) {
			if (Systems.ATC.equals(coding.getSystem()) && coding.hasCode()) {
				return coding.getCode();
			}
		}

		return null;
	}

	/**
	 * What the prescriber will read in a signal about this drug: the host's own wording where it
	 * sent one, so the message reads against the dossier it came from, and the code otherwise.
	 *
	 * <p>Never blank. The Hub's duplicate-medication check titles its signal with this attribute
	 * and reads it positionally, so an entry without one costs the whole classic half of the check
	 * — the sort of thing that fails as silence rather than as an error.
	 */
	private String caption(CodeableConcept concept, CodedItem code) {
		for (Coding coding : concept.getCoding()) {
			if (coding.hasDisplay() && !coding.getDisplay().isBlank()) {
				return coding.getDisplay();
			}
		}

		if (concept.hasText() && !concept.getText().isBlank()) {
			return concept.getText();
		}

		return code.codeSystem() + " " + code.code();
	}

	/**
	 * The reason for prescribing, as an ICPC-1 NL code, which is what makes a dose check
	 * indication-specific: the G-Standaard's dose bands are keyed on the reason as well as the
	 * product, and without one the check falls back to the "alle zorg" band.
	 */
	private String reasonIcpc(MedicationRequest prescription) {
		for (CodeableConcept reason : prescription.getReasonCode()) {
			for (Coding coding : reason.getCoding()) {
				if (CodeSystemTokens.ICPC.equals(codeSystems.tokenFor(coding.getSystem()))) {
					return coding.getCode();
				}
			}
		}

		return null;
	}

	/**
	 * The host's own id for the record, so a finding can be pointed back at it. The resource
	 * {@code id} where the host set one — which is what an integrator should do — and its position
	 * in the request otherwise, so that the attribute is never empty and two entries never share
	 * a value.
	 */
	private String uid(String id, String kind, int index) {
		return id == null || id.isBlank() ? kind + "-" + index : id;
	}

	/**
	 * When the use starts.
	 *
	 * <p><strong>Today when the host said nothing</strong>, and that is a decision rather than a
	 * default. Both engines skip a drug whose start date is absent or in the future — the Hub
	 * compares {@code substring(@date,1,10)} numerically and an absent attribute is not a number —
	 * so a silent omission would take the drug out of the check without taking it out of the
	 * answer. A {@code MedicationStatement} asserts that the patient is using the medication, and
	 * a prescription being weighed is being written now, so "no later than today" is what both
	 * resources already say; today is that claim written down.
	 *
	 * <p>What is <em>not</em> invented is an end: a host that stated a period that has ended has
	 * said the medication is not current, and that entry is dropped upstream on purpose.
	 */
	private LocalDate start(Period period, Date stated, LocalDate today) {
		if (period != null && period.hasStart()) {
			return toLocalDate(period.getStart());
		}

		return stated == null ? today : toLocalDate(stated);
	}

	private LocalDate end(Period period) {
		return period != null && period.hasEnd() ? toLocalDate(period.getEnd()) : null;
	}

	private LocalDate toLocalDate(Date date) {
		return date.toInstant().atZone(ZoneId.systemDefault()).toLocalDate();
	}
}
