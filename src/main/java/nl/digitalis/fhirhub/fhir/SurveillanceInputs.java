package nl.digitalis.fhirhub.fhir;

import java.util.List;

import org.hl7.fhir.r4.model.AllergyIntolerance;
import org.hl7.fhir.r4.model.Condition;
import org.hl7.fhir.r4.model.MedicationRequest;
import org.hl7.fhir.r4.model.MedicationStatement;
import org.hl7.fhir.r4.model.Observation;
import org.hl7.fhir.r4.model.Patient;
import org.hl7.fhir.r4.model.StringType;

/**
 * The inbound parameters of {@code $check-medication}, as HAPI destructured them.
 *
 * <p>The sibling of {@link SessionInputs}, and shaped by the same constraint: every parameter is
 * bound individually so that HAPI can generate an {@code OperationDefinition} carrying every
 * name, type and cardinality. Those {@code min}/{@code max} declarations are documentation —
 * HAPI reads them when it builds the definition and never while binding a request — so
 * {@link SurveillanceParametersMapper} and the profile are what actually reject a bad request.
 *
 * <p>{@code prescription} is a list here where a session takes one, because a proposed regimen is
 * weighed as a whole: two new drugs can interact with each other and with nothing the patient
 * already takes.
 */
public record SurveillanceInputs(
		Patient patient,
		StringType xisId,
		StringType xisVersion,
		List<MedicationRequest> prescription,
		List<MedicationStatement> medicationStatement,
		List<AllergyIntolerance> allergyIntolerance,
		List<Condition> condition,
		List<Observation> observation) {

	public SurveillanceInputs {
		prescription = prescription == null ? List.of() : List.copyOf(prescription);
		medicationStatement = medicationStatement == null ? List.of() : List.copyOf(medicationStatement);
		allergyIntolerance = allergyIntolerance == null ? List.of() : List.copyOf(allergyIntolerance);
		condition = condition == null ? List.of() : List.copyOf(condition);
		observation = observation == null ? List.of() : List.copyOf(observation);
	}
}
