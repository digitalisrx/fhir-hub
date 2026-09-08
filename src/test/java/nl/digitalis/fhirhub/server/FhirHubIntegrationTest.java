package nl.digitalis.fhirhub.server;

import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.anyUrl;
import static com.github.tomakehurst.wiremock.client.WireMock.post;
import static com.github.tomakehurst.wiremock.client.WireMock.postRequestedFor;
import static org.assertj.core.api.Assertions.assertThat;

import org.hl7.fhir.r4.model.Bundle;
import org.hl7.fhir.r4.model.CapabilityStatement;
import org.hl7.fhir.r4.model.CapabilityStatement.CapabilityStatementRestResourceOperationComponent;
import org.hl7.fhir.r4.model.Coding;
import org.hl7.fhir.r4.model.DateTimeType;
import org.hl7.fhir.r4.model.Enumerations.AdministrativeGender;
import org.hl7.fhir.r4.model.CodeableConcept;
import org.hl7.fhir.r4.model.MedicationRequest;
import org.hl7.fhir.r4.model.MedicationStatement;
import org.hl7.fhir.r4.model.Observation;
import org.hl7.fhir.r4.model.OperationDefinition;
import org.hl7.fhir.r4.model.OperationOutcome;
import org.hl7.fhir.r4.model.Parameters;
import org.hl7.fhir.r4.model.Patient;
import org.hl7.fhir.r4.model.CodeType;
import org.hl7.fhir.r4.model.Quantity;
import org.hl7.fhir.r4.model.Reference;
import org.hl7.fhir.r4.model.UrlType;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.List;

import ca.uhn.fhir.context.FhirContext;
import ca.uhn.fhir.parser.IParser;
import com.github.tomakehurst.wiremock.WireMockServer;
import com.github.tomakehurst.wiremock.core.WireMockConfiguration;
import nl.digitalis.fhirhub.Fixtures;
import nl.digitalis.fhirhub.fhir.SessionParametersMapper;
import nl.digitalis.fhirhub.fhir.Systems;

