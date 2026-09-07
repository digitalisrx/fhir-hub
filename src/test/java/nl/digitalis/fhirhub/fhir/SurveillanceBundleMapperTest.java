package nl.digitalis.fhirhub.fhir;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.groups.Tuple.tuple;

import java.time.LocalDateTime;
import java.util.List;

import org.hl7.fhir.r4.model.Bundle;
import org.hl7.fhir.r4.model.DetectedIssue;
import org.hl7.fhir.r4.model.DetectedIssue.DetectedIssueSeverity;
import org.hl7.fhir.r4.model.DetectedIssue.DetectedIssueStatus;
import org.junit.jupiter.api.Test;

import nl.digitalis.fhirhub.Fixtures;
import nl.digitalis.fhirhub.hub.MedicationSurveillanceResponseParser;
import nl.digitalis.fhirhub.model.CodedItem;
import nl.digitalis.fhirhub.model.FindingContext;
import nl.digitalis.fhirhub.model.FindingContext.Kind;
import nl.digitalis.fhirhub.model.SurveillanceFinding;
import nl.digitalis.fhirhub.model.SurveillanceReport;

/**
 * The report as a host reads it.
 *
 * <p>Driven from the published example response through the real parser, so the assertions are
 * about a payload that has been produced rather than one composed here.
 */
class SurveillanceBundleMapperTest {

	private final SurveillanceBundleMapper mapper = new SurveillanceBundleMapper(new CodeSystemRegistry());

	private final Bundle bundle = mapper.toBundle(
			new MedicationSurveillanceResponseParser().parse(
					Fixtures.hubXml("medication-surveillance-response.xml")));

	@Test
	void returnsOneDetectedIssuePerSignal() {
		assertThat(bundle.getType()).isEqualTo(Bundle.BundleType.COLLECTION);
		assertThat(bundle.getEntry()).hasSize(3);
		assertThat(bundle.getEntry()).allSatisfy(entry -> {
			assertThat(entry.getFullUrl()).startsWith("urn:uuid:");
			assertThat(entry.getResource()).isInstanceOf(DetectedIssue.class);
		});
	}

	/** The handle support asks for when a prescriber queries a signal. */
	@Test
	void identifiesTheReportItCameFrom() {
		assertThat(bundle.getIdentifier().getSystem()).isEqualTo(Systems.CRS_REPORT);
		assertThat(bundle.getIdentifier().getValue()).isEqualTo("83327A6E-FAED-4448-A20E-EFEA660C7627");
		assertThat(bundle.hasTimestamp()).isTrue();
	}

	@Test
	void carriesTheRuleAsAnIdentifierAndItsTitleAsText() {
		DetectedIssue issue = issue(0);

		assertThat(issue.getStatus()).isEqualTo(DetectedIssueStatus.FINAL);
		assertThat(issue.getIdentifierFirstRep().getSystem()).isEqualTo(Systems.CRS_RULE);
		assertThat(issue.getIdentifierFirstRep().getValue()).isEqualTo("MFB-0000000068-v000006");
		assertThat(issue.getCode().getText()).isEqualTo("Nierfunctie: metformine");
		assertThat(issue.getCode().getCoding()).isEmpty();
	}

	/**
	 * Red, orange, green become high, moderate, low. Note that a green signal is a rule that fired
	 * and concluded no action is needed, not a rule that nearly fired.
	 */
	@Test
	void mapsTheAlertLevelOntoSeverity() {
		assertThat(issue(0).getSeverity()).isEqualTo(DetectedIssueSeverity.HIGH);
		assertThat(issue(1).getSeverity()).isEqualTo(DetectedIssueSeverity.LOW);
	}

	@Test
	void leavesSeverityAbsentWhenTheReportStatedNoLevel() {
		Bundle levelless = mapper.toBundle(new SurveillanceReport("CRID", "2.3", null, 1,
				List.of(new SurveillanceFinding("hub-dosage-control-5916", "1", null,
						"Doseringscontrole", null, "Geen doseringsaanpassing.", List.of()))));

		assertThat(((DetectedIssue) levelless.getEntryFirstRep().getResource()).hasSeverity()).isFalse();
	}

