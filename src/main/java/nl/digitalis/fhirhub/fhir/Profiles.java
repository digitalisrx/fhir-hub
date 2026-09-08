package nl.digitalis.fhirhub.fhir;

/**
 * Canonical URLs of the payload profiles, defined in {@code ig/input/fsh/}.
 *
 * <p>SUSHI derives each of these as {@code {canonical}/StructureDefinition/{Id}}, so the FSH ids
 * and these constants are the same fact written twice. {@code IgCanonicalsTest} fails if they
 * drift apart.
 *
 * <p>These are not decoration: the providers hand them to {@code ProfileValidator}, so every
 * inbound payload is checked against the URL named here.
 */
public final class Profiles {

	/**
	 * The Implementation Guide's canonical, and the host that serves it.
	 *
	 * <p>Deliberately not used to build the constants below: an integrator reading a payload greps
	 * for the whole URL, and so does anyone chasing one through this codebase, so each is written
	 * out. {@code IgCanonicalsTest} checks that they all sit under this one.
	 */
	public static final String CANONICAL = "http://spec.digitalis.nl/fhir";

	public static final String FORMULARY_SESSION_INPUT =
			"http://spec.digitalis.nl/fhir/StructureDefinition/fhirhub-FormularySessionInput";

	public static final String CREATERX_SESSION_INPUT =
			"http://spec.digitalis.nl/fhir/StructureDefinition/fhirhub-CreateRxSessionInput";

	public static final String SESSION_OUTPUT =
			"http://spec.digitalis.nl/fhir/StructureDefinition/fhirhub-SessionOutput";

	public static final String RESULT_BUNDLE =
			"http://spec.digitalis.nl/fhir/StructureDefinition/fhirhub-ResultBundle";

	/**
	 * The request profile of {@code $check-medication-request}, on the surveillance base. The
	 * response has one too — {@link #SURVEILLANCE_BUNDLE}.
	 */
	public static final String SURVEILLANCE_INPUT =
			"http://spec.digitalis.nl/fhir/StructureDefinition/fhirhub-SurveillanceInput";

	/**
	 * The response of both surveillance operations: a collection Bundle of
	 * {@link #SURVEILLANCE_FINDING}, one entry per signal.
	 *
	 * <p>Unlike the constants above this one is not handed to {@code ProfileValidator} — outbound
	 * payloads are not validated on the request path, because that would put the reference
	 * validator in the path of every response for a payload this service built itself. It is here
	 * because {@code OutboundPayloadConformanceTest} validates against it in the build, which is
	 * where that gap is closed, and because {@code IgCanonicalsTest} then pins the FSH id against
	 * this string.
	 *
	 * <p>Note that nothing asserts it in {@code meta.profile}. The Bundle does validate clean
	 * against it, so claiming it would be honest — but the Implementation Guide tells integrators
	 * not to route on {@code meta.profile}, and the two have to move together.
	 */
	public static final String SURVEILLANCE_BUNDLE =
			"http://spec.digitalis.nl/fhir/StructureDefinition/fhirhub-SurveillanceBundle";

	/**
	 * One signal in that Bundle. Reached through {@link #SURVEILLANCE_BUNDLE}, which binds
	 * {@code entry.resource} to it, so validating the Bundle validates every finding in it.
	 */
	public static final String SURVEILLANCE_FINDING =
			"http://spec.digitalis.nl/fhir/StructureDefinition/fhirhub-SurveillanceFinding";

	/**
	 * The request profile of {@code $check-medication-statement}: the same context, a mandatory
	 * medication list, and no {@code prescription} slice at all. The absence is enforced rather
	 * than documented — the slicing is closed, so a {@code MedicationRequest} sent here is a 400.
	 */
	public static final String SURVEILLANCE_STATEMENT_INPUT =
			"http://spec.digitalis.nl/fhir/StructureDefinition/fhirhub-SurveillanceStatementInput";

	private Profiles() {
	}
}
