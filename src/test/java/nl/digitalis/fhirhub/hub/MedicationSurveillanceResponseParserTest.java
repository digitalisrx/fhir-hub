package nl.digitalis.fhirhub.hub;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.groups.Tuple.tuple;

import java.time.LocalDateTime;

import org.junit.jupiter.api.Test;

import ca.uhn.fhir.rest.server.exceptions.InternalErrorException;
import nl.digitalis.fhirhub.Fixtures;
import nl.digitalis.fhirhub.model.FindingContext;
import nl.digitalis.fhirhub.model.FindingContext.Kind;
import nl.digitalis.fhirhub.model.SurveillanceFinding;
import nl.digitalis.fhirhub.model.SurveillanceReport;

/**
 * Reads the published example response, and pins the four ways a check can fail to be a check.
 *
 * <p>The fixture is the response Digitalis documented for the request in
 * {@code DigitalisRx-documentation/}: metformine prescribed to a pregnant patient with an eGFR of
 * 35 who also takes omeprazol, and three rules fire on it.
 */
class MedicationSurveillanceResponseParserTest {

	private final MedicationSurveillanceResponseParser parser = new MedicationSurveillanceResponseParser();

	@Test
	void readsTheReportMetadataSupportAsksFor() {
		SurveillanceReport report = parse("medication-surveillance-response.xml");

		assertThat(report.crid()).isEqualTo("83327A6E-FAED-4448-A20E-EFEA660C7627");
		assertThat(report.serviceVersion()).isEqualTo("2.3.20260509.1129");
		assertThat(report.reportedAt()).isEqualTo(LocalDateTime.of(2026, 9, 7, 11, 11, 16));
		assertThat(report.activatedRules()).isEqualTo(27);
	}

	@Test
	void readsEveryRuleThatFired() {
		SurveillanceReport report = parse("medication-surveillance-response.xml");

		assertThat(report.findings())
				.extracting(SurveillanceFinding::ruleId, SurveillanceFinding::alertLevel,
						SurveillanceFinding::displayTitle)
				.containsExactly(
						tuple("MFB-0000000068-v000006", 1, "Nierfunctie: metformine"),
						tuple("MFB-0000002086-v000001", 3, "Zwangerschap: metformine"),
						tuple("MFB-0000008504-v000001", 3,
								"Metformine + Remmers/Inductoren (actie Nee)"));
	}

	/**
	 * The rule's text, flattened to lines, with the numbered dosing steps still numbered — that
	 * order is the advice, not decoration.
	 */
	@Test
	void flattensTheRuleTextAndKeepsTheOrderOfTheSteps() {
		SurveillanceFinding finding = parse("medication-surveillance-response.xml").findings().getFirst();

		assertThat(finding.text())
				.startsWith("Risico op lactaatacidose is verhoogd.")
				.contains("1. aanvankelijk 500 mg metformine 2x per dag")
				.contains("2. vervolgens dosering geleidelijk verhogen");
	}

	/**
	 * The rules engine writes its text HTML-escaped into an XML text node, so {@code ë} arrives as
	 * the six characters {@code &#235;}. Left alone, a prescriber reads "Pati&#235;nt heeft".
	 */
	@Test
	void decodesTheCharacterReferencesTheEngineDoubleEscapes() {
		SurveillanceFinding finding = parse("medication-surveillance-response.xml").findings().getFirst();

		assertThat(finding.text()).contains("Patiënt heeft creatinineklaring 30-60 ml/min");
		assertThat(finding.text()).doesNotContain("&#235;");
	}

	/** What the rule was looking at, which is what makes a signal actionable. */
	@Test
	void readsTheContextTheRuleReadIncludingTheHostsOwnRecordIds() {
		SurveillanceFinding nierfunctie = parse("medication-surveillance-response.xml").findings().getFirst();

		assertThat(nierfunctie.context())
				.extracting(FindingContext::kind, FindingContext::uid, FindingContext::value)
				.containsExactly(
						tuple(Kind.PROPOSED_DRUG, "9064", null),
						tuple(Kind.LAB_RESULT, "1667", "35"));

		FindingContext drug = nierfunctie.context().getFirst();
		assertThat(drug.caption()).isEqualTo("METFORMINE TABLET   500MG");
		assertThat(drug.codes()).extracting("codeSystem", "code").containsExactly(
				tuple("PRK", "1090"),
				tuple("GPK", "3816"),
				tuple("HPK", "693332"),
				tuple("ATC", "A10BA02"));
	}

