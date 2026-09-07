package nl.digitalis.fhirhub;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.EnableConfigurationProperties;

import nl.digitalis.fhirhub.config.PrescriptorProperties;
import nl.digitalis.fhirhub.gstandaard.GStandaardProperties;
import nl.digitalis.fhirhub.hub.HubProperties;

/**
 * FHIR R4 interface for two Digitalis applications: Prescriptor, at {@code /fhir/evs}, and
 * medication surveillance, at {@code /fhir/surveillance} — see {@code config.FhirConfig}.
 *
 * <p>There is no DataSource auto-configuration to exclude any more: {@code GStandaardJdbcConfig}
 * builds the Hikari pool itself from {@code gstandaard.datasource}, and Boot's JDBC
 * auto-configuration left the classpath with {@code spring-boot-starter-jdbc}. Before that it had
 * to be excluded by name, or Boot went looking for a primary {@code spring.datasource.url} that is
 * deliberately not set and failed at startup.
 *
 * <p>fhir-hub still holds no session state. The database is a read-only reference lookup.
 */
@SpringBootApplication
@EnableConfigurationProperties({ PrescriptorProperties.class, GStandaardProperties.class, HubProperties.class })
public class FhirHubApplication {

	public static void main(String[] args) {
		SpringApplication.run(FhirHubApplication.class, args);
	}
}
