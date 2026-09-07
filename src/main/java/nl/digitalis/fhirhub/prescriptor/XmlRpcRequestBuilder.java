package nl.digitalis.fhirhub.prescriptor;

import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.Map;
import java.util.function.Consumer;

import org.springframework.stereotype.Component;

import nl.digitalis.fhirhub.model.CodedItem;
import nl.digitalis.fhirhub.model.DrugCode;
import nl.digitalis.fhirhub.model.ExistingPrescription;
import nl.digitalis.fhirhub.model.MedicationCodes;
import nl.digitalis.fhirhub.model.PatientContext;
import nl.digitalis.fhirhub.model.PrescriptorCredentials;
import nl.digitalis.fhirhub.model.SessionRequest;
import nl.digitalis.fhirhub.xml.XmlWriter;

/** Builds the XML-RPC method calls Prescriptor expects. */
@Component
public class XmlRpcRequestBuilder {

	private static final DateTimeFormatter BIRTHDATE = DateTimeFormatter.ofPattern("yyyyMMdd'T12:00:00'");

	/**
	 * MedicationType 9: the whole current-medication list is read at PRK level. See
	 * {@link #medicationType}.
	 */
	private static final String MEDICATION_TYPE_PRK = "9";

	/**
	 * PrescriptionType for the single prescription CreateRx opens for editing, keyed by the level
	 * the host coded it at. Unlike {@code MedicationType} this one does describe the data: it
	 * names which of the {@code DrugCodePRK} / {@code DrugCodeHPK} members the upstream should
	 * read, and both are written when the host supplied both.
	 */
	private static final Map<String, String> PRESCRIPTION_TYPES = Map.of(
			CodeSystemTokens.HPK, "7",
			CodeSystemTokens.PRK, "9");

	/** MedicationType when the patient uses nothing, so there is no level to declare. */
	private static final String NO_MEDICATION = "0";

	public String openSession(SessionRequest request,
			PrescriptorCredentials credentials,
			List<MedicationCodes> medication) {
		PatientContext patient = request.patient();
		String presPlus = DigitalisRxBuilder.build(patient, medication, credentials);

		return methodCall(request.type().methodName(), struct -> {
			struct.element("member", m -> m
					.text("name", "BirthDate")
					.element("value", v -> v.text("dateTime.iso8601", patient.dateOfBirth().format(BIRTHDATE))));

			stringMember(struct, "PatientGender", patient.gender());
			stringMember(struct, "UserNO", "default_user");

			if (request.icpc() != null && !request.icpc().isBlank()) {
				stringMember(struct, "SearchKey", request.icpc());
			}

			// The level the whole current-medication list is read at. One value covers the list
			// upstream, so it cannot follow the individual entries; see medicationType().
			intMember(struct, "MedicationType", medicationType(patient));
			booleanMember(struct, "MedicationCheck", true);
			stringMember(struct, "PracticeID", credentials.practiceId());
			stringMember(struct, "LicenseKey", credentials.licenseKey());

			struct.element("member", m -> m
					.text("name", "PresPlus")
					.element("value", v -> v.element("string", s -> s.cdata(presPlus))));

			stringMember(struct, "ExceptionURL", "");
			stringMember(struct, "SessionEndURL", request.endSessionUrl());
			stringMember(struct, "HostAppSession", "");
			intMember(struct, "UseSSL", "0");
			intMember(struct, "ReturnZindexNumbers", "0");

			// Allergies split by subsystem, one member each. The receiving side reads all
			// three — see prescriptor-api's OpenSessionRequestBuilder.getAllergies, which maps
			// Allergies→OGGRP, AlStam→SNK, AlStof→SSK. The JSON interface accepted SSK codes
			// and then sent AlStof empty, so they never reached allergy checking; that is
			// corrected here rather than carried over.
			codeArrayMember(struct, "Allergies", patient.allergies(), CodeSystemTokens.OGGRP);
			codeArrayMember(struct, "AlStam", patient.allergies(), CodeSystemTokens.SNK);
			codeArrayMember(struct, "AlStof", patient.allergies(), CodeSystemTokens.SSK);

			codeArrayMember(struct, "Contraindications_ICPC", patient.contraIndications(), CodeSystemTokens.ICPC);
			codeArrayMember(struct, "Contraindications_Z_Index", patient.contraIndications(), CodeSystemTokens.CI_CODE);

			if (request.prescription() != null) {
				prescriptionMember(struct, request.prescription());
			}

			struct.element("member", m -> m
					.text("name", "Authorization")
					.element("value", v -> v.element("struct", inner -> {
						booleanMember(inner, "Eigenkeuze", true);
						booleanMember(inner, "Filterinstellingen", true);
						booleanMember(inner, "FormulariaVoorkeuren", true);
					})));
		});
	}

