package nl.digitalis.fhirhub.model;

import java.math.BigDecimal;
import java.time.LocalDate;

/**
 * One drug on its way into a medication-surveillance check, resolved and ready to be written as
 * a {@code <drug>} element.
 *
 * <p>Richer than the {@link CodedItem} a session sends, because the rules read more than the code:
 * the classic G-Standaard checks read the coded dosage and the supply, dose checking reads the
 * reason for prescribing, and both engines filter the dossier on the start and end dates. What is
 * absent is as load-bearing as what is present — see {@code MedicationSurveillanceRequestBuilder}
 * for what each field does upstream and which rules cannot fire without it.
 *
 * @param uid        the host's own record id for this entry, echoed back in the findings so a
 *                   signal can be pointed at the row it is about. The resource {@code id} the
 *                   host sent, or its position in the request when it sent none
 * @param codes      PRK + GPK (+ HPK), resolved from the G-Standaard before the call
 * @param atc        the ATC of the product; {@code ZZZZZZ} when the G-Standaard has none, which
 *                   is the value the schema reserves for products without one
 * @param caption    what the prescriber sees in a signal about this drug. The host's own wording
 *                   where it sent one, because a signal reads better against the dossier it came
 *                   from
 * @param directions the NHG Tabel 25 coded instruction, e.g. {@code 3-4D1T}
 * @param dosageText the same instruction in words, display-only upstream
 * @param startDate  when the use started, or is proposed to start. Both engines drop a drug whose
 *                   start lies in the future, and the classic checks drop one with no start at all
 * @param endDate    when the use ends, if the host stated an end
 */
public record SurveillanceDrug(
		String uid,
		MedicationCodes codes,
		String atc,
		String caption,
		String directions,
		String dosageText,
		BigDecimal supplyQuantity,
		String supplyUnit,
		String reasonIcpc,
		LocalDate startDate,
		LocalDate endDate) {
}