	/**
	 * The interaction rule's context carries two drugs, and only one of them is the one being
	 * prescribed. A host has to be able to tell them apart to show the signal against the right
	 * row, and the {@code trigger} attribute this interface sent is what says so.
	 */
	@Test
	void tellsTheProposedDrugFromTheStandingDossier() {
		SurveillanceFinding interaction = parse("medication-surveillance-response.xml").findings().getLast();

		assertThat(interaction.context())
				.extracting(FindingContext::kind, FindingContext::caption)
				.containsExactly(
						tuple(Kind.PROPOSED_DRUG, "METFORMINE TABLET   500MG"),
						tuple(Kind.CURRENT_DRUG, "OMEPRAZOL CAPSULE MSR 20MG"));
	}

	@Test
	void readsAContraIndicationFromTheContext() {
		SurveillanceFinding zwangerschap = parse("medication-surveillance-response.xml").findings().get(1);

		assertThat(zwangerschap.context())
				.filteredOn(context -> context.kind() == Kind.CONTRA_INDICATION)
				.singleElement()
				.satisfies(context -> {
					assertThat(context.caption()).isEqualTo("ZWANGERSCHAP");
					assertThat(context.uid()).isEqualTo("5468");
					assertThat(context.codes()).extracting("code").containsExactly("1320");
				});
	}

	/** A report that ran and found nothing is the one empty answer that may be returned. */
	@Test
	void readsAnAllClearAsAReportWithNoFindings() {
		SurveillanceReport report = parse("empty-report-response.xml");

		assertThat(report.findings()).isEmpty();
		assertThat(report.crid()).isEqualTo("0EA8D2C1-0000-4448-A20E-EFEA660C7627");
	}

	/**
	 * And a response with no report is not that. The Hub logs a failure of the rules engine or of
	 * its own G-Standaard checks rather than raising, so this is a real shape — and it is the one
	 * that must never be mistaken for an all-clear.
	 */
	@Test
	void refusesAResponseWithNoReportInIt() {
		assertThatThrownBy(() -> parse("no-report-response.xml"))
				.isInstanceOf(InternalErrorException.class)
				.hasMessageContaining("no report")
				.hasMessageContaining("no conclusion may be drawn");
	}

	@Test
	void refusesASoapFault() {
		assertThatThrownBy(() -> parse("fault-response.xml"))
				.isInstanceOf(InternalErrorException.class)
				.hasMessageContaining("could not be run")
				// The upstream fault string is logged, not returned: it is written for us and can
				// carry a stack trace.
				.hasMessageNotContaining("DigitalisRx.xml is empty");
	}

	@Test
	void refusesABodyThatIsNotXmlAtAll() {
		assertThatThrownBy(() -> parser.parse("<html><body>Server Error (500)</body>"))
				.isInstanceOf(InternalErrorException.class)
				.hasMessageContaining("unreadable");

		assertThatThrownBy(() -> parser.parse(""))
				.isInstanceOf(InternalErrorException.class)
				.hasMessageContaining("empty");
	}

	/**
	 * An entity reference in the response body is not expanded. The upstream is trusted, which is
	 * exactly the assumption that turns a misconfigured or compromised service into a local file
	 * read on this side of the network.
	 */
	@Test
	void doesNotResolveEntitiesInTheResponse() {
		String withDoctype = "<?xml version=\"1.0\"?><!DOCTYPE r [<!ENTITY x SYSTEM \"file:///etc/passwd\">]>"
				+ "<Envelope><Body><report activatedRules=\"0\">&x;</report></Body></Envelope>";

		assertThatThrownBy(() -> parser.parse(withDoctype))
				.isInstanceOf(InternalErrorException.class)
				.hasMessageContaining("unreadable");
	}

	private SurveillanceReport parse(String fixture) {
		return parser.parse(Fixtures.hubXml(fixture));
	}
}
