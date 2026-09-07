package nl.digitalis.fhirhub.prescriptor;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;

import java.util.List;

import ca.uhn.fhir.rest.server.exceptions.InternalErrorException;
import nl.digitalis.fhirhub.gstandaard.MedicationCodeResolver;
import nl.digitalis.fhirhub.model.MedicationCodes;
import nl.digitalis.fhirhub.model.PrescriptorCredentials;
import nl.digitalis.fhirhub.model.SessionHandle;
import nl.digitalis.fhirhub.model.SessionRequest;
import nl.digitalis.fhirhub.model.SessionResult;

/**
 * The one place that talks to Prescriptor.
 *
 * <p>Stateless: no session is stored here. Session state lives entirely upstream, keyed by the
 * id Prescriptor hands out, exactly as in the JSON interface.
 */
@Service
public class PrescriptorClient {

	private static final Logger log = LoggerFactory.getLogger(PrescriptorClient.class);

	private final RestClient http;
	private final XmlRpcRequestBuilder requests;
	private final XmlRpcResponseParser responses;
	private final MedicationCodeResolver medicationCodes;

	public PrescriptorClient(@Qualifier("prescriptorRestClient") RestClient prescriptorRestClient,
			XmlRpcRequestBuilder requests,
			XmlRpcResponseParser responses,
			MedicationCodeResolver medicationCodes) {
		this.http = prescriptorRestClient;
		this.requests = requests;
		this.responses = responses;
		this.medicationCodes = medicationCodes;
	}

	public SessionHandle openSession(SessionRequest request, PrescriptorCredentials credentials) {
		// Resolved before the call is built: a drug that cannot be resolved must abort the
		// session, not quietly drop out of medication surveillance.
		List<MedicationCodes> medication = medicationCodes.resolve(request.patient().medications());

		String xml = requests.openSession(request, credentials, medication);
		log.debug("XML-RPC {} request built for organization {}",
				request.type().methodName(), credentials.practiceId());

		SessionHandle handle = responses.parseSessionResponse(post(xml), request.type());
		log.info("Session created for organization {}, type {}, sessionId {}, xis: {} {}",
				credentials.practiceId(), request.type(), handle.sessionId(),
				request.xis().id(), request.xis().version());

		return handle;
	}

	public SessionResult requestResult(String sessionId) {
		log.info("Result requested for sessionId {}", sessionId);
		SessionResult result = responses.parseResultResponse(post(requests.requestResult(sessionId)));
		log.info("Result retrieved for sessionId {}: {} drug(s), {} advice(s)",
				sessionId, result.drugs().size(), result.advices().size());

		return result;
	}

	private String post(String xml) {
		try {
			return http.post()
					.contentType(MediaType.TEXT_XML)
					.body(xml)
					.retrieve()
					.body(String.class);
		}
		catch (RestClientException e) {
			throw new InternalErrorException("Could not reach Prescriptor", e);
		}
	}
}
