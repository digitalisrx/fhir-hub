package nl.digitalis.fhirhub.prescriptor;

import java.util.Set;

/**
 * The G-Standaard / NHG subsystem tokens the upstream XML dialect understands.
 *
 * <p>These tokens end up as XML <em>attribute names</em> in the inner DigitalisRx document, so
 * they are validated against this allow-list before use. Without that, a caller-supplied code
 * system would be able to inject attributes.
 *
 * <p>They are the upstream vocabulary and nothing else. A token is never a {@code Coding.system}
 * on either side of this interface — it is not a URI — and {@code CodeSystemRegistry} is the one
 * place that maps between a token and the system URI that stands for it.
 */
public final class CodeSystemTokens {

	public static final String SSK = "SSK";
	public static final String SNK = "SNK";
	public static final String OGGRP = "OGGrp";

	public static final String CI_CODE = "CICode";
	public static final String ICPC = "ICPC";

	public static final String PRK = "PRK";
	public static final String HPK = "HPK";

	/** Prescribed-at level only: a host never sends current medication as a GPK. */
	public static final String GPK = "GPK";

	/**
	 * Two tokens that travel outbound only.
	 *
	 * <p>{@code ATC} is an attribute of {@code <drug>} and {@code LOINC} is an element name rather
	 * than an attribute, so neither is a subsystem a code is <em>routed</em> into the way the
	 * others are. They are here because a medication-surveillance report echoes both back inside a
	 * finding's context, and {@code CodeSystemRegistry.systemFor} needs a token to turn into a
	 * system URI. Deliberately absent from the sets below: nothing accepts an inbound code in
	 * either of them by way of this vocabulary.
	 */
	public static final String ATC = "ATC";

	public static final String LOINC = "LOINC";

	public static final Set<String> ALLERGY = Set.of(SSK, SNK, OGGRP);
	public static final Set<String> CONTRA_INDICATION = Set.of(CI_CODE, ICPC);
	public static final Set<String> MEDICATION = Set.of(PRK, HPK);

	private CodeSystemTokens() {
	}
}
