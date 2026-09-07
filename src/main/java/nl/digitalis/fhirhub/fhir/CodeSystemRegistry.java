package nl.digitalis.fhirhub.fhir;

import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.springframework.stereotype.Component;

import nl.digitalis.fhirhub.prescriptor.CodeSystemTokens;

/**
 * Translates between FHIR {@code system} URIs and the upstream subsystem tokens.
 *
 * <p>The two vocabularies are kept apart on purpose, and the asymmetry is the point:
 * {@link #tokenFor} accepts only a {@code Coding.system} — a URI — and {@link #systemFor} is the
 * only place that turns an upstream token back into one. A token such as {@code PRK} is an XML
 * attribute name in the DigitalisRx document (see {@link CodeSystemTokens}), not an identifier a
 * caller may put in {@code Coding.system}: it is not a URI, so accepting it would make this a
 * FHIR interface that also speaks something else, and the something else would be the form a
 * host discovers first.
 *
 * <p>So there is exactly one accepted system per subsystem, it is the one the profiles in
 * {@code ig/} bind and the one {@link #systemFor} emits, and anything else fails — twice over: a
 * validation error against the profile, and, if validation is switched off, an
 * {@code InvalidRequestException} from {@code SessionParametersMapper} naming what it should have
 * been. {@code TerminologyEnforcementTest} pins both.
 *
 * <p>Several URIs may still map to one token, which is what would let a terminology change be
 * additive: a new URI can be registered beside the current one and the old one dropped once
 * integrators have moved. With sixteen integrating systems that matters more than tidiness.
 *
 * <p>See {@link Systems} for where each OID came from and which one is inferred.
 */
@Component
public class CodeSystemRegistry {

	private final Map<String, String> tokenBySystem = new LinkedHashMap<>();

	public CodeSystemRegistry() {
		register(CodeSystemTokens.SSK, Systems.G_STANDAARD_SSK);
		register(CodeSystemTokens.SNK, Systems.G_STANDAARD_SNK);
		register(CodeSystemTokens.OGGRP, Systems.G_STANDAARD_OGGRP);
		register(CodeSystemTokens.CI_CODE, Systems.G_STANDAARD_CONTRA_INDICATIE);
		register(CodeSystemTokens.ICPC, Systems.ICPC_1_NL);
		register(CodeSystemTokens.PRK, Systems.PRK);
		register(CodeSystemTokens.HPK, Systems.HPK);
	}

	private void register(String token, String... systems) {
		for (String system : systems) {
			tokenBySystem.put(system, token);
		}
	}

	/** The upstream token for a FHIR system URI, or null when the system is not one we route. */
	public String tokenFor(String system) {
		return system == null ? null : tokenBySystem.get(system);
	}

	/** The canonical FHIR system URI to emit for a given upstream token. */
	public String systemFor(String token) {
		return switch (token) {
			case CodeSystemTokens.PRK -> Systems.PRK;
			case CodeSystemTokens.HPK -> Systems.HPK;
			case CodeSystemTokens.GPK -> Systems.GPK;
			case CodeSystemTokens.ICPC -> Systems.ICPC_1_NL;
			case CodeSystemTokens.SSK -> Systems.G_STANDAARD_SSK;
			case CodeSystemTokens.SNK -> Systems.G_STANDAARD_SNK;
			case CodeSystemTokens.OGGRP -> Systems.G_STANDAARD_OGGRP;
			case CodeSystemTokens.CI_CODE -> Systems.G_STANDAARD_CONTRA_INDICATIE;
			case CodeSystemTokens.ATC -> Systems.ATC;
			case CodeSystemTokens.LOINC -> Systems.LOINC;
			default -> throw new IllegalArgumentException("No system URI known for token " + token);
		};
	}

	/**
	 * The system URIs a caller may use for a set of upstream tokens, sorted so the order is
	 * stable. This exists so a rejection can name the URIs to send rather than the internal
	 * tokens, which are exactly the strings a caller must <em>not</em> put in a
	 * {@code Coding.system}.
	 */
	public List<String> systemsFor(Collection<String> tokens) {
		return tokens.stream().map(this::systemFor).sorted().toList();
	}
}
