package nl.digitalis.fhirhub.fhir;

import java.time.ZoneId;
import java.util.Optional;
import java.util.Date;
import java.util.UUID;

import org.hl7.fhir.r4.model.Bundle;
import org.hl7.fhir.r4.model.Bundle.BundleType;
import org.hl7.fhir.r4.model.CodeableConcept;
import org.hl7.fhir.r4.model.Coding;
import org.hl7.fhir.r4.model.DateTimeType;
import org.hl7.fhir.r4.model.DetectedIssue;
import org.hl7.fhir.r4.model.DetectedIssue.DetectedIssueEvidenceComponent;
import org.hl7.fhir.r4.model.DetectedIssue.DetectedIssueSeverity;
import org.hl7.fhir.r4.model.DetectedIssue.DetectedIssueStatus;
import org.hl7.fhir.r4.model.Identifier;
import org.hl7.fhir.r4.model.Reference;
import org.springframework.stereotype.Component;

import nl.digitalis.fhirhub.model.CodedItem;
import nl.digitalis.fhirhub.model.FindingContext;
import nl.digitalis.fhirhub.model.SurveillanceFinding;
import nl.digitalis.fhirhub.model.SurveillanceReport;

/**
 * Maps a clinical-rules report onto the Bundle {@code $check-medication-request} returns: one
 * {@code DetectedIssue} per signal, in the order the report listed them.
 *
 * <p><strong>An empty Bundle means no signal fired</strong>, and it can only be reached by a
 * report that ran: {@code MedicationSurveillanceResponseParser} refuses a response with no report
 * in it rather than passing an empty one on. That is the invariant the whole contract rests on,
 * and it is enforced there, not here.
 *
 * <h2>The published profile is the other half of this class</h2>
 * {@code fhirhub-SurveillanceBundle} and {@code fhirhub-SurveillanceFinding}
 * ({@code ig/input/fsh/profiles-surveillance.fsh}) describe what this mapper emits, element for
 * element: the fixed {@code collection} type and {@code final} status, the two identifier systems,
 * {@code code} with text and no coding, {@code detail} as plain text, and {@code implicated} as a
 * logical reference. They are a promise to integrators, so a change here that moves a cardinality
 * has to move the profile with it —
 * {@code OutboundPayloadConformanceTest.theSurveillanceBundleSatisfiesItsProfile} fails when the
 * two disagree.
 *
 * <p>Nothing is asserted in {@code meta.profile}, even though the Bundle validates clean against
 * it: the Implementation Guide tells integrators not to route on {@code meta.profile}, and that
 * sentence and this decision have to move together.
 */
@Component
public class SurveillanceBundleMapper {

	private final CodeSystemRegistry codeSystems;

	public SurveillanceBundleMapper(CodeSystemRegistry codeSystems) {
		this.codeSystems = codeSystems;
	}

	/**
	 * What the host's own resource was for the drugs this check had under test.
	 *
	 * <p>The report echoes those drugs back marked {@code pending}/{@code trigger}, so
	 * {@code MedicationSurveillanceResponseParser} reports them as
	 * {@link FindingContext.Kind#PROPOSED_DRUG} whichever operation asked — the marking is how the
	 * upstream is told what to examine and carries no news about which FHIR resource the host sent.
	 * Only the operation knows that, so it says: {@code $check-medication-request} put
	 * {@code MedicationRequest}s under test and {@code $check-medication-statement} put
	 * {@code MedicationStatement}s. Getting it wrong points every signal at a resource type the
	 * host never sent, which is a reference it cannot resolve rather than a visible error — hence
	 * no default.
	 */
	public enum DrugsUnderTest {

		MEDICATION_REQUEST("MedicationRequest"),

		MEDICATION_STATEMENT("MedicationStatement");

		private final String resourceType;

		DrugsUnderTest(String resourceType) {
			this.resourceType = resourceType;
		}

		String resourceType() {
			return resourceType;
		}
	}

	public Bundle toBundle(SurveillanceReport report, DrugsUnderTest underTest) {
		Bundle bundle = new Bundle();
		bundle.setType(BundleType.COLLECTION);

		// The report id, which is the one thing that ties this answer to the run that produced it:
		// a prescriber querying a signal quotes it and support finds the run by it.
		if (report.crid() != null) {
			bundle.setIdentifier(new Identifier().setSystem(Systems.CRS_REPORT).setValue(report.crid()));
		}

		if (report.reportedAt() != null) {
			bundle.setTimestamp(toDate(report));
		}

		for (SurveillanceFinding finding : report.findings()) {
			bundle.addEntry()
					// No server identity to give these: this service stores nothing and there is
					// no endpoint to fetch a finding back from. urn:uuid is the FHIR idiom for
					// exactly that, and it identifies the entry within this Bundle and nowhere
					// else — the stable handle is DetectedIssue.identifier, which is the rule.
					.setFullUrl("urn:uuid:" + UUID.randomUUID())
					.setResource(toDetectedIssue(finding, report, underTest));
		}

		return bundle;
	}

