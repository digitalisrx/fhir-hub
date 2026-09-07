package nl.digitalis.fhirhub.hub;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;

import ca.uhn.fhir.rest.server.exceptions.InternalErrorException;
import nl.digitalis.fhirhub.model.PrescriptorCredentials;
import nl.digitalis.fhirhub.model.SurveillanceReport;
import nl.digitalis.fhirhub.model.SurveillanceRequest;

/**
 * The one place that talks to the Digitalis Hub, which runs the medication-surveillance check:
 * the G-Standaard's medisch-farmaceutische beslisregels through the clinical-rules engine, and
 * the classic allergy, age, duplicate-medication and dose checks through the G-Standaard service.
 *
 * <p>Stateless, like {@code PrescriptorClient}: one request, one answer, nothing stored.
 *
 * <p>The {@code RestClient} is qualified by name because there are two of them now, and this
 * application has no Spring Boot parent POM and so no {@code -parameters} on the compiler:
 * without the annotation Spring cannot fall back to matching the parameter name against the bean
 * name, and the context fails to start with "expected single matching bean but found 2".
 *
 * <p><strong>The Hub does not adjudicate the credentials.</strong> It replaces the licence in the
 * payload with its own before calling the rules engine, and its {@code MedicationSurveillance}
 * operation checks nothing else — so unlike a session, where Prescriptor rejects a bad practice id
 * with a 401 that this interface passes straight through, a well-formed {@code Authorization}
 * header is all this contract can currently insist on. That is a gap in the contract rather than
 * in this class: it is recorded in the Implementation Guide, and closing it means either a
 * credential check upstream or one in front of this service.
 */
@Service
public class HubClient {

	private static final Logger log = LoggerFactory.getLogger(HubClient.class);

	private final RestClient http;
	private final MedicationSurveillanceRequestBuilder requests;
	private final MedicationSurveillanceResponseParser responses;

	public HubClient(@Qualifier("hubRestClient") RestClient hubRestClient,
			MedicationSurveillanceRequestBuilder requests,
			MedicationSurveillanceResponseParser responses) {
		this.http = hubRestClient;
		this.requests = requests;
		this.responses = responses;
	}

	public SurveillanceReport checkMedication(SurveillanceRequest request, PrescriptorCredentials credentials) {
		String xml = requests.medicationSurveillance(request, credentials);
		log.debug("Medication surveillance requested for organization {}: {} proposed, {} current, "
				+ "{} allergy, {} contra-indication, {} lab",
				credentials.practiceId(), request.proposed().size(), request.currentMedication().size(),
				request.allergies().size(), request.contraIndications().size(),
				request.laboratoryData().size());

		return responses.parse(post(xml));
	}

	/**
	 * A transport failure is a 500 and never an empty result, for the reason the whole surveillance
	 * contract exists: a caller cannot tell "the check found nothing" from "the check did not
	 * happen", so the second must not be able to look like the first.
	 *
	 * <p><strong>An error status is read rather than thrown on</strong>, which is why the default
	 * status handler is replaced by one that only logs. SOAP 1.1 carries a fault with HTTP 500, so
	 * the default behaviour would turn the one response that explains itself into an exception with
	 * the explanation buried in it — and the response parser is where the difference between a
	 * fault, an unparseable body and a real report is decided, fail-closed and in one place. The
	 * handler must not throw for that to work.
	 *
	 * <p>{@code SOAPAction} is sent because the WSDL declares one. The service resolves the
	 * operation from the body element and its own examples send no such header, so this is
	 * politeness towards a standard rather than a requirement.
	 */
	private String post(String xml) {
		try {
			return http.post()
					.contentType(MediaType.TEXT_XML)
					.header("SOAPAction", "\"" + MedicationSurveillanceRequestBuilder.SOAP_ACTION + "\"")
					.body(xml)
					.retrieve()
					.onStatus(HttpStatusCode::isError, (request, response) ->
							log.error("Medication surveillance answered HTTP {}", response.getStatusCode()))
					.body(String.class);
		}
		catch (RestClientException e) {
			log.error("Medication surveillance could not be reached", e);

			throw new InternalErrorException(
					"Could not reach medication surveillance, so no conclusion may be drawn about this"
							+ " patient's medication.");
		}
	}
}
