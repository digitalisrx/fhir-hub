package nl.digitalis.fhirhub.fhir;

import java.util.List;

import org.hl7.fhir.r4.model.AllergyIntolerance;
import org.hl7.fhir.r4.model.Condition;
import org.hl7.fhir.r4.model.MedicationStatement;
import org.hl7.fhir.r4.model.Observation;
import org.hl7.fhir.r4.model.Patient;
import org.hl7.fhir.r4.model.StringType;

/**
 * The inbound parameters of {@code $check-medication-statement}, as HAPI destructured them.
 *
 * <p>The sibling of {@link SurveillanceInputs} with one parameter missing and one changed:
 * <strong>there is no {@code prescription}</strong>, and {@code medicationStatement} is mandatory.
 * That is the whole difference between the two operations, and it is deliberately expressed as a
 * separate type rather than as a flag on the other one — a request record whose subject depends on
 * which of its lists happens to be empty is a record that cannot be validated, and this contract
 * refuses an empty check rather than answering it.
 *
 * <p>Absent rather than accepted-and-ignored: a host that posts a {@code MedicationRequest} here is
 * refused by the closed slicing of {@code fhirhub-SurveillanceStatementInput}. Ignoring it would
 * mean checking a drug's context while leaving the drug itself out of the check.
 */
public record StatementCheckInputs(
		Patient patient,
		StringType xisId,
		StringType xisVersion,
		List<MedicationStatement> medicationStatement,
		List<AllergyIntolerance> allergyIntolerance,
		List<Condition> condition,
		List<Observation> observation) {

	public StatementCheckInputs {
		medicationStatement = medicationStatement == null ? List.of() : List.copyOf(medicationStatement);
		allergyIntolerance = allergyIntolerance == null ? List.of() : List.copyOf(allergyIntolerance);
		condition = condition == null ? List.of() : List.copyOf(condition);
		observation = observation == null ? List.of() : List.copyOf(observation);
	}
}
