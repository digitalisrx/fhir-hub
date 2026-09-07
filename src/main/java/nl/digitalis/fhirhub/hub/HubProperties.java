package nl.digitalis.fhirhub.hub;

import java.net.URI;
import java.time.Duration;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Configuration for the Digitalis Hub, which serves the medication-surveillance check.
 *
 * <p>A second upstream rather than a second URL on the first: the Hub is a SOAP service in front
 * of the clinical-rules engine and the G-Standaard, and Prescriptor is an XML-RPC endpoint in
 * front of the prescribing application. Only the surveillance contract talks to this one.
 *
 * @param targetUrl    the SOAP endpoint; validated at startup
 * @param connectTimeout how long to wait for a connection
 * @param readTimeout  how long to wait for an answer. Longer than Prescriptor's by default,
 *                     because a check runs the rules engine and then a series of G-Standaard
 *                     lookups per proposed drug — the published example reports 109 ms for the
 *                     rules alone, and the dose check is the slow half
 */
@ConfigurationProperties(prefix = "hub")
public record HubProperties(
		URI targetUrl,
		Duration connectTimeout,
		Duration readTimeout) {

	public HubProperties {
		if (targetUrl == null || targetUrl.getHost() == null) {
			throw new IllegalStateException("hub.target-url must be set to an absolute URL");
		}

		connectTimeout = connectTimeout == null ? Duration.ofSeconds(10) : connectTimeout;
		readTimeout = readTimeout == null ? Duration.ofSeconds(60) : readTimeout;
	}
}
