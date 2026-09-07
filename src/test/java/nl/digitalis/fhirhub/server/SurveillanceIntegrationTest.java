package nl.digitalis.fhirhub.server;

import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.anyUrl;
import static com.github.tomakehurst.wiremock.client.WireMock.post;
import static com.github.tomakehurst.wiremock.client.WireMock.postRequestedFor;
import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.List;

import org.hl7.fhir.r4.model.AllergyIntolerance;
import org.hl7.fhir.r4.model.Bundle;
import org.hl7.fhir.r4.model.CapabilityStatement;
import org.hl7.fhir.r4.model.CapabilityStatement.CapabilityStatementRestComponent;
import org.hl7.fhir.r4.model.CapabilityStatement.CapabilityStatementRestResourceOperationComponent;
import org.hl7.fhir.r4.model.CodeType;
import org.hl7.fhir.r4.model.DateType;
import org.hl7.fhir.r4.model.DetectedIssue;
import org.hl7.fhir.r4.model.Enumerations.AdministrativeGender;
import org.hl7.fhir.r4.model.MedicationRequest;
import org.hl7.fhir.r4.model.MedicationStatement;
import org.hl7.fhir.r4.model.OperationDefinition;
import org.hl7.fhir.r4.model.OperationOutcome;
import org.hl7.fhir.r4.model.Parameters;
import org.hl7.fhir.r4.model.Patient;
import org.hl7.fhir.r4.model.Reference;
import org.hl7.fhir.r4.model.StringType;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

import ca.uhn.fhir.context.FhirContext;
import ca.uhn.fhir.parser.IParser;
import com.github.tomakehurst.wiremock.WireMockServer;
import com.github.tomakehurst.wiremock.core.WireMockConfiguration;
import nl.digitalis.fhirhub.Fixtures;
import nl.digitalis.fhirhub.fhir.Profiles;
import nl.digitalis.fhirhub.fhir.Systems;