/**
 * End-to-end coverage of the three operations over real HTTP, with Prescriptor stubbed.
 *
 * <p>This is the test that would have caught the things unit tests cannot: that Basic
 * credentials actually reach the XML-RPC call, that HAPI renders an OperationOutcome for the
 * exceptions the parser throws, and that the CapabilityStatement advertises the operations.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class FhirHubIntegrationTest {

	private static WireMockServer prescriptor;

	private static final HttpClient CLIENT = HttpClient.newHttpClient();

	@Autowired
	private FhirContext fhirContext;

	@LocalServerPort
	private int port;

	private IParser parser;

	@BeforeAll
	static void startPrescriptor() {
		prescriptor = new WireMockServer(WireMockConfiguration.options().dynamicPort());
		prescriptor.start();
	}

	@AfterAll
	static void stopPrescriptor() {
		prescriptor.stop();
	}

	@DynamicPropertySource
	static void prescriptorUrl(DynamicPropertyRegistry registry) {
		registry.add("prescriptor.target-url", () -> prescriptor.baseUrl() + "/xmlrpc_dispatch.php");
	}

	@BeforeEach
	void reset() {
		prescriptor.resetAll();
		parser = fhirContext.newJsonParser();
	}

	@Test
	void opensAFormularySessionAndForwardsTheBasicCredentials() {
		stub("open-session-response.xml");

		HttpResponse<String> response = postFhir("/fhir/evs/$formulary-session", sessionParameters());

		assertThat(response.statusCode()).isEqualTo(200);

		Parameters out = parser.parseResource(Parameters.class, response.body());
		assertThat(value(out, SessionOperationProvider.OUT_SESSION_ID))
				.isEqualTo("sess-abc-123");
		assertThat(value(out, SessionOperationProvider.OUT_URL))
				.startsWith("https://evs.prescriptor.nl/");

		// The credentials arrived on the HTTP layer and left in the message body, which is the
		// whole point of the change.
		String sent = prescriptor.findAll(postRequestedFor(anyUrl())).getFirst().getBodyAsString();
		assertThat(sent).contains("<name>PracticeID</name><value><string>practice-123</string></value>");
		assertThat(sent).contains("<name>LicenseKey</name><value><string>license-key</string></value>");
		assertThat(sent).contains("<methodName>openSession</methodName>");

		// Current medication was resolved against G-Standaard and forwarded, so medication
		// surveillance sees what the patient is actually taking.
		assertThat(sent).contains("<GStandaard PRK=\"18996\" GPK=\"111111\"");
	}

	/**
	 * A drug G-Standaard cannot resolve aborts the session. Opening one anyway would leave
	 * surveillance running on an incomplete list and reporting a false all-clear.
	 */
	@Test
	void refusesToOpenASessionWithUnresolvableMedication() {
		stub("open-session-response.xml");

		// Conformant, so that what this test proves is the G-Standaard check rather than the
		// profile: surveillance must fail closed on a code it cannot resolve.
		MedicationStatement unknown = new MedicationStatement();
		unknown.setStatus(MedicationStatement.MedicationStatementStatus.ACTIVE);
		unknown.setSubject(unknownSubject());
		unknown.setMedication(new CodeableConcept().addCoding(
				new Coding().setSystem(Systems.PRK).setCode("404040")));

		Parameters in = sessionParameters();
		in.addParameter().setName(SessionParametersMapper.PARAM_MEDICATION).setResource(unknown);

		HttpResponse<String> response = postFhir("/fhir/evs/$formulary-session", in);

		assertThat(response.statusCode()).isEqualTo(400);
		OperationOutcome outcome = parser.parseResource(OperationOutcome.class, response.body());
		assertThat(outcome.getIssueFirstRep().getDiagnostics())
				.contains("404040")
				.contains("medication surveillance");

		// Nothing was sent upstream: the session was never opened.
		assertThat(prescriptor.findAll(postRequestedFor(anyUrl()))).isEmpty();
	}

	@Test
	void opensACreateRxSessionWithoutAnIcpcCode() {
		stub("open-session-response.xml");

		Parameters in = sessionParameters();
		in.getParameter().removeIf(p -> SessionParametersMapper.PARAM_REASON.equals(p.getName()));

		HttpResponse<String> response = postFhir("/fhir/evs/$createrx-session", in);

		assertThat(response.statusCode()).isEqualTo(200);
		Parameters out = parser.parseResource(Parameters.class, response.body());
		// CreateRx reports its key under a different member than the formulary session does.
		assertThat(value(out, SessionOperationProvider.OUT_SESSION_ID))
				.isEqualTo("rx-def-456");
	}

	@Test
	void returnsTheSessionResultAsABundle() {
		stub("request-result-response.xml");

		HttpResponse<String> response = getFhir("/fhir/evs/$session-result?session=sess-abc-123");

		assertThat(response.statusCode()).isEqualTo(200);

		Bundle bundle = parser.parseResource(Bundle.class, response.body());
		assertThat(bundle.getEntry()).hasSize(4);

		MedicationRequest first = (MedicationRequest) bundle.getEntryFirstRep().getResource();
		assertThat(first.getDosageInstructionFirstRep().getText())
				.isEqualTo("3-4 keer per dag 1 zetpil gedurende max. 1 maand");
	}

	@Test
	void rejectsAnUnauthenticatedRequest() {
		HttpResponse<String> response = getAnonymous("/fhir/evs/$session-result?session=x");

		assertThat(response.statusCode()).isEqualTo(401);
		assertThat(response.headers().firstValue("WWW-Authenticate")).get().asString().startsWith("Basic");
	}

	/**
	 * The rejection before the servlet is a FHIR resource like every other error this API returns.
	 * Spring Security answered with an empty body; an integrator with one error handler had to
	 * special-case exactly the response that means "you are not who you said you were".
	 */
	@Test
	void rendersAnUnauthenticatedRequestAsAnOperationOutcome() {
		HttpResponse<String> response = getAnonymous("/fhir/evs/$session-result?session=x");

		assertThat(response.statusCode()).isEqualTo(401);
		assertThat(response.headers().firstValue("Content-Type")).get().asString()
				.contains("application/fhir+json");

		OperationOutcome outcome = parser.parseResource(OperationOutcome.class, response.body());
		assertThat(outcome.getIssueFirstRep().getCode()).isEqualTo(OperationOutcome.IssueType.SECURITY);
		assertThat(outcome.getIssueFirstRep().getDiagnostics()).contains("practice id");
	}

	/**
	 * Everything the header can be other than a usable credential. None of these reaches an
	 * operation, and none of them is distinguished in the response: a caller learns that the
	 * credential was not accepted, not which part of it this service could read.
	 */
	@Test
	void refusesAnythingThatIsNotAWellFormedBasicCredential() {
		assertThat(statusWithAuthorization(null)).isEqualTo(401);
		assertThat(statusWithAuthorization("Bearer some-token")).as("another scheme").isEqualTo(401);
		assertThat(statusWithAuthorization("Basic")).as("no credential at all").isEqualTo(401);
		assertThat(statusWithAuthorization("Basic !!not-base64!!")).as("undecodable").isEqualTo(401);
		assertThat(statusWithAuthorization("Basic " + encode("no-colon-here"))).as("no separator").isEqualTo(401);
		assertThat(statusWithAuthorization("Basic " + encode(":key-only"))).as("blank practice id").isEqualTo(401);
		assertThat(statusWithAuthorization("Basic " + encode("practice-only:"))).as("blank key").isEqualTo(401);
		assertThat(statusWithAuthorization("Basic " + encode("  :  "))).as("both blank").isEqualTo(401);
	}

	/** RFC 7617 splits on the first colon: a user-id cannot contain one, a password can. */
	@Test
	void acceptsALicenseKeyContainingAColon() {
		stub("open-session-response.xml");

		HttpResponse<String> response = send(HttpRequest
				.newBuilder(URI.create(url("/fhir/evs/$formulary-session")))
				.header("Authorization", "Basic " + encode("practice-1:key:with:colons"))
				.header("Content-Type", "application/fhir+json")
				.POST(HttpRequest.BodyPublishers.ofString(
						parser.encodeResourceToString(sessionParameters())))
				.build());

		assertThat(response.statusCode()).isEqualTo(200);
		assertThat(prescriptor.findAll(postRequestedFor(anyUrl())).getFirst().getBodyAsString())
				.contains("<name>LicenseKey</name><value><string>key:with:colons</string></value>");
	}

	/** The scheme name is case-insensitive; some clients send it lowercase. */
	@Test
	void acceptsTheSchemeInAnyCase() {
		stub("open-session-response.xml");

		HttpResponse<String> response = send(HttpRequest
				.newBuilder(URI.create(url("/fhir/evs/$formulary-session")))
				.header("Authorization", "basic " + encode(
						Fixtures.CREDENTIALS.practiceId() + ":" + Fixtures.CREDENTIALS.licenseKey()))
				.header("Content-Type", "application/fhir+json")
				.POST(HttpRequest.BodyPublishers.ofString(
						parser.encodeResourceToString(sessionParameters())))
				.build());

		assertThat(response.statusCode()).isEqualTo(200);
	}

	private int statusWithAuthorization(String header) {
		HttpRequest.Builder request = HttpRequest.newBuilder(URI.create(url("/fhir/evs/$session-result?session=x")));
		if (header != null) {
			request = request.header("Authorization", header);
		}

		return send(request.GET().build()).statusCode();
	}

	private static String encode(String pair) {
		return Base64.getEncoder().encodeToString(pair.getBytes(StandardCharsets.UTF_8));
	}

	/** An upstream fault is a credential problem, and must surface as a FHIR OperationOutcome. */
	@Test
	void rendersAnUpstreamFaultAsAnOperationOutcome() {
		stub("fault-response.xml");

		HttpResponse<String> response = postFhir("/fhir/evs/$formulary-session", sessionParameters());

		assertThat(response.statusCode()).isEqualTo(401);

		OperationOutcome outcome = parser.parseResource(OperationOutcome.class, response.body());
		assertThat(outcome.getIssueFirstRep().getDiagnostics()).contains("organization ID or key");
	}

	@Test
	void rendersAnUnknownSessionAsAnOperationOutcome() {
		stub("nil-response.xml");

		HttpResponse<String> response = getFhir("/fhir/evs/$session-result?session=gone");

		assertThat(response.statusCode()).isEqualTo(401);
		OperationOutcome outcome = parser.parseResource(OperationOutcome.class, response.body());
		assertThat(outcome.getIssueFirstRep().getDiagnostics()).contains("session");
	}

	@Test
	void rendersAValidationFailureAsAnOperationOutcome() {
		Parameters in = sessionParameters();
		in.getParameter().removeIf(p -> SessionParametersMapper.PARAM_END_SESSION_URL.equals(p.getName()));

		HttpResponse<String> response = postFhir("/fhir/evs/$formulary-session", in);

		assertThat(response.statusCode()).isEqualTo(400);
		OperationOutcome outcome = parser.parseResource(OperationOutcome.class, response.body());
		assertThat(outcome.getIssueFirstRep().getDiagnostics()).contains("endSessionUrl");
	}

	/**
	 * A parameter with no name is a 400, and the reason this is pinned over HTTP is that it used
	 * to be a 500: the reference validator crashes on a nameless parameter
	 * (NullPointerException on a null key in its table of standard parameter names) before it can
	 * report the name base R4 requires, and HAPI renders that crash as HAPI-0389. A malformed
	 * request must not arrive as this service having failed.
	 */
	@Test
	void refusesAParameterWithNoName() {
		Observation weight = new Observation();
		weight.setStatus(Observation.ObservationStatus.FINAL);
		weight.getCode().addCoding().setSystem(Systems.LOINC).setCode("29463-7");
		weight.setEffective(new DateTimeType("2026-09-08"));
		weight.setValue(new Quantity().setValue(70L)
				.setSystem(Systems.UCUM).setCode("kg").setUnit("kg"));

		Parameters in = sessionParameters();
		in.addParameter().setResource(weight);

		HttpResponse<String> response = postFhir("/fhir/evs/$formulary-session", in);

		assertThat(response.statusCode()).isEqualTo(400);
		OperationOutcome outcome = parser.parseResource(OperationOutcome.class, response.body());
		assertThat(outcome.getIssueFirstRep().getDiagnostics()).contains("has no name");

		// Rejected before the session was opened, like any other non-conformant payload.
		assertThat(prescriptor.findAll(postRequestedFor(anyUrl()))).isEmpty();
	}

	/** Integrators must be able to discover the operations before they hold credentials. */
	@Test
	void servesAnUnauthenticatedCapabilityStatement() {
		HttpResponse<String> response = getAnonymous("/fhir/evs/metadata");

		assertThat(response.statusCode()).isEqualTo(200);
		assertThat(response.body())
				.contains("formulary-session")
				.contains("createrx-session")
				.contains("session-result");
	}

	/**
	 * Which release of the published specification this deployment implements has to be
	 * answerable, because the change policy tells integrators to check it: a parameter name
	 * introduced in a later release is a 400 here rather than an ignored element, since the
	 * inbound slicing is closed. The number comes off the profiles in the jar, so this also fails
	 * if those were built without the version stamp.
	 */
	@Test
	void reportsTheSpecificationReleaseInTheCapabilityStatement() {
		CapabilityStatement statement = parser.parseResource(CapabilityStatement.class,
				getAnonymous("/fhir/evs/metadata").body());

		assertThat(statement.getSoftware().getName()).isEqualTo("Digitalis fhir-hub");
		assertThat(statement.getSoftware().getVersion())
				.as("the IG version, not HAPI's")
				.matches("\\d+\\.\\d+\\.\\d+");
		assertThat(statement.getImplementation().getDescription())
				.contains("http://spec.digitalis.nl/fhir")
				.contains(statement.getSoftware().getVersion());
	}

	/**
	 * The CapabilityStatement is public and links each operation to an OperationDefinition, so
	 * those links have to resolve for the same anonymous integrator that just read them.
	 */
	@Test
	void servesTheAdvertisedOperationDefinitionsUnauthenticated() {
		CapabilityStatement statement = parser.parseResource(CapabilityStatement.class,
				getAnonymous("/fhir/evs/metadata").body());

		List<CapabilityStatementRestResourceOperationComponent> operations =
				statement.getRestFirstRep().getOperation();
		assertThat(operations).hasSize(3);

		for (CapabilityStatementRestResourceOperationComponent operation : operations) {
			HttpResponse<String> response = send(
					HttpRequest.newBuilder(URI.create(operation.getDefinition())).GET().build());

			assertThat(response.statusCode())
					.as("anonymous read of %s", operation.getDefinition())
					.isEqualTo(200);
			assertThat(parser.parseResource(OperationDefinition.class, response.body()).getCode())
					.isEqualTo(operation.getName());
		}
	}

	/**
	 * The point of declaring the parameters individually: the generated OperationDefinition has
	 * to be complete enough for an integrator to generate a request from, and has to carry the
	 * cardinality difference between the two session operations.
	 *
	 */
	@Test
	void describesTheSessionParametersInTheOperationDefinition() {
		OperationDefinition formulary = operationDefinition("formulary-session");

		assertThat(formulary.getParameter())
				.extracting(p -> p.getName() + " " + p.getMin() + ".." + p.getMax() + " " + p.getType())
				.containsExactly(
						"patient 1..1 Patient",
						"reason 1..1 CodeableConcept",
						"endSessionUrl 1..1 url",
						"xisId 1..1 string",
						"xisVersion 1..1 string",
						"allergyIntolerance 0..* AllergyIntolerance",
						"condition 0..* Condition",
						"medicationStatement 0..* MedicationStatement",
						"observation 0..* Observation",
						"prescription 0..0 MedicationRequest");

		// reason and prescription are the two the operations declare differently.
		assertThat(operationDefinition("createrx-session").getParameter())
				.filteredOn(p -> List.of("reason", "prescription").contains(p.getName()))
				.extracting(p -> p.getName() + " " + p.getMin() + ".." + p.getMax())
				.containsExactly("reason 0..1", "prescription 0..1");
	}

	private OperationDefinition operationDefinition(String code) {
		CapabilityStatement statement = parser.parseResource(CapabilityStatement.class,
				getAnonymous("/fhir/evs/metadata").body());

		String definition = statement.getRestFirstRep().getOperation().stream()
				.filter(o -> code.equals(o.getName()))
				.findFirst()
				.orElseThrow()
				.getDefinition();

		return parser.parseResource(OperationDefinition.class,
				send(HttpRequest.newBuilder(URI.create(definition)).GET().build()).body());
	}

	/**
	 * The profiles are enforced, not merely published. This payload is one fhir-hub's own rules
	 * accept happily — the mapper never looks at MedicationStatement.status — so a 400 here can
	 * only come from the profile.
	 */
	@Test
	void rejectsAPayloadThatDoesNotSatisfyTheProfile() {
		Parameters in = sessionParameters();
		medicationStatementIn(in).setStatus(null);

		HttpResponse<String> response = postFhir("/fhir/evs/$formulary-session", in);

		assertThat(response.statusCode()).isEqualTo(400);

		OperationOutcome outcome = parser.parseResource(OperationOutcome.class, response.body());
		assertThat(outcome.getIssue()).isNotEmpty();
		assertThat(outcome.getIssueFirstRep().getDiagnostics())
				.contains("MedicationStatement.status");
	}

	/** Warnings must not be treated as failures: every G-Standaard coding produces one. */
	@Test
	void acceptsAConformantPayloadDespiteUnresolvableCodeSystems() {
		stub("open-session-response.xml");

		HttpResponse<String> response = postFhir("/fhir/evs/$formulary-session", sessionParameters());

		assertThat(response.statusCode())
				.as("G-Standaard code systems cannot be expanded, and that is a warning")
				.isEqualTo(200);
	}

	private MedicationStatement medicationStatementIn(Parameters parameters) {
		return (MedicationStatement) parameters.getParameter().stream()
				.filter(p -> SessionParametersMapper.PARAM_MEDICATION.equals(p.getName()))
				.findFirst()
				.orElseThrow()
				.getResource();
	}

	/** A browser ranks text/html first; the highlighter must answer it with HTML, not XML. */
	@Test
	void rendersTheCapabilityStatementAsHtmlForABrowser() {
		HttpResponse<String> response = send(HttpRequest.newBuilder(URI.create(url("/fhir/evs/metadata")))
				.header("Accept", "text/html,application/xhtml+xml,application/xml;q=0.9,*/*;q=0.8")
				.GET().build());

		assertThat(response.statusCode()).isEqualTo(200);
		assertThat(response.headers().firstValue("Content-Type")).get().asString().startsWith("text/html");
		assertThat(response.body()).contains("formulary-session");
	}

	/**
	 * A payload that is conformant, not merely one this interface happens to accept.
	 *
	 * <p>{@code Observation.status} and {@code MedicationStatement.status}/{@code subject} are
	 * mandatory in base R4 and are never read here — see the profiles in {@code ig/}. They are
	 * set because the request is validated before it is mapped; without them this fixture is a
	 * 400, which is exactly what {@link #rejectsAPayloadThatDoesNotSatisfyTheProfile} asserts.
	 */
	private Parameters sessionParameters() {
		Patient patient = new Patient();
		patient.setGender(AdministrativeGender.FEMALE);
		patient.setBirthDateElement(new org.hl7.fhir.r4.model.DateType("1980-01-01"));

		Observation klaring = new Observation();
		klaring.setStatus(Observation.ObservationStatus.FINAL);
		klaring.getCode().addCoding(new Coding().setSystem(Systems.LOINC).setCode("62238-1"));
		klaring.setValue(new Quantity().setValue(65).setSystem(Systems.UCUM)
				.setCode("mL/min/{1.73_m2}").setUnit("mL/min/1.73m2"));
		klaring.setEffective(new DateTimeType("2024-07-04"));

		MedicationStatement current = new MedicationStatement();
		current.setStatus(MedicationStatement.MedicationStatementStatus.ACTIVE);
		current.setSubject(unknownSubject());
		current.setMedication(new CodeableConcept().addCoding(
				new Coding().setSystem(Systems.PRK).setCode("18996")));

		Parameters parameters = new Parameters();
		parameters.addParameter().setName(SessionParametersMapper.PARAM_PATIENT).setResource(patient);
		parameters.addParameter().setName(SessionParametersMapper.PARAM_MEDICATION).setResource(current);
		parameters.addParameter().setName(SessionParametersMapper.PARAM_REASON)
				.setValue(new CodeableConcept().addCoding(
						new Coding().setSystem(Systems.ICPC_1_NL).setCode("A01")));
		parameters.addParameter().setName(SessionParametersMapper.PARAM_END_SESSION_URL)
				.setValue(new UrlType("https://someurl.example/done"));
		parameters.addParameter().setName(SessionParametersMapper.PARAM_OBSERVATION).setResource(klaring);

		parameters.addParameter().setName(SessionParametersMapper.PARAM_XIS_ID)
				.setValue(new org.hl7.fhir.r4.model.StringType("xis-001"));
		parameters.addParameter().setName(SessionParametersMapper.PARAM_XIS_VERSION)
				.setValue(new org.hl7.fhir.r4.model.StringType("1.0"));

		return parameters;
	}

	/**
	 * The patient travels as a sibling parameter, not a contained resource, so there is nothing
	 * for a mandatory subject reference to point at. data-absent-reason is the FHIR idiom for
	 * exactly that, and the same one $session-result uses for MedicationRequest.subject.
	 */
	private Reference unknownSubject() {
		Reference reference = new Reference();
		reference.addExtension("http://hl7.org/fhir/StructureDefinition/data-absent-reason",
				new CodeType("unknown"));

		return reference;
	}

	private void stub(String fixture) {
		prescriptor.stubFor(post(anyUrl()).willReturn(aResponse()
				.withStatus(200)
				.withHeader("Content-Type", "text/xml")
				.withBody(Fixtures.xml(fixture))));
	}

	private HttpResponse<String> postFhir(String path, Parameters body) {
		return send(authenticated(path)
				.header("Content-Type", "application/fhir+json")
				.POST(HttpRequest.BodyPublishers.ofString(parser.encodeResourceToString(body)))
				.build());
	}

	private HttpResponse<String> getFhir(String path) {
		return send(authenticated(path).GET().build());
	}

	private HttpResponse<String> getAnonymous(String path) {
		return send(HttpRequest.newBuilder(URI.create(url(path))).GET().build());
	}

	private HttpRequest.Builder authenticated(String path) {
		String pair = Fixtures.CREDENTIALS.practiceId() + ":" + Fixtures.CREDENTIALS.licenseKey();
		String encoded = Base64.getEncoder()
				.encodeToString(pair.getBytes(StandardCharsets.UTF_8));

		return HttpRequest.newBuilder(URI.create(url(path))).header("Authorization", "Basic " + encoded);
	}

	private HttpResponse<String> send(HttpRequest request) {
		try {
			return CLIENT.send(request, HttpResponse.BodyHandlers.ofString());
		}
		catch (Exception e) {
			throw new IllegalStateException("Request failed: " + request.uri(), e);
		}
	}

	private String value(Parameters parameters, String name) {
		return parameters.getParameter().stream()
				.filter(p -> name.equals(p.getName()))
				.map(p -> p.getValue().primitiveValue())
				.findFirst()
				.orElse(null);
	}

	private String url(String path) {
		return "http://localhost:" + port + path;
	}
}
