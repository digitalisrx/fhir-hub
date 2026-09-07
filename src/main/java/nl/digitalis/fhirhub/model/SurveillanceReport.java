package nl.digitalis.fhirhub.model;

import java.time.LocalDateTime;
import java.util.List;

/**
 * One clinical-rules report: the findings, and enough about the run to ask a support question
 * about it later.
 *
 * <p>A report with no findings is a genuine all-clear and is <em>not</em> the same thing as an
 * absent report. {@code MedicationSurveillanceResponseParser} refuses a response that carries no
 * report element at all rather than presenting it as an empty one, because the difference between
 * "nothing fired" and "nothing ran" is the whole safety question of this contract.
 *
 * @param crid           the report id the clinical-rules service assigns. Support asks for this
 * @param serviceVersion the version of the rules service that answered
 * @param reportedAt     the moment upstream stamped the report
 * @param activatedRules how many rules the service says it activated. Reported for the record and
 *                       deliberately not used to decide anything: the Hub appends its own
 *                       G-Standaard signals to the report afterwards without touching the count,
 *                       so it can be lower than {@code findings.size()}
 */
public record SurveillanceReport(
		String crid,
		String serviceVersion,
		LocalDateTime reportedAt,
		Integer activatedRules,
		List<SurveillanceFinding> findings) {

	public SurveillanceReport {
		findings = List.copyOf(findings);
	}
}