/**
 * The medication-surveillance base, over real HTTP, with the Digitalis Hub stubbed.
 *
 * <p>What needs pinning here is what unit tests cannot see: that a conformant request reaches the
 * Hub carrying the credentials and the marks that decide which drug is checked, that the report
 * comes back as a Bundle of DetectedIssue, that <strong>every</strong> way for the check not to
 * run is an error rather than an empty result, that the request profile is enforced, and that
 * neither base advertises the other's operations.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class SurveillanceIntegrationTest {

	private static final HttpClient CLIENT = HttpClient.newHttpClient();

	private static WireMockServer hub;

	@Autowired
	private FhirContext fhirContext;

	@LocalServerPort
	private int port;

	private IParser parser;

	@BeforeAll
	static void startHub() {
		hub = new WireMockServer(WireMockConfiguration.options().dynamicPort());
		hub.start();
	}

	@AfterAll
	static void stopHub() {
		hub.stop();
	}

	@DynamicPropertySource
	static void hubUrl(DynamicPropertyRegistry registry) {
		registry.add("hub.target-url", () -> hub.baseUrl() + "/call/");
	}

	@BeforeEach
	void reset() {
		hub.resetAll();
		parser = fhirContext.newJsonParser();
	}

	@Test
	void returnsTheReportAsABundleOfDetectedIssues() {
		stub("medication-surveillance-response.xml");

		HttpResponse<String> response = postFhir("/fhir/surveillance/$check-medication",
				surveillanceParameters());

		assertThat(response.statusCode()).isEqualTo(200);

		Bundle bundle = parser.parseResource(Bundle.class, response.body());
		assertThat(bundle.getEntry()).hasSize(3);
		assertThat(bundle.getIdentifier().getValue()).isEqualTo("83327A6E-FAED-4448-A20E-EFEA660C7627");

		DetectedIssue first = (DetectedIssue) bundle.getEntryFirstRep().getResource();
		assertThat(first.getSeverity()).isEqualTo(DetectedIssue.DetectedIssueSeverity.HIGH);
		assertThat(first.getCode().getText()).isEqualTo("Nierfunctie: metformine");
		assertThat(first.getDetail()).contains("lactaatacidose");
	}

	/**
	 * The request the Hub receives. Two attributes on the proposed drug decide which of the two
	 * engines behind that one call looks at it, the credentials have to arrive from the HTTP layer,
	 * and the current medication has to arrive resolved to the PRK + GPK pair with its ATC — a
	 * request missing any of them still gets a plausible-looking answer.
	 */
	@Test
	void sendsAWellFormedDigitalisRxDocumentCarryingTheCredentials() {
		stub("medication-surveillance-response.xml");

		postFhir("/fhir/surveillance/$check-medication", surveillanceParameters());

		String sent = hub.findAll(postRequestedFor(anyUrl())).getFirst().getBodyAsString();
		assertThat(sent)
				.contains("<MedicationSurveillance xmlns=\"http://hub.digitalis.nl/call\">")
				.contains("organisationUnitId=\"practice-123\"")
				.contains("key=\"license-key\"")
				.contains("pending=\"true\"")
				.contains("trigger=\"true\"")
				// The proposal, resolved: PRK 18996 is the paracetamol zetpil in the stand-in view.
				.contains("PRK=\"18996\" GPK=\"111111\"")
				.contains("ATC=\"N02BE01\"")
				// And the standing dossier, which is what it is weighed against.
				.contains("pending=\"false\"")
				.contains("PRK=\"43800\" GPK=\"222222\" HPK=\"2106\"")
				// The host's own wording and record id, all the way from Coding.display and
				// AllergyIntolerance.id to the attributes the Hub writes into its signal.
				.contains("<GStandaard SNK=\"10499\" caption=\"TALK\" UID=\"allergy-1\"");
	}

	/**
	 * A report that ran and found nothing. This is the only 200 with an empty Bundle the contract
	 * can produce, and the test below is the reason it has to be pinned separately.
	 */
	@Test
	void returnsAnEmptyBundleWhenTheCheckRanAndNothingFired() {
		stub("empty-report-response.xml");

		HttpResponse<String> response = postFhir("/fhir/surveillance/$check-medication",
				surveillanceParameters());

		assertThat(response.statusCode()).isEqualTo(200);
		assertThat(parser.parseResource(Bundle.class, response.body()).getEntry()).isEmpty();
	}

	/**
	 * And every way for the check <em>not</em> to run is a 500 with an OperationOutcome. An empty
	 * Bundle cannot be told apart from a genuine all-clear by a prescriber who sent a medication
	 * list, so nothing that failed may answer with one — the same false negative that makes an
	 * unresolvable drug code a 400 rather than a dropped drug.
	 */
	@Test
	void refusesToTurnAFailedCheckIntoAnAllClear() {
		hub.stubFor(post(anyUrl()).willReturn(aResponse()
				.withStatus(500)
				.withHeader("Content-Type", "text/xml")
				.withBody(Fixtures.hubXml("fault-response.xml"))));

		HttpResponse<String> fault = postFhir("/fhir/surveillance/$check-medication", surveillanceParameters());
		assertThat(fault.statusCode()).isEqualTo(500);
		assertThat(diagnostics(fault)).contains("could not be run");

		hub.resetAll();
		stub("no-report-response.xml");

		HttpResponse<String> noReport = postFhir("/fhir/surveillance/$check-medication", surveillanceParameters());
		assertThat(noReport.statusCode()).isEqualTo(500);
		assertThat(diagnostics(noReport)).contains("no report");

		hub.resetAll();
		hub.stubFor(post(anyUrl()).willReturn(aResponse().withStatus(502).withBody("<html>Bad Gateway</html>")));

		HttpResponse<String> garbage = postFhir("/fhir/surveillance/$check-medication", surveillanceParameters());
		assertThat(garbage.statusCode()).isEqualTo(500);
		assertThat(diagnostics(garbage)).contains("no conclusion may be drawn");
	}

	/**
	 * The G-Standaard lookup fails closed here exactly as it does for a session: a code the
	 * G-Standaard has no product for aborts the check rather than dropping the drug out of it.
	 */
	@Test
	void refusesADrugTheGStandaardCannotResolve() {
		stub("medication-surveillance-response.xml");

		Parameters in = surveillanceParameters();
		((MedicationStatement) resource(in, "medicationStatement"))
				.getMedicationCodeableConcept().getCodingFirstRep().setCode("404404");

		HttpResponse<String> response = postFhir("/fhir/surveillance/$check-medication", in);

		assertThat(response.statusCode()).isEqualTo(400);
		assertThat(diagnostics(response)).contains("G-Standaard has no product for HPK 404404");
		assertThat(hub.findAll(postRequestedFor(anyUrl()))).isEmpty();
	}

	/**
	 * The request profile is enforced before anything is sent, so an integrator gets every problem
	 * in one OperationOutcome and nothing reaches the Hub until the body conforms.
	 */
	@Test
	void validatesTheRequestBeforeCallingUpstream() {
		Parameters in = surveillanceParameters();
		in.getParameter().removeIf(p -> "xisId".equals(p.getName()));

		HttpResponse<String> response = postFhir("/fhir/surveillance/$check-medication", in);

		assertThat(response.statusCode()).isEqualTo(400);
		assertThat(diagnostics(response)).contains("xisId");
		assertThat(hub.findAll(postRequestedFor(anyUrl()))).isEmpty();
	}

	/** Neither a prescription to check nor a medication list to check it against. */
	@Test
	void refusesARequestWithNothingToCheck() {
		Parameters in = surveillanceParameters();
		in.getParameter().removeIf(p -> "prescription".equals(p.getName())
				|| "medicationStatement".equals(p.getName()));

		HttpResponse<String> response = postFhir("/fhir/surveillance/$check-medication", in);

		assertThat(response.statusCode()).isEqualTo(400);
		assertThat(response.body()).contains("fhirhub-something-to-check");
	}

	/**
	 * The two bases are separate contracts, and this is what notices if a provider ever extends
	 * the wrong marker: a session operation advertised on the surveillance base would tell an
	 * integrator to post patient data to an address that cannot serve it.
	 */
	@Test
	void theSurveillanceBaseAdvertisesOnlyItsOwnOperations() {
		CapabilityStatement statement = parser.parseResource(CapabilityStatement.class,
				getAnonymous("/fhir/surveillance/metadata").body());

		assertThat(operationNames(statement)).containsExactly("check-medication");
		assertThat(statement.getSoftware().getVersion())
				.as("both bases report the release of the one Implementation Guide")
				.matches("\\d+\\.\\d+\\.\\d+");
		assertThat(statement.getImplementation().getDescription())
				.contains("medication-surveillance contract");
	}

	@Test
	void theEvsBaseDoesNotAdvertiseTheSurveillanceOperation() {
		CapabilityStatement statement = parser.parseResource(CapabilityStatement.class,
				getAnonymous("/fhir/evs/metadata").body());

		assertThat(operationNames(statement))
				.containsExactlyInAnyOrder("formulary-session", "createrx-session", "session-result");
	}

	/** Discovery before credentials, on this base as on the other one. */
	@Test
	void servesItsCapabilityStatementUnauthenticatedAndNothingElse() {
		assertThat(getAnonymous("/fhir/surveillance/metadata").statusCode()).isEqualTo(200);

		HttpResponse<String> refused = send(HttpRequest
				.newBuilder(URI.create(url("/fhir/surveillance/$check-medication")))
				.header("Content-Type", "application/fhir+json")
				.POST(HttpRequest.BodyPublishers.ofString(
						parser.encodeResourceToString(surveillanceParameters())))
				.build());

		assertThat(refused.statusCode()).isEqualTo(401);
		assertThat(refused.headers().firstValue("WWW-Authenticate")).get().asString().startsWith("Basic");
	}

	/**
	 * The generated {@code OperationDefinition} is the parameter list an integrator can generate a
	 * request from, and the published guide names both its address and its contents. Readable
	 * unauthenticated, like the statement that advertises it.
	 */
	@Test
	void describesItsParametersInAnOperationDefinitionAnyoneCanRead() {
		CapabilityStatement statement = parser.parseResource(CapabilityStatement.class,
				getAnonymous("/fhir/surveillance/metadata").body());
		String definition = statement.getRestFirstRep().getOperationFirstRep().getDefinition();

		assertThat(definition)
				.as("the address the Implementation Guide tells integrators to fetch")
				.endsWith("/fhir/surveillance/OperationDefinition/-s-check-medication");

		HttpResponse<String> response = send(HttpRequest.newBuilder(URI.create(definition)).GET().build());
		assertThat(response.statusCode()).isEqualTo(200);

		OperationDefinition operation = parser.parseResource(OperationDefinition.class, response.body());
		assertThat(operation.getParameter())
				.extracting(p -> p.getName() + " " + p.getMin() + ".." + p.getMax() + " " + p.getType())
				.containsExactly(
						"patient 1..1 Patient",
						"xisId 1..1 string",
						"xisVersion 1..1 string",
						"prescription 0..* MedicationRequest",
						"medicationStatement 0..* MedicationStatement",
						"allergyIntolerance 0..* AllergyIntolerance",
						"condition 0..* Condition",
						"observation 0..* Observation");

		// No `use: out` parameter, which is HAPI's doing rather than a decision here — the two
		// session operations describe their responses the same way, which is to say not at all.
		// The response shape lives in the Implementation Guide; no profile is published for it.
	}

	/** The profile is the one the service names in its refusal, so an integrator can go read it. */
	@Test
	void namesTheProfileItValidatedAgainst() {
		Parameters in = surveillanceParameters();
		in.getParameter().removeIf(p -> "xisVersion".equals(p.getName()));

		assertThat(postFhir("/fhir/surveillance/$check-medication", in).body())
				.contains(Profiles.SURVEILLANCE_INPUT);
	}

	private void stub(String fixture) {
		hub.stubFor(post(anyUrl()).willReturn(aResponse()
				.withHeader("Content-Type", "text/xml; charset=utf-8")
				.withBody(Fixtures.hubXml(fixture))));
	}

	private List<String> operationNames(CapabilityStatement statement) {
		return statement.getRest().stream()
				.map(CapabilityStatementRestComponent::getOperation)
				.flatMap(List::stream)
				.map(CapabilityStatementRestResourceOperationComponent::getName)
				.toList();
	}

	private String diagnostics(HttpResponse<String> response) {
		return parser.parseResource(OperationOutcome.class, response.body())
				.getIssueFirstRep().getDiagnostics();
	}

	private org.hl7.fhir.r4.model.Resource resource(Parameters parameters, String name) {
		return parameters.getParameter().stream()
				.filter(p -> name.equals(p.getName()))
				.findFirst()
				.orElseThrow()
				.getResource();
	}

	/**
	 * The payload of the published example, minus the resources that are identical to the session
	 * ones: a patient, the two identifying strings, one prescription to check and one entry of
	 * standing medication to check it against.
	 */
	private Parameters surveillanceParameters() {
		Patient patient = new Patient();
		patient.setGender(AdministrativeGender.FEMALE);
		patient.setBirthDateElement(new DateType("1980-01-01"));

		MedicationRequest prescription = new MedicationRequest();
		prescription.setId("rx-1");
		prescription.setStatus(MedicationRequest.MedicationRequestStatus.ACTIVE);
		prescription.setIntent(MedicationRequest.MedicationRequestIntent.ORDER);
		prescription.setSubject(absentReference());
		prescription.getMedicationCodeableConcept().addCoding()
				.setSystem(Systems.PRK)
				.setCode("18996")
				.setDisplay("PARACETAMOL ZETPIL 1000MG");

		MedicationStatement statement = new MedicationStatement();
		statement.setId("ms-1");
		statement.setStatus(MedicationStatement.MedicationStatementStatus.ACTIVE);
		statement.setSubject(absentReference());
		statement.getMedicationCodeableConcept().addCoding()
				.setSystem(Systems.HPK)
				.setCode("2106")
				.setDisplay("OXYCODON HCL TABLET 5MG");

		AllergyIntolerance allergy = new AllergyIntolerance();
		allergy.setId("allergy-1");
		allergy.setPatient(absentReference());
		allergy.getClinicalStatus().addCoding()
				.setSystem("http://terminology.hl7.org/CodeSystem/allergyintolerance-clinical")
				.setCode("active");
		allergy.getCode().addCoding()
				.setSystem(Systems.G_STANDAARD_SNK)
				.setCode("10499")
				.setDisplay("TALK");

		Parameters parameters = new Parameters();
		parameters.addParameter().setName("patient").setResource(patient);
		parameters.addParameter().setName("xisId").setValue(new StringType("xis-001"));
		parameters.addParameter().setName("xisVersion").setValue(new StringType("1.0"));
		parameters.addParameter().setName("prescription").setResource(prescription);
		parameters.addParameter().setName("medicationStatement").setResource(statement);
		parameters.addParameter().setName("allergyIntolerance").setResource(allergy);

		return parameters;
	}

	/** Mandatory in base R4, never read here — the same idiom the session examples use. */
	private Reference absentReference() {
		Reference reference = new Reference();
		reference.addExtension("http://hl7.org/fhir/StructureDefinition/data-absent-reason",
				new CodeType("unknown"));

		return reference;
	}

	private HttpResponse<String> postFhir(String path, Parameters body) {
		return send(HttpRequest.newBuilder(URI.create(url(path)))
				.header("Authorization", "Basic " + encode(
						Fixtures.CREDENTIALS.practiceId() + ":" + Fixtures.CREDENTIALS.licenseKey()))
				.header("Content-Type", "application/fhir+json")
				.POST(HttpRequest.BodyPublishers.ofString(parser.encodeResourceToString(body)))
				.build());
	}

	private HttpResponse<String> getAnonymous(String path) {
		return send(HttpRequest.newBuilder(URI.create(url(path))).GET().build());
	}

	private HttpResponse<String> send(HttpRequest request) {
		try {
			return CLIENT.send(request, HttpResponse.BodyHandlers.ofString());
		}
		catch (IOException | InterruptedException e) {
			throw new IllegalStateException(e);
		}
	}

	private String url(String path) {
		return "http://localhost:" + port + path;
	}

	private static String encode(String pair) {
		return Base64.getEncoder().encodeToString(pair.getBytes(StandardCharsets.UTF_8));
	}
}