	@Test
	void carriesTheRuleTextAsTheDetail() {
		assertThat(issue(0).getDetail())
				.contains("Risico op lactaatacidose is verhoogd")
				.contains("1. aanvankelijk 500 mg metformine 2x per dag");
	}

	/** The codes a receiving system already knows, and the value that triggered the rule. */
	@Test
	void carriesWhatTheRuleReadAsEvidence() {
		DetectedIssue issue = issue(0);

		assertThat(issue.getEvidence()).hasSize(2);
		assertThat(issue.getEvidence().getFirst().getCodeFirstRep().getCoding())
				.extracting("system", "code")
				.containsExactly(
						tuple(Systems.PRK, "1090"),
						tuple(Systems.GPK, "3816"),
						tuple(Systems.HPK, "693332"),
						tuple(Systems.ATC, "A10BA02"));
		assertThat(issue.getEvidence().getLast().getCodeFirstRep().getText())
				.as("the value belongs with the determination it was measured for")
				.isEqualTo("kreatinineklaring: 35");
	}

	/**
	 * The reference back to the host's own record, by the id the host sent. This is what lets a
	 * signal be shown against the right row instead of matched on codes.
	 */
	@Test
	void referencesTheHostsOwnRecordsByTheIdTheySentWithThem() {
		DetectedIssue interaction = issue(2);

		assertThat(interaction.getImplicated())
				.extracting(reference -> reference.getType() + " " + reference.getIdentifier().getValue())
				.containsExactly("MedicationRequest 9064", "MedicationStatement 9066");
		assertThat(interaction.getImplicatedFirstRep().getDisplay())
				.isEqualTo("METFORMINE TABLET   500MG");
		assertThat(interaction.getImplicatedFirstRep().hasReference())
				.as("a logical reference: there is nothing on this server to resolve")
				.isFalse();
	}

	/** A lab result is evidence rather than the subject of the finding. */
	@Test
	void doesNotImplicateAnythingButMedication() {
		assertThat(issue(0).getImplicated()).hasSize(1);
	}

	@Test
	void returnsAnEmptyBundleForAReportThatFoundNothing() {
		Bundle allClear = mapper.toBundle(new MedicationSurveillanceResponseParser()
				.parse(Fixtures.hubXml("empty-report-response.xml")));

		assertThat(allClear.getEntry()).isEmpty();
		assertThat(allClear.getIdentifier().getValue()).isEqualTo("0EA8D2C1-0000-4448-A20E-EFEA660C7627");
	}

	/** Nothing claims a profile: none is published for this shape yet, deliberately. */
	@Test
	void assertsNoProfile() {
		assertThat(bundle.getMeta().getProfile()).isEmpty();
		assertThat(issue(0).getMeta().getProfile()).isEmpty();
	}

	@Test
	void mapsAnAllergySignalsContextToItsSubsystemCode() {
		Bundle allergy = mapper.toBundle(new SurveillanceReport("CRID", "2.3", LocalDateTime.now(), 1,
				List.of(new SurveillanceFinding("hub-allergy-snk-10499", "1", 1, "Allergie TALK", null,
						"Overgevoeligheid", List.of(new FindingContext(Kind.ALLERGY, "5470", "TALK",
								List.of(new CodedItem("SNK", "10499")), null))))));

		DetectedIssue issue = (DetectedIssue) allergy.getEntryFirstRep().getResource();
		assertThat(issue.getEvidenceFirstRep().getCodeFirstRep().getCodingFirstRep().getSystem())
				.isEqualTo(Systems.G_STANDAARD_SNK);
		assertThat(issue.getImplicated())
				.as("an allergy is evidence, not the medication the risk is in")
				.isEmpty();
	}

	private DetectedIssue issue(int index) {
		return (DetectedIssue) bundle.getEntry().get(index).getResource();
	}
}
