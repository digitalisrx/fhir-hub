package nl.digitalis.fhirhub.server;

import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.anyUrl;
import static com.github.tomakehurst.wiremock.client.WireMock.post;
import static com.github.tomakehurst.wiremock.client.WireMock.postRequestedFor;
import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
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
import org.hl7.fhir.r4.model.DateTimeType;
import org.hl7.fhir.r4.model.DateType;
import org.hl7.fhir.r4.model.DetectedIssue;
import org.hl7.fhir.r4.model.Enumerations.AdministrativeGender;
import org.hl7.fhir.r4.model.MedicationRequest;
import org.hl7.fhir.r4.model.MedicationStatement;
import org.hl7.fhir.r4.model.Observation;
import org.hl7.fhir.r4.model.OperationDefinition;
import org.hl7.fhir.r4.model.OperationOutcome;
import org.hl7.fhir.r4.model.Parameters;
import org.hl7.fhir.r4.model.Patient;
import org.hl7.fhir.r4.model.Quantity;
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

		HttpResponse<String> response = postFhir("/fhir/surveillance/$check-medication-request",
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

		postFhir("/fhir/surveillance/$check-medication-request", surveillanceParameters());

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
				.contains("<GStandaard SNK=\"10499\" caption=\"TALK\" UID=\"allergy-1\"")
				// A weight the host sent in LOINC, in the NHG identity the dose check selects on —
				// and in both forms, because the beslisregels read the other one.
				.contains("<LOINC num=\"29463-7\"")
				.contains("<NHG id=\"357\" memo=\"GEW\" mat=\"AO\" caption=\"Gewicht\"")
				.contains("value=\"70\" UID=\"weight-1\"");
	}

	/**
	 * A report that ran and found nothing. This is the only 200 with an empty Bundle the contract
	 * can produce, and the test below is the reason it has to be pinned separately.
	 */
	@Test
	void returnsAnEmptyBundleWhenTheCheckRanAndNothingFired() {
		stub("empty-report-response.xml");

		HttpResponse<String> response = postFhir("/fhir/surveillance/$check-medication-request",
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

		HttpResponse<String> fault = postFhir("/fhir/surveillance/$check-medication-request", surveillanceParameters());
		assertThat(fault.statusCode()).isEqualTo(500);
		assertThat(diagnostics(fault)).contains("could not be run");

		hub.resetAll();
		stub("no-report-response.xml");

		HttpResponse<String> noReport = postFhir("/fhir/surveillance/$check-medication-request", surveillanceParameters());
		assertThat(noReport.statusCode()).isEqualTo(500);
		assertThat(diagnostics(noReport)).contains("no report");

		hub.resetAll();
		hub.stubFor(post(anyUrl()).willReturn(aResponse().withStatus(502).withBody("<html>Bad Gateway</html>")));

		HttpResponse<String> garbage = postFhir("/fhir/surveillance/$check-medication-request", surveillanceParameters());
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

		HttpResponse<String> response = postFhir("/fhir/surveillance/$check-medication-request", in);

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

		HttpResponse<String> response = postFhir("/fhir/surveillance/$check-medication-request", in);

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

		HttpResponse<String> response = postFhir("/fhir/surveillance/$check-medication-request", in);

		assertThat(response.statusCode()).isEqualTo(400);
		assertThat(diagnostics(response)).contains("prescription");
		assertThat(hub.findAll(postRequestedFor(anyUrl()))).isEmpty();
	}

	/**
	 * A dossier with no prescription in it was a valid request until 0.4.0 — the check ran over
	 * the current medication and looked for interactions among it. {@code prescription} is 1..*
	 * now, so this is a 400, and it is pinned because the shape is one a host may already send:
	 * accepting it and answering "no signals" would be the false negative this contract is built
	 * to avoid, and silently checking nothing would be worse than refusing.
	 */
	@Test
	void refusesARequestCarryingOnlyCurrentMedication() {
		Parameters in = surveillanceParameters();
		in.getParameter().removeIf(p -> "prescription".equals(p.getName()));

		HttpResponse<String> response = postFhir("/fhir/surveillance/$check-medication-request", in);

		assertThat(response.statusCode()).isEqualTo(400);
		assertThat(diagnostics(response)).contains("prescription");
		assertThat(hub.findAll(postRequestedFor(anyUrl())))
				.as("nothing reaches the Hub for a request that cannot be checked")
				.isEmpty();
	}

	/**
	 * The dossier check, end to end. What makes it a check rather than a plausible-looking answer
	 * is the marking: every {@code medicationStatement} has to leave here as {@code pending="true"}
	 * and {@code trigger="true"}, because the Hub gates all of its own G-Standaard checks on at
	 * least one drug carrying {@code pending} ({@code MbFactory.process}) and returns a complete,
	 * empty report otherwise. Sending this list as the standing dossier would answer 200 with no
	 * findings for a check that never ran.
	 */
	@Test
	void checksAWholeDossierWithEveryEntryUnderTest() {
		stub("medication-surveillance-response.xml");

		HttpResponse<String> response = postFhir("/fhir/surveillance/$check-medication-statement",
				statementCheckParameters());

		assertThat(response.statusCode()).isEqualTo(200);
		assertThat(parser.parseResource(Bundle.class, response.body()).getEntry()).hasSize(3);

		String sent = hub.findAll(postRequestedFor(anyUrl())).getFirst().getBodyAsString();
		assertThat(sent)
				.as("every entry is a subject of the check, so every drug carries both marks")
				.contains("pending=\"true\"")
				.contains("trigger=\"true\"")
				.contains("PRK=\"43800\" GPK=\"222222\" HPK=\"2106\"")
				.contains("PRK=\"18996\" GPK=\"111111\"");
		assertThat(sent)
				.as("nothing is sent as context, because the dossier is the subject")
				.doesNotContain("pending=\"false\"");
	}

	/**
	 * A repeated determination reaches the Hub once, as the latest value.
	 *
	 * <p>Weight is the case that has to be right: the dose check selects it with
	 * {@code NHG[@id="357"]} and reads no date, so a weight from last year sent beside today's
	 * would be a dose band computed against the wrong patient — and the answer would look like a
	 * complete check. The LOINC form the beslisregels read is resolved by date upstream, but only
	 * one value is ever weighed there either, so both forms go out once.
	 */
	@Test
	void sendsOnlyTheMostRecentResultOfARepeatedDetermination() {
		stub("medication-surveillance-response.xml");

		Parameters parameters = surveillanceParameters();
		Observation stale = new Observation();
		stale.setId("weight-0");
		stale.setStatus(Observation.ObservationStatus.FINAL);
		stale.getCode().addCoding().setSystem(Systems.LOINC).setCode("29463-7");
		stale.setEffective(new DateTimeType("2024-01-15"));
		stale.setValue(new Quantity().setValue(52L)
				.setSystem(Systems.UCUM).setCode("kg").setUnit("kg"));
		parameters.addParameter().setName("observation").setResource(stale);

		assertThat(postFhir("/fhir/surveillance/$check-medication-request", parameters).statusCode())
				.isEqualTo(200);

		String sent = hub.findAll(postRequestedFor(anyUrl())).getFirst().getBodyAsString();
		assertThat(sent)
				.as("the weight of 2026-09-06, in both the forms a weight is read in")
				.contains("<LOINC num=\"29463-7\"")
				.contains("<NHG id=\"357\"")
				.contains("value=\"70\"");
		assertThat(sent).as("and the one from 2024 is not sent at all").doesNotContain("value=\"52\"");
		assertThat(sent.split("<LOINC ", -1)).as("one LOINC element").hasSize(2);
		assertThat(sent.split("<NHG ", -1)).as("one NHG element").hasSize(2);
	}

	/**
	 * A signal from the dossier check points at a {@code MedicationStatement}, not at a
	 * {@code MedicationRequest}. The report cannot say which — it echoes back the {@code pending}
	 * and {@code trigger} attributes this interface set, and those mean "under test" whichever
	 * operation asked — so the operation is what decides, and a wrong decision here hands a host a
	 * reference to a resource type it never sent.
	 */
	@Test
	void namesTheHostsMedicationStatementAsTheImplicatedResource() {
		stub("medication-surveillance-response.xml");

		Bundle bundle = parser.parseResource(Bundle.class,
				postFhir("/fhir/surveillance/$check-medication-statement", statementCheckParameters())
						.body());

		List<Reference> implicated = bundle.getEntry().stream()
				.map(entry -> (DetectedIssue) entry.getResource())
				.flatMap(issue -> issue.getImplicated().stream())
				.toList();

		assertThat(implicated).isNotEmpty();
		assertThat(implicated).extracting(Reference::getType).containsOnly("MedicationStatement");
	}

	/** No prescription slice at all, so one sent here is refused rather than quietly dropped. */
	@Test
	void refusesAPrescriptionOnTheDossierCheck() {
		Parameters in = statementCheckParameters();
		in.addParameter().setName("prescription")
				.setResource(surveillanceParameters().getParameter().stream()
						.filter(p -> "prescription".equals(p.getName()))
						.findFirst().orElseThrow().getResource());

		HttpResponse<String> response =
				postFhir("/fhir/surveillance/$check-medication-statement", in);

		assertThat(response.statusCode()).isEqualTo(400);
		// The closed-slicing message names the slices this profile does define rather than the one
		// it was sent, which is the same wording an unknown parameter name gets — the point being
		// that the payload is refused rather than answered for its context alone.
		assertThat(diagnostics(response))
				.contains("does not match any known slice")
				.contains("slicing is CLOSED")
				.contains("fhirhub-SurveillanceStatementInput");
		assertThat(hub.findAll(postRequestedFor(anyUrl()))).isEmpty();
	}

	/** The same fail-closed rule as the other operation, on the parameter that carries its subject. */
	@Test
	void refusesADossierCheckWithNoMedication() {
		Parameters in = statementCheckParameters();
		in.getParameter().removeIf(p -> "medicationStatement".equals(p.getName()));

		HttpResponse<String> response =
				postFhir("/fhir/surveillance/$check-medication-statement", in);

		assertThat(response.statusCode()).isEqualTo(400);
		assertThat(diagnostics(response)).contains("medicationStatement");
		assertThat(hub.findAll(postRequestedFor(anyUrl()))).isEmpty();
	}

	/**
	 * The dossier check publishes its own parameter list, and what an integrator has to see in it
	 * is the absence: no {@code prescription}, and a {@code medicationStatement} that is 1..*.
	 */
	@Test
	void describesTheDossierChecksParametersWithNoPrescriptionAmongThem() {
		CapabilityStatement statement = parser.parseResource(CapabilityStatement.class,
				getAnonymous("/fhir/surveillance/metadata").body());
		String definition = definitionOf(statement, "check-medication-statement");

		assertThat(definition).endsWith("/fhir/surveillance/OperationDefinition/-s-check-medication-statement");

		OperationDefinition operation = parser.parseResource(OperationDefinition.class,
				send(HttpRequest.newBuilder(URI.create(definition)).GET().build()).body());

		assertThat(operation.getParameter())
				.extracting(p -> p.getName() + " " + p.getMin() + ".." + p.getMax() + " " + p.getType())
				.containsExactly(
						"patient 1..1 Patient",
						"xisId 1..1 string",
						"xisVersion 1..1 string",
						"medicationStatement 1..* MedicationStatement",
						"allergyIntolerance 0..* AllergyIntolerance",
						"condition 0..* Condition",
						"observation 0..* Observation");
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

		assertThat(operationNames(statement))
				.containsExactlyInAnyOrder("check-medication-request", "check-medication-statement");
		assertThat(statement.getSoftware().getVersion())
				.as("both bases report the release of the one Implementation Guide")
				.matches("\\d+\\.\\d+\\.\\d+");
		assertThat(statement.getImplementation().getDescription())
				.contains("medication-surveillance contract");
	}

	/**
	 * The 0.4.0 rename is a clean break: {@code $check-medication} was published and current at
	 * 0.3.0, and this deployment does not answer it. Pinned as a test rather than left to the
	 * changelog because "the old name is gone" is a claim about behaviour, and an integrator
	 * reading the Breaking heading has to be able to rely on the status it names — a name that
	 * quietly answered would be worse than either choice made deliberately.
	 */
	@Test
	void theNameThisOperationHadAt0_3_0IsNoLongerServed() {
		HttpResponse<String> response = postFhir("/fhir/surveillance/$check-medication",
				surveillanceParameters());

		assertThat(response.statusCode())
				.as("HAPI answers an unknown operation on a known base with 400, not 404")
				.isEqualTo(400);
		assertThat(response.body())
				.as("a miss inside a known base is still a FHIR OperationOutcome")
				.contains("OperationOutcome")
				.contains("check-medication");
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
				.newBuilder(URI.create(url("/fhir/surveillance/$check-medication-request")))
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
		String definition = definitionOf(statement, "check-medication-request");

		assertThat(definition)
				.as("the address the Implementation Guide tells integrators to fetch")
				.endsWith("/fhir/surveillance/OperationDefinition/-s-check-medication-request");

		HttpResponse<String> response = send(HttpRequest.newBuilder(URI.create(definition)).GET().build());
		assertThat(response.statusCode()).isEqualTo(200);

		OperationDefinition operation = parser.parseResource(OperationDefinition.class, response.body());
		assertThat(operation.getParameter())
				.extracting(p -> p.getName() + " " + p.getMin() + ".." + p.getMax() + " " + p.getType())
				.containsExactly(
						"patient 1..1 Patient",
						"xisId 1..1 string",
						"xisVersion 1..1 string",
						"prescription 1..* MedicationRequest",
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

		assertThat(postFhir("/fhir/surveillance/$check-medication-request", in).body())
				.contains(Profiles.SURVEILLANCE_INPUT);
	}

	/**
	 * The published example is the documented request.
	 *
	 * <p>{@code ExampleSurveillanceReferenceCase} in the IG is the FHIR form of
	 * {@code DigitalisRx-documentation/example-1-req.xml}, and this is what makes that claim true
	 * rather than asserted: the example goes in over HTTP and the {@code DigitalisRx} the Hub would
	 * have received is compared with the document it is the FHIR form of. Nothing else would notice
	 * the example drifting — it satisfies its profile either way.
	 *
	 * <p>Two differences from that document are deliberate and are pinned here as differences, so
	 * that reading them off the wire is not mistaken for a bug:
	 * <ul>
	 * <li>The metformine goes out as PRK + GPK with <strong>no HPK</strong>. The document carries
	 * one, because the host that produced it had the drug at handelsproduct level too;
	 * {@code selected="PRK"} there says which level it chose, and a PRK-coded FHIR entry resolves
	 * to the pair.
	 * <li>The two current-medication entries carry <strong>no dosing, supply or indication</strong>.
	 * The document has all three on every drug; on this contract they travel with a
	 * {@code prescription} and a {@code MedicationStatement} has nowhere to put them. Only the
	 * proposal's dosing is read by the dose check, so nothing is lost that is read today — see the
	 * README's open items.
	 * </ul>
	 */
	@Test
	void postsTheReferenceCaseAsDocumented() throws IOException {
		stub("medication-surveillance-response.xml");

		Parameters example = parser.parseResource(Parameters.class,
				Files.readString(Path.of("ig", "fsh-generated", "resources",
						"Parameters-ExampleSurveillanceReferenceCase.json")));

		assertThat(postFhir("/fhir/surveillance/$check-medication-request", example).statusCode()).isEqualTo(200);

		String sent = hub.findAll(postRequestedFor(anyUrl())).getFirst().getBodyAsString();

		assertThat(sent)
				.as("the patient of the reference document")
				.contains("<gender>F</gender>")
				.contains("<dob>2007-09-07</dob>");

		assertThat(drug(sent, 0))
				.as("the metformine the signals are about, marked for both engines")
				.contains("ATC=\"A10BA02\"")
				.contains("UID=\"9064\"")
				.contains("pending=\"true\"")
				.contains("trigger=\"true\"")
				.contains("date=\"2026-09-06\"")
				.contains("dateEnd=\"2026-09-26\"")
				.contains("directionCoded=\"1D1T\"")
				.contains("directionCaption=\"1 X per dag 1 tablet\"")
				.contains("supplyQuantity=\"20\" supplyUnit=\"ST\"")
				.contains("PRK=\"1090\" GPK=\"3816\" selected=\"PRK\"")
				.contains("caption=\"METFORMINE TABLET   500MG\"")
				.contains("<RVV codeSystem=\"ICPC1\" codeValue=\"K86\"")
				.doesNotContain("HPK=");

		assertThat(drug(sent, 1))
				.as("ibuprofen, as the standing dossier it is weighed against")
				.contains("UID=\"9065\"")
				.contains("pending=\"false\"")
				.contains("date=\"2026-09-06\"")
				.contains("dateEnd=\"2026-09-11\"")
				.contains("PRK=\"27278\" GPK=\"51004\"")
				.contains("caption=\"IBUPROFEN TABLET 400MG\"")
				.doesNotContain("directionCoded")
				.doesNotContain("supplyQuantity")
				.doesNotContain("RVV");

		assertThat(drug(sent, 2))
				.contains("UID=\"9066\"")
				.contains("PRK=\"60062\" GPK=\"114529\"")
				.contains("caption=\"OMEPRAZOL CAPSULE MSR 20MG\"");

		assertThat(sent)
				.as("the dossier the rules read it against, each with the wording and id it came with")
				.contains("<GStandaard OGGrp=\"35\" caption=\"PENICILLINES\" UID=\"5469\"")
				.contains("<GStandaard SNK=\"10499\" caption=\"TALK\" UID=\"5470\"")
				.contains("<GStandaard CICode=\"1320\" caption=\"ZWANGERSCHAP\" UID=\"5468\"")
				// The eGFR, at the moment it was taken rather than at midnight, and with no NHG
				// twin: a determination the beslisregels read stays LOINC-only.
				.contains("<LOINC num=\"62238-1\"")
				.contains("date=\"2026-09-06T09:05:11\"")
				.contains("value=\"35\" UID=\"1667\"")
				.doesNotContain("<NHG");
	}

	/** The nth {@code <drug>} element, so an assertion cannot pass on another drug's attribute. */
	private String drug(String xml, int index) {
		String[] drugs = xml.split("<drug ");

		return drugs[index + 1].split("</drug>")[0];
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

		Observation weight = new Observation();
		weight.setId("weight-1");
		weight.setStatus(Observation.ObservationStatus.FINAL);
		weight.getCode().addCoding().setSystem(Systems.LOINC).setCode("29463-7");
		weight.setEffective(new DateTimeType("2026-09-06"));
		weight.setValue(new Quantity().setValue(70L)
				.setSystem(Systems.UCUM).setCode("kg").setUnit("kg"));

		Parameters parameters = new Parameters();
		parameters.addParameter().setName("patient").setResource(patient);
		parameters.addParameter().setName("xisId").setValue(new StringType("xis-001"));
		parameters.addParameter().setName("xisVersion").setValue(new StringType("1.0"));
		parameters.addParameter().setName("prescription").setResource(prescription);
		parameters.addParameter().setName("medicationStatement").setResource(statement);
		parameters.addParameter().setName("allergyIntolerance").setResource(allergy);
		parameters.addParameter().setName("observation").setResource(weight);

		return parameters;
	}

	/**
	 * The dossier check's payload: the shared context, and two medication entries rather than one,
	 * because "the entire list is under test" is only visibly different from "one drug is" when
	 * there is more than one.
	 */
	private Parameters statementCheckParameters() {
		Parameters parameters = surveillanceParameters();
		parameters.getParameter().removeIf(p -> "prescription".equals(p.getName()));

		MedicationStatement second = new MedicationStatement();
		second.setId("ms-2");
		second.setStatus(MedicationStatement.MedicationStatementStatus.ACTIVE);
		second.setSubject(absentReference());
		second.getMedicationCodeableConcept().addCoding()
				.setSystem(Systems.PRK)
				.setCode("18996")
				.setDisplay("PARACETAMOL ZETPIL 1000MG");

		parameters.addParameter().setName("medicationStatement").setResource(second);

		return parameters;
	}

	private String definitionOf(CapabilityStatement statement, String operation) {
		return statement.getRestFirstRep().getOperation().stream()
				.filter(o -> operation.equals(o.getName()))
				.map(CapabilityStatement.CapabilityStatementRestResourceOperationComponent::getDefinition)
				.findFirst()
				.orElseThrow(() -> new AssertionError(operation + " is not advertised"));
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
