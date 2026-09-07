package nl.digitalis.fhirhub.model;

import java.util.List;

/**
 * One thing a rule was looking at when it fired, as the report echoes it back.
 *
 * <p>This is what makes a signal actionable rather than merely true: "the nierfunctie is too low"
 * is a different message from "the nierfunctie of 35 mL/min you sent on 6 September, against the
 * metformine you are prescribing". The upstream repeats the elements it read inside the finding,
 * including the {@code UID} the host put on them, so a signal can be pointed back at the row in
 * the dossier it is about.
 *
 * @param kind    which part of the dossier this was
 * @param uid     the host's own id for the record, when it sent one and the report echoed it
 * @param caption the human-readable wording that travelled with it
 * @param codes   the codes it carried — a drug arrives with PRK, GPK and its ATC together
 * @param value   the measured value, for a lab result; {@code null} for everything else
 */
public record FindingContext(
		Kind kind,
		String uid,
		String caption,
		List<CodedItem> codes,
		String value) {

	public FindingContext {
		codes = List.copyOf(codes);
	}

	/**
	 * Which part of the dossier a context element came from.
	 *
	 * <p>{@link #PROPOSED_DRUG} and {@link #CURRENT_DRUG} are told apart by the {@code pending}
	 * and {@code trigger} attributes the report echoes back, which is the only way round: both
	 * arrive as {@code <drug>} elements in the same {@code <medication>} list. The distinction is
	 * worth keeping because it decides which of the host's resources a finding points at — the
	 * prescription it is weighing, or the medication the patient already takes.
	 */
	public enum Kind {
		PROPOSED_DRUG,
		CURRENT_DRUG,
		LAB_RESULT,
		CONTRA_INDICATION,
		INDICATION,
		ALLERGY
	}
}