	private DetectedIssue toDetectedIssue(SurveillanceFinding finding, SurveillanceReport report,
			DrugsUnderTest underTest) {
		DetectedIssue issue = new DetectedIssue();
		issue.setStatus(DetectedIssueStatus.FINAL);

		if (finding.ruleId() != null) {
			issue.addIdentifier(new Identifier().setSystem(Systems.CRS_RULE).setValue(finding.ruleId()));
		}

		// code carries the title and no coding, because there is nothing honest to code it with:
		// the rule identifier is an identifier and FHIR's own DetectedIssue categories (drug
		// interaction, duplicate therapy, ...) are a classification the upstream does not make
		// and this interface would have to guess at from a rule id. text is what a prescriber
		// reads; identifier is what a system routes on.
		issue.setCode(new CodeableConcept().setText(finding.displayTitle()));

		severity(finding).ifPresent(issue::setSeverity);

		if (finding.text() != null) {
			issue.setDetail(finding.text());
		}

		if (report.reportedAt() != null) {
			issue.setIdentified(new DateTimeType(toDate(report)));
		}

		for (FindingContext context : finding.context()) {
			evidence(issue, context);
			implicated(issue, context, underTest);
		}

		return issue;
	}

	/**
	 * 1 red, 2 orange, 3 green upstream; {@code high}, {@code moderate} and {@code low} in FHIR,
	 * which is the whole of what {@code DetectedIssue.severity} offers.
	 *
	 * <p>Note what green means here and what it does not: level 3 is a rule that fired and
	 * concluded that no action is needed — "Dit is GEEN contra-indicatie", "Bij deze interactie is
	 * GEEN actie nodig" — so it is a finding with a reassuring text, not a near-miss. A host that
	 * hides {@code low} hides the answer to a question the prescriber's own dossier raised.
	 *
	 * <p>An absent level stays absent. {@code severity} is 0..1 in FHIR, and a rule that reported
	 * no level is not the same as a rule that reported the mildest one.
	 */
	private Optional<DetectedIssueSeverity> severity(SurveillanceFinding finding) {
		if (finding.alertLevel() == null) {
			return Optional.empty();
		}

		return Optional.of(switch (finding.alertLevel()) {
			case 1 -> DetectedIssueSeverity.HIGH;
			case 2 -> DetectedIssueSeverity.MODERATE;
			default -> DetectedIssueSeverity.LOW;
		});
	}

	/**
	 * What the rule read, as codes: the drug's PRK, GPK, HPK and ATC together in one
	 * {@code CodeableConcept}, a lab result's LOINC code with the value it carried, a
	 * contra-indication's CICode.
	 *
	 * <p>{@code evidence.code} rather than {@code evidence.detail} alone, because the codes are
	 * the part a receiving system can act on without resolving anything: it already knows which
	 * PRK it sent.
	 */
	private void evidence(DetectedIssue issue, FindingContext context) {
		if (context.codes().isEmpty() && context.caption() == null) {
			return;
		}

		CodeableConcept concept = new CodeableConcept();
		for (CodedItem code : context.codes()) {
			concept.addCoding(new Coding()
					.setSystem(codeSystems.systemFor(code.codeSystem()))
					.setCode(code.code()));
		}

		// The value belongs with the code it was measured for: "eGFR 35" is the evidence, and
		// "eGFR" on its own is only the parameter.
		String text = context.value() == null
				? context.caption()
				: (context.caption() == null ? context.value() : context.caption() + ": " + context.value());

		if (text != null) {
			concept.setText(text);
		}

		DetectedIssueEvidenceComponent evidence = issue.addEvidence();
		evidence.addCode(concept);
	}

	/**
	 * The host's own record, referenced by the id it sent.
	 *
	 * <p>A logical reference — an identifier and a type, with no URL — because there is nothing to
	 * resolve: the resources came in on the request and live in the host's system, not here. This
	 * is what lets a host highlight the row a signal is about instead of matching on codes, and it
	 * is why {@code SurveillanceParametersMapper} sends the resource {@code id} upstream as the
	 * {@code UID}.
	 *
	 * <p>Only drugs get one. A lab result, a contra-indication and an allergy carry a UID too, but
	 * {@code DetectedIssue.implicated} is defined as the resources "implicated in the detected
	 * risk" — the medication, in other words — and the rest is evidence rather than the subject of
	 * the finding.
	 */
	private void implicated(DetectedIssue issue, FindingContext context, DrugsUnderTest underTest) {
		if (context.uid() == null) {
			return;
		}

		// A drug that was under test is whatever resource the operation took; one that was context
		// is always a MedicationStatement, because that is the only parameter a dossier arrives in.
		String type = switch (context.kind()) {
			case PROPOSED_DRUG -> underTest.resourceType();
			case CURRENT_DRUG -> "MedicationStatement";
			default -> null;
		};

		if (type == null) {
			return;
		}

		Reference reference = new Reference()
				.setType(type)
				.setIdentifier(new Identifier().setValue(context.uid()));

		if (context.caption() != null) {
			reference.setDisplay(context.caption());
		}

		issue.addImplicated(reference);
	}

	private Date toDate(SurveillanceReport report) {
		return Date.from(report.reportedAt().atZone(ZoneId.systemDefault()).toInstant());
	}
}
