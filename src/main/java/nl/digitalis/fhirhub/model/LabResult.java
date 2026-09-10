package nl.digitalis.fhirhub.model;

import java.time.LocalDate;
import java.time.LocalTime;

/**
 * One laboratory determination on its way upstream.
 *
 * <p>The LOINC code travels all the way through: the upstream carries lab data as
 * {@code <LOINC num="…">} and the rules engine tests it by that number, so nothing here is
 * translated into another terminology. {@code caption} is display-only, and {@code value} is in
 * {@code unit} because the rules are written in one — see {@code fhir/LabDeterminations}.
 *
 * <p>{@code time} is the time of day the sample was taken, or {@code null} when the host stated
 * only a date. It is what decides which of several results for one determination is forwarded:
 * only the most recent is sent, and a date-only value collapses to midnight, so two results from
 * one day tie and the tie is broken by the order they were sent in rather than by which is later.
 * The same comparison is made again upstream on the {@code date} attribute alone — see
 * {@code ClinicalContextMapper.mostRecentPerDetermination} and
 * {@code DigitalisRxBuilder.upstreamMoment} for the two shapes the engine parses.
 *
 * <p>{@code uid} is the host's own id for the record, echoed back inside a finding's context so a
 * signal about a lab value can be pointed at the row it came from. Only the surveillance contract
 * sends it.
 */
public record LabResult(
		String loinc,
		String caption,
		String unit,
		LocalDate date,
		LocalTime time,
		String value,
		String uid) {

	/** A determination without the host's record id, for the contract that does not echo one. */
	public LabResult(String loinc, String caption, String unit, LocalDate date, LocalTime time,
			String value) {
		this(loinc, caption, unit, date, time, value, null);
	}

	public LabResult {
		// Coding.display when the host sent one; the determination's own name otherwise, so the
		// prescriber sees something better than a code.
		if (caption == null || caption.isBlank()) {
			caption = loinc;
		}
	}
}