	public String requestResult(String sessionId) {
		return methodCall("requestResult", struct -> stringMember(struct, "PrescriptorSessionKey", sessionId));
	}

	private String methodCall(String methodName, Consumer<XmlWriter> members) {
		return XmlWriter.document(xml -> xml.element("methodCall", call -> {
			call.text("methodName", methodName);
			call.element("params", params -> params
					.element("param", param -> param
							.element("value", value -> value
									.element("struct", members))));
		}));
	}

	/**
	 * The level Prescriptor reads the current-medication list at — one value for the whole list,
	 * and PRK for every list that has anything in it.
	 *
	 * <p>Upstream, this is not a description of the codes but an instruction: the open-session
	 * handler switches on it once and then reads <em>that one attribute</em> off every
	 * {@code <drug>} element, dropping any drug where the attribute is absent — without an error,
	 * because an empty code is skipped rather than rejected. So a value derived from the host's
	 * entries would make a mixed PRK/HPK list unsafe: announce HPK, and every PRK-coded entry
	 * disappears from medication surveillance silently, which is the failure
	 * {@code MedicationCodeResolver} refuses to allow for an unresolvable code.
	 *
	 * <p>A constant is safe here precisely because of that resolver: every entry is resolved to a
	 * PRK + GPK pair before this runs, so PRK is present on every drug fhir-hub sends whatever
	 * level the host used. Hosts may mix levels per entry freely, which is the point.
	 *
	 * <p>Two things would invalidate the constant: dropping the enrichment in
	 * {@code MedicationCodeResolver}, or a requirement that surveillance run at HPK level. The
	 * {@code HPK} attribute is still written when the host supplied one — see
	 * {@code DigitalisRxBuilder} — so the second would be a change here rather than to the data.
	 */
	private String medicationType(PatientContext patient) {
		return patient.medications().isEmpty() ? NO_MEDICATION : MEDICATION_TYPE_PRK;
	}

	/**
	 * An existing prescription handed to CreateRx for editing.
	 *
	 * <p>PrescriptionType follows the code level supplied, preferring PRK when the host gives
	 * both — the same precedence the JSON interface applies.
	 */
	private void prescriptionMember(XmlWriter struct, ExistingPrescription prescription) {
		DrugCode prk = codeOfType(prescription, CodeSystemTokens.PRK);
		DrugCode hpk = codeOfType(prescription, CodeSystemTokens.HPK);

		struct.element("member", m -> m
				.text("name", "Prescription")
				.element("value", v -> v.element("struct", inner -> {
					if (hpk != null) {
						stringMember(inner, "DrugCodeHPK", String.valueOf(hpk.value()));
						stringMember(inner, "DrugNameHPK", hpk.description());
					}
					if (prk != null) {
						stringMember(inner, "DrugCodePRK", String.valueOf(prk.value()));
						stringMember(inner, "DrugNamePRK", prk.description());
					}

					stringMember(inner, "CodedDirection", prescription.directions());
					stringMember(inner, "SupplyUnit", prescription.unit());
					stringMember(inner, "ATC", prescription.atc() == null ? "" : prescription.atc());

					inner.element("member", member -> member
							.text("name", "NumbersSupplied")
							.element("value", value -> value.text("double",
									prescription.quantity() == null
											? "0"
											: prescription.quantity().toPlainString())));

					DrugCode preferred = prk != null ? prk : hpk;
					if (preferred != null) {
						intMember(inner, "PrescriptionType", PRESCRIPTION_TYPES.get(preferred.type()));
					}
				})));
	}

	private DrugCode codeOfType(ExistingPrescription prescription, String type) {
		return prescription.codes().stream()
				.filter(code -> type.equals(code.type()))
				.findFirst()
				.orElse(null);
	}

	private void stringMember(XmlWriter struct, String name, String value) {
		struct.element("member", m -> m
				.text("name", name)
				.element("value", v -> v.text("string", value)));
	}

	private void intMember(XmlWriter struct, String name, String value) {
		struct.element("member", m -> m
				.text("name", name)
				.element("value", v -> v.text("int", value)));
	}

	private void booleanMember(XmlWriter struct, String name, boolean value) {
		struct.element("member", m -> m
				.text("name", name)
				.element("value", v -> v.text("boolean", value ? "1" : "0")));
	}

	private void codeArrayMember(XmlWriter struct, String name, List<CodedItem> items, String codeSystem) {
		struct.element("member", m -> m
				.text("name", name)
				.element("value", v -> v.element("array", array -> array.element("data", data -> {
					for (CodedItem item : items) {
						if (codeSystem.equals(item.codeSystem())) {
							data.element("value", value -> value.text("string", item.code()));
						}
					}
				}))));
	}
}
