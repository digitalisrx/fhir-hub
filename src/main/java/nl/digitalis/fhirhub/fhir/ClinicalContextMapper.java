package nl.digitalis.fhirhub.fhir;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;

import org.hl7.fhir.r4.model.AllergyIntolerance;
import org.hl7.fhir.r4.model.CodeableConcept;
import org.hl7.fhir.r4.model.Coding;
import org.hl7.fhir.r4.model.Condition;
import org.hl7.fhir.r4.model.DateTimeType;
import org.hl7.fhir.r4.model.Dosage;
import org.hl7.fhir.r4.model.Extension;
import org.hl7.fhir.r4.model.MedicationRequest;
import org.hl7.fhir.r4.model.Enumerations.AdministrativeGender;
import org.hl7.fhir.r4.model.MedicationStatement;
import org.hl7.fhir.r4.model.Observation;
import org.hl7.fhir.r4.model.Patient;
import org.hl7.fhir.r4.model.Quantity;
import org.hl7.fhir.r4.model.StringType;
import org.springframework.stereotype.Component;

import ca.uhn.fhir.model.api.TemporalPrecisionEnum;
import ca.uhn.fhir.rest.server.exceptions.InvalidRequestException;
import nl.digitalis.fhirhub.fhir.LabDeterminations.Determination;
import nl.digitalis.fhirhub.model.CodedItem;
import nl.digitalis.fhirhub.model.LabResult;
import nl.digitalis.fhirhub.model.XisInfo;
import nl.digitalis.fhirhub.prescriptor.CodeSystemTokens;

/**
 * The patient context both contracts take, mapped once.
 *
 * <p>A session and a surveillance check ask different questions and send different payloads
 * upstream, but the dossier they carry is the same dossier and binds the same profiles: gender,
 * birth date, allergies, contra-indications, current medication, lab results, and the two strings
 * that identify the calling system. Every rule about them is safety-relevant — which
 * {@code Coding.system} is routed, which LOINC codes are read, which unit a value has to arrive
 * in, what happens to a gender FHIR has and Prescriptor does not — so they live here rather than
 * in each of {@link SessionParametersMapper} and {@link SurveillanceParametersMapper}. Two copies
 * of a fail-closed rule is one copy that will be softened by accident.
 *
 * <p>What is <em>not</em> here is anything either contract does alone: the ICPC reason for
 * encounter and the end-session URL of a session, the prescriptions and their dosing on a check.
 */
@Component
public class ClinicalContextMapper {

	private final CodeSystemRegistry codeSystems;

	private final LabDeterminations determinations;

	public ClinicalContextMapper(CodeSystemRegistry codeSystems, LabDeterminations determinations) {
		this.codeSystems = codeSystems;
		this.determinations = determinations;
	}

	/**
	 * Identifies the calling system. Required by both contracts, and never forwarded to either
	 * upstream — it exists so a log line can be attributed to a supplier and a release.
	 *
	 * <p>Two flat strings rather than one parameter with {@code id} and {@code version} parts:
	 * HAPI's binder never reads {@code part}, so the multi-part shape could not appear in the
	 * generated {@code OperationDefinition} at all.
	 */
	public XisInfo xis(StringType xisId, StringType xisVersion, String idName, String versionName) {
		return new XisInfo(requireNonBlank(xisId, idName), requireNonBlank(xisVersion, versionName));
	}

	public String requireNonBlank(StringType value, String name) {
		if (value == null || value.getValue() == null || value.getValue().isBlank()) {
			throw new InvalidRequestException(
					"Parameters.parameter:" + name + " is required and must be a non-blank string");
		}

		return value.getValue();
	}

	/**
	 * FHIR's administrative gender has four values; Prescriptor's {@code PatientGender} has three
	 * — {@code M}, {@code F} and {@code X} ("Unknown").
	 *
	 * <p>{@code unknown} maps onto {@code X} rather than being rejected: a host that genuinely
	 * does not know is stating a fact, and refusing the session would leave it unable to
	 * prescribe at all. Note the clinical consequence — sex-specific surveillance checks cannot
	 * fire on {@code X}, so a host that knows the sex must send it.
	 *
	 * <p>{@code other} and an absent gender are still rejected. {@code other} is not the same
	 * assertion as {@code unknown}, and there is no upstream value for it; an absent gender is a
	 * caller omission rather than a statement about the patient. Coercing either into {@code X}
	 * would put words in the host's mouth.
	 */
	public String gender(Patient patient) {
		AdministrativeGender gender = patient.getGender();
		if (gender == AdministrativeGender.MALE) {
			return "M";
		}
		if (gender == AdministrativeGender.FEMALE) {
			return "F";
		}
		if (gender == AdministrativeGender.UNKNOWN) {
			return "X";
		}

		throw new InvalidRequestException(
				"Patient.gender must be 'male', 'female' or 'unknown'; Prescriptor cannot interpret "
						+ (gender == null ? "an absent gender" : "'" + gender.toCode() + "'"));
	}

