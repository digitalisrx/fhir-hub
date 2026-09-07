package nl.digitalis.fhirhub.config;

import java.time.Duration;
import java.util.List;

import org.springframework.boot.web.servlet.ServletRegistrationBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.client.ClientHttpRequestFactory;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.web.client.RestClient;

import ca.uhn.fhir.context.FhirContext;
import ca.uhn.fhir.rest.api.EncodingEnum;
import ca.uhn.fhir.rest.server.RestfulServer;
import ca.uhn.fhir.rest.server.interceptor.ResponseHighlighterInterceptor;
import nl.digitalis.fhirhub.fhir.Profiles;
import nl.digitalis.fhirhub.hub.HubProperties;
import nl.digitalis.fhirhub.fhir.SpecificationVersion;
import nl.digitalis.fhirhub.server.EvsProvider;
import nl.digitalis.fhirhub.server.SurveillanceProvider;

/** Wires the two HAPI FHIR servers and the HTTP client that talks to Prescriptor. */
@Configuration
public class FhirConfig {

	/**
	 * The EVS contract's base URL, and the reason it carries a name rather than being
	 * {@code /fhir}.
	 *
	 * <p>FHIR gives an operation three places to live — {@code [base]/$op},
	 * {@code [base]/[Type]/$op} and {@code [base]/[Type]/[id]/$op} — and reserves the path space
	 * under a base for resource type names. So a second contract on this host cannot be a segment
	 * inside one base: {@code /fhir/evs/$formulary-session} under a base of {@code /fhir} parses as
	 * a type-level operation on a resource type called {@code evs}, and there is no such type.
	 * Separate contracts are separate bases, each with its own {@code metadata}, which is why
	 * {@link #SURVEILLANCE_BASE} is a sibling of this rather than a path below it.
	 */
	public static final String EVS_BASE = "/fhir/evs";

	/**
	 * The medication-surveillance contract's base URL.
	 *
	 * <p>A second base rather than a second service, because the two contracts share the parts
	 * that are expensive and safety-relevant: the payload profiles and their resource-level
	 * profiles, the G-Standaard code resolution with its fail-closed rule, the validator and its
	 * dependency exclusions, the Basic-authentication filter, and the version stamped on the
	 * artifacts. A separate deployable would duplicate all of it, along with the SBOM and SOUP
	 * inventory that ships with it.
	 *
	 * <p>What is <em>not</em> shared is the provider set: each base advertises only its own
	 * operations, which is what {@link EvsProvider} and {@link SurveillanceProvider} are for.
	 *
	 * <p>The upstream is not shared either: this base talks to the Digitalis Hub over SOAP
	 * ({@code hub/}) and the EVS base to Prescriptor over XML-RPC ({@code prescriptor/}).
	 */
	public static final String SURVEILLANCE_BASE = "/fhir/surveillance";

	/**
	 * A FhirContext is expensive to build and cheap to share; one per application is the
	 * documented HAPI pattern.
	 */
	@Bean
	FhirContext fhirContext() {
		return FhirContext.forR4();
	}

	@Bean
	ServletRegistrationBean<RestfulServer> fhirServlet(FhirContext fhirContext, List<EvsProvider> providers,
			SpecificationVersion specification) {
		return servlet(EVS_BASE, "fhir-hub",
				new FhirHubRestfulServer(fhirContext, providers, specification, "the EVS contract"));
	}

	/**
	 * The surveillance base is registered like any other, so its {@code metadata},
	 * {@code OperationDefinition}s and {@code OperationOutcome} rendering all work — the
	 * operation behind it is the only part that is missing.
	 */
	@Bean
	ServletRegistrationBean<RestfulServer> surveillanceServlet(FhirContext fhirContext,
			List<SurveillanceProvider> providers, SpecificationVersion specification) {
		return servlet(SURVEILLANCE_BASE, "fhir-hub-surveillance",
				new FhirHubRestfulServer(fhirContext, providers, specification,
						"the medication-surveillance contract"));
	}

	private ServletRegistrationBean<RestfulServer> servlet(String base, String name, RestfulServer server) {
		ServletRegistrationBean<RestfulServer> registration =
				new ServletRegistrationBean<>(server, base + "/*");
		registration.setName(name);
		registration.setLoadOnStartup(1);

		return registration;
	}

	@Bean
	RestClient prescriptorRestClient(PrescriptorProperties properties) {
		return restClient(properties.targetUrl().toString(),
				properties.connectTimeout(), properties.readTimeout());
	}

	/**
	 * The client for the Digitalis Hub, which serves the surveillance base.
	 *
	 * <p>A second {@code RestClient} rather than a shared one with two base URLs: the two
	 * upstreams have their own timeouts — a rules run plus a dose check is slower than opening a
	 * session — and a bean per upstream is what lets a deployment move one without the other. They
	 * are built by the same method, so the two differ in configuration and in nothing else.
	 */
	@Bean
	RestClient hubRestClient(HubProperties properties) {
		return restClient(properties.targetUrl().toString(),
				properties.connectTimeout(), properties.readTimeout());
	}

	private RestClient restClient(String baseUrl, Duration connectTimeout, Duration readTimeout) {
		SimpleClientHttpRequestFactory factory = new SimpleClientHttpRequestFactory();
		factory.setConnectTimeout(connectTimeout);
		factory.setReadTimeout(readTimeout);

		return RestClient.builder()
				.baseUrl(baseUrl)
				.requestFactory((ClientHttpRequestFactory) factory)
				.build();
	}

	/** The HAPI plain server, configured for R4 with JSON as the default encoding. */
	static class FhirHubRestfulServer extends RestfulServer {

		private static final long serialVersionUID = 1L;

		FhirHubRestfulServer(FhirContext fhirContext, List<?> providers,
				SpecificationVersion specification, String contract) {
			super(fhirContext);
			registerProviders(providers);
			// HAPI would otherwise announce itself as "HAPI FHIR Server" at its own version,
			// which answers a question nobody asked. What an integrator needs from
			// GET [base]/metadata is which release of the published specification this deployment
			// implements: the change policy tells them to check it before sending a parameter
			// introduced in a later one, because inbound slicing is closed and a name this
			// deployment does not know is a 400 rather than an ignored element.
			//
			// One number for both bases, because there is one Implementation Guide and one
			// version stamped on every artifact in it. A release that only moves the
			// surveillance contract still moves the number an EVS integrator reads, which is why
			// the changelog names the contract each change belongs to.
			setServerName("Digitalis fhir-hub");
			setServerVersion(specification.version());
			setImplementationDescription("Digitalis fhir-hub, implementing " + contract + " of "
					+ Profiles.CANONICAL + " release " + specification.version());
			setDefaultResponseEncoding(EncodingEnum.JSON);
			// Integrators debugging a payload should get readable output without asking.
			setDefaultPrettyPrint(true);
			// A browser ranks text/html above application/xml, so without this the
			// CapabilityStatement rendered as raw XML. Machine clients are unaffected:
			// the interceptor only engages on Accept: text/html or _format=html.
			registerInterceptor(new ResponseHighlighterInterceptor());
		}
	}
}
