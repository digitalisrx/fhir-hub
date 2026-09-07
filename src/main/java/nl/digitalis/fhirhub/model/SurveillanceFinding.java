package nl.digitalis.fhirhub.model;

import java.util.List;

/**
 * One signal: a rule fired, and this is what it says and what it was looking at.
 *
 * @param ruleId      the rule's identifier upstream — {@code MFB-0000000068-v000006} for a
 *                    medisch-farmaceutische beslisregel, {@code hub-doublemedication-prkA-1090}
 *                    and its siblings for the classic G-Standaard checks
 * @param ruleVersion the rule's version, as a string because the two sources count differently
 * @param alertLevel  1 red, 2 orange, 3 green, per the schema. {@code null} when the report
 *                    carried a level outside that set, which is left absent rather than guessed
 * @param title       the rule's own title
 * @param caption     the message's title, which the schema says is the more specific of the two
 *                    when it is present at all
 * @param text        the message, flattened to plain text — see
 *                    {@code MedicationSurveillanceResponseParser.plainText}
 * @param context     what the rule was looking at: the drugs, lab results, contra-indications and
 *                    allergies it read, as echoed back in the report
 */
public record SurveillanceFinding(
		String ruleId,
		String ruleVersion,
		Integer alertLevel,
		String title,
		String caption,
		String text,
		List<FindingContext> context) {

	public SurveillanceFinding {
		context = List.copyOf(context);
	}

	/** The title to show: the message's own where it has one, the rule's otherwise. */
	public String displayTitle() {
		return caption == null || caption.isBlank() ? title : caption;
	}
}
