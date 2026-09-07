package nl.digitalis.fhirhub.model;

/**
 * A code plus the G-Standaard / NHG subsystem token it belongs to, and the two things a host can
 * tell us about it that are not the code.
 *
 * <p>The token ({@code SNK}, {@code OGGrp}, {@code CICode}, {@code ICPC}, {@code PRK},
 * {@code HPK}) is what the upstream XML-RPC dialect keys on: it decides which member the code
 * is routed into. The FHIR mappers translate between the token and the full system URI in
 * {@link nl.digitalis.fhirhub.fhir.Systems}.
 *
 * @param caption the host's own wording for it, from {@code Coding.display} or the concept's
 *                {@code text}. Display-only, and not decoration on the surveillance contract: the
 *                Hub's allergy check interpolates this attribute into the title and the body of
 *                the signal it raises, so an empty one produces "In het dossier is een allergie
 *                () geregistreerd" — a signal that does not say what it is about. Measured
 *                against the live service, not deduced
 * @param uid     the host's own record id, echoed back inside a finding's context so a signal can
 *                be pointed at the row it came from
 */
public record CodedItem(String codeSystem, String code, String caption, String uid) {

	/** A code and nothing else, for the paths that route on the code alone. */
	public CodedItem(String codeSystem, String code) {
		this(codeSystem, code, null, null);
	}
}
