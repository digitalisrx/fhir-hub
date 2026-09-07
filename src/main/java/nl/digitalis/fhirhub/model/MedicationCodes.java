package nl.digitalis.fhirhub.model;

/**
 * The G-Standaard code set for one drug the patient is currently using.
 *
 * <p>A host sends a single code, PRK or HPK. Prescriptor's medication surveillance needs PRK and
 * GPK together (plus HPK when the host had it), so each drug is looked up in the {@code medcode}
 * view of the G-Standaard database before the session is opened.
 *
 * @param prk voorschrijfproduct code; always present
 * @param gpk generiek product code; always present
 * @param hpk handelsproduct code, only when the host identified the drug at HPK level
 * @param atc the product's ATC, from the same row. Read by the {@code /fhir/surveillance}
 *            contract and not by a session: a medisch-farmaceutische beslisregel selects on ATC
 *            far more often than on a product code, so a {@code <drug>} sent without one is
 *            invisible to most of the rules — see {@code MedicationSurveillanceRequestBuilder}.
 *            {@code null} where the G-Standaard has none, which the schema spells
 *            {@code ZZZZZZ} on the wire
 */
public record MedicationCodes(Integer prk, Integer gpk, Integer hpk, String atc) {
}