	public LocalDate birthDate(Patient patient) {
		if (!patient.hasBirthDate()) {
			throw new InvalidRequestException("Patient.birthDate is required");
		}

		return patient.getBirthDate().toInstant().atZone(ZoneId.systemDefault()).toLocalDate();
	}

	public List<CodedItem> allergies(List<AllergyIntolerance> allergies) {
		List<CodedItem> items = new ArrayList<>();
		int index = 0;
		for (AllergyIntolerance allergy : allergies) {
			index++;
			addCoded(items, allergy.getCode(), CodeSystemTokens.ALLERGY, "AllergyIntolerance.code",
					uid(allergy.getIdPart(), "allergyIntolerance", index));
		}

		return items;
	}

	/**
	 * The conditions, each carrying the token of the subsystem it was coded in — {@code CICode}
	 * for a G-Standaard contra-indication, {@code ICPC} for an ICPC-1 NL code. Both contracts read
	 * that token and route on it, and they route differently: a session puts them in two XML-RPC
	 * members, a check puts them in two elements of the DigitalisRx document.
	 */
	public List<CodedItem> conditions(List<Condition> conditions) {
		List<CodedItem> items = new ArrayList<>();
		int index = 0;
		for (Condition condition : conditions) {
			index++;
			addCoded(items, condition.getCode(), CodeSystemTokens.CONTRA_INDICATION, "Condition.code",
					uid(condition.getIdPart(), "condition", index));
		}

		return items;
	}

	public List<CodedItem> medications(List<MedicationStatement> statements) {
		List<CodedItem> items = new ArrayList<>();
		for (MedicationStatement statement : statements) {
			items.add(medication(statement));
		}

		return items;
	}

	/** One current-medication entry, as the code and the level the host coded it at. */
	public CodedItem medication(MedicationStatement statement) {
		List<CodedItem> items = new ArrayList<>();
		if (statement.getMedication() instanceof CodeableConcept concept) {
			addCoded(items, concept, CodeSystemTokens.MEDICATION,
					"MedicationStatement.medicationCodeableConcept",
					uid(statement.getIdPart(), "medicationStatement", 1));
		}

		if (items.isEmpty()) {
			throw new InvalidRequestException(
					"MedicationStatement.medicationCodeableConcept is required and must carry a PRK or"
							+ " HPK coding; expected one of "
							+ codeSystems.systemsFor(CodeSystemTokens.MEDICATION));
		}

		return items.getFirst();
	}

	private void addCoded(List<CodedItem> items, CodeableConcept concept, Set<String> allowed,
			String path, String uid) {
		if (concept == null || concept.getCoding().isEmpty()) {
			throw new InvalidRequestException(path + " requires at least one coding");
		}

		for (Coding coding : concept.getCoding()) {
			String token = codeSystems.tokenFor(coding.getSystem());
			if (token != null && allowed.contains(token)) {
				items.add(new CodedItem(token, coding.getCode(), caption(concept, coding, token), uid));

				return;
			}
		}

		// The accepted system URIs, not the upstream tokens: the tokens are exactly the strings
		// a caller must not put in Coding.system, so naming them here would misdirect.
		throw new InvalidRequestException(
				path + " has no coding in a system this interface routes; expected one of "
						+ codeSystems.systemsFor(allowed));
	}

	/**
	 * The NHG Tabel 25 coded instruction. Taken from the CodedDirections extension when the
	 * host round-trips a prescription this interface produced, and otherwise from Dosage.text.
	 *
	 * <p>The extension wins over {@code Dosage.text}, which is the human-readable form beside
	 * it: reading the text in preference would downgrade the dosing silently rather than
	 * visibly, and that is the failure this interface is built to avoid.
	 */
	public String codedDirections(MedicationRequest prescription) {
		for (Dosage dosage : prescription.getDosageInstruction()) {
			Extension coded = dosage.getExtensionByUrl(DigitalisExtensions.CODED_DIRECTIONS);
			if (coded != null && coded.getValue() != null) {
				return coded.getValue().primitiveValue();
			}
		}

		return prescription.getDosageInstructionFirstRep().getText();
	}

	/**
	 * The host's own wording for a coded item, and never blank.
	 *
	 * <p>{@code Coding.display} first, then the concept's {@code text}, then the code itself,
	 * because the surveillance upstream writes this string into the middle of a sentence it shows
	 * a prescriber: "In het dossier is een allergie (PENICILLINES) geregistreerd". The fallback is
	 * ugly and says something; blank says nothing, which is what the live service demonstrated
	 * before this method existed.
	 */
	private String caption(CodeableConcept concept, Coding coding, String token) {
		if (coding.hasDisplay() && !coding.getDisplay().isBlank()) {
			return coding.getDisplay();
		}

		if (concept.hasText() && !concept.getText().isBlank()) {
			return concept.getText();
		}

		return token + " " + coding.getCode();
	}

