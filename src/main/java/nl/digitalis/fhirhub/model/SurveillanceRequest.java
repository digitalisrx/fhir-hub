package nl.digitalis.fhirhub.model;

import java.time.LocalDate;
import java.util.List;

/**
 * A medication-surveillance question: this patient, this context, these proposed prescriptions.
 *
 * <p>Deliberately not a {@link SessionRequest}. The two contracts carry the same patient context
 * and are mapped from the same FHIR profiles, but what they do with the medication list is not
 * the same shape: a session pushes current medication in as a list of codes and lets Prescriptor
 * decide what to prescribe, while surveillance has to say which drugs are the <em>candidates</em>
 * and which are the standing dossier — and the candidates carry a dosage, a supply and a reason
 * for prescribing that current medication does not. Folding both into one record would put a
 * {@code trigger} flag on a session.
 *
 * @param proposed          the prescriptions being weighed. Marked {@code pending} and
 *                          {@code trigger} upstream; see {@code MedicationSurveillanceRequestBuilder}
 * @param currentMedication the standing dossier the proposals are weighed against
 */
public record SurveillanceRequest(
		XisInfo xis,
		String gender,
		LocalDate dateOfBirth,
		List<CodedItem> allergies,
		List<CodedItem> contraIndications,
		List<CodedItem> indications,
		List<SurveillanceDrug> proposed,
		List<SurveillanceDrug> currentMedication,
		List<LabResult> laboratoryData) {

	public SurveillanceRequest {
		allergies = List.copyOf(allergies);
		contraIndications = List.copyOf(contraIndications);
		indications = List.copyOf(indications);
		proposed = List.copyOf(proposed);
		currentMedication = List.copyOf(currentMedication);
		laboratoryData = List.copyOf(laboratoryData);
	}
}