	/**
	 * The host's own id for a record, or its position in the request when it sent none — so the
	 * attribute is never empty and two entries never share a value.
	 */
	private String uid(String id, String kind, int index) {
		return id == null || id.isBlank() ? kind + "-" + index : id;
	}

	/**
	 * Maps lab Observations onto the determinations medication surveillance reads.
	 *
	 * <p>A host sends a LOINC code and the upstream tests that same code, so nothing is translated;
	 * {@link LabDeterminations} says which codes a rule can read and in which unit. A code outside
	 * that list is refused rather than forwarded, because forwarding it would leave the prescriber
	 * believing a value had been weighed when nothing read it — the same false all-clear an
	 * unresolvable drug code is refused for.
	 */
	public List<LabResult> laboratoryData(List<Observation> observations) {
		List<LabResult> results = new ArrayList<>();
		int index = 0;
		for (Observation observation : observations) {
			index++;
			Coding coding = firstCodingForSystem(observation.getCode(), Systems.LOINC);
			if (coding == null) {
				throw new InvalidRequestException(
						"Observation.code requires a coding in " + Systems.LOINC
								+ "; the determinations medication surveillance reads are "
								+ determinations.acceptedCodes());
			}

			Determination determination = determinations.forLoinc(coding.getCode());
			if (determination == null) {
				throw new InvalidRequestException(
						"LOINC code '" + coding.getCode() + "' is not a determination medication"
								+ " surveillance reads, so sending it would suggest it had been"
								+ " weighed. Accepted: " + determinations.acceptedCodes());
			}

			Effective effective = effective(observation);
			results.add(new LabResult(
					determination.loinc(),
					coding.hasDisplay() ? coding.getDisplay() : determination.display(),
					determination.unit(),
					effective.date(),
					effective.time(),
					valueIn(observation, determination),
					uid(observation.getIdPart(), "observation", index)));
		}

		return results;
	}

	private Coding firstCodingForSystem(CodeableConcept concept, String system) {
		if (concept == null) {
			return null;
		}

		for (Coding coding : concept.getCoding()) {
			if (system.equals(coding.getSystem())) {
				return coding;
			}
		}

		return null;
	}

	/**
	 * When the sample was taken, to the precision the host stated it in.
	 *
	 * <p>The time of day is kept rather than truncated away. Several results for one determination
	 * are resolved upstream by taking the most recent — {@code TProtocolParserDataLOINC.GetValueExt}
	 * in the rules engine — and that comparison reads the {@code date} attribute alone. Sending a
	 * date-only value puts every result of one day at midnight, and the engine's most-recent scan
	 * keeps the first of a tie, so this morning's eGFR would beat this afternoon's whenever the host
	 * listed it first. The host had the time; dropping it here is what loses the ordering.
	 *
	 * <p>A host that states only a date still gets a date: it is a claim about precision, and
	 * inventing a midnight it did not send would be a different claim.
	 */
	private Effective effective(Observation observation) {
		if (!observation.hasEffectiveDateTimeType()) {
			throw new InvalidRequestException("Observation.effectiveDateTime is required");
		}

		DateTimeType effective = observation.getEffectiveDateTimeType();
		ZonedDateTime moment = effective.getValue().toInstant().atZone(ZoneId.systemDefault());
		boolean statedTime = effective.getPrecision() != null
				&& effective.getPrecision().ordinal() >= TemporalPrecisionEnum.MINUTE.ordinal();

		// Seconds are the finest the upstream parses; anything below is dropped rather than rounded.
		return new Effective(moment.toLocalDate(),
				statedTime ? moment.toLocalTime().withNano(0) : null);
	}

	/** {@code effectiveDateTime}, split into the two attributes the upstream document carries. */
	private record Effective(LocalDate date, LocalTime time) {
	}

	/**
	 * The result value, in the unit the rules evaluate in.
	 *
	 * <p>The upstream carries no unit, so the number has to be right on arrival: a kalium in mg/dL
	 * rather than mmol/L is a different answer, not a rounded one, and nothing downstream could
	 * notice. Hence a {@code Quantity} with a UCUM code the determination accepts, converted where
	 * the conversion is exact, and a refusal otherwise.
	 */
	private String valueIn(Observation observation, Determination determination) {
		if (!(observation.getValue() instanceof Quantity quantity) || !quantity.hasValue()) {
			throw new InvalidRequestException(
					"Observation.valueQuantity is required for " + determination.loinc() + " ("
							+ determination.display() + "), in " + determination.acceptedUnits());
		}

		String unit = quantity.hasCode() ? quantity.getCode() : quantity.getUnit();
		BigDecimal converted = determination.toUpstreamUnit(unit, quantity.getValue());
		if (converted == null) {
			throw new InvalidRequestException(
					"Observation.valueQuantity for " + determination.loinc() + " ("
							+ determination.display() + ") must be in " + determination.acceptedUnits()
							+ " as a UCUM code, not '" + unit + "': the upstream carries no unit, so"
							+ " the value is evaluated as " + determination.unit());
		}

		return converted.stripTrailingZeros().toPlainString();
	}
}
