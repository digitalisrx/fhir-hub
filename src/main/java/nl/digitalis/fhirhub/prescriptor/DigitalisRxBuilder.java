package nl.digitalis.fhirhub.prescriptor;

import java.time.format.DateTimeFormatter;

import java.util.List;

import nl.digitalis.fhirhub.model.CodedItem;
import nl.digitalis.fhirhub.model.LabResult;
import nl.digitalis.fhirhub.model.MedicationCodes;
import nl.digitalis.fhirhub.model.PatientContext;
import nl.digitalis.fhirhub.model.PrescriptorCredentials;
import nl.digitalis.fhirhub.xml.XmlWriter;

/**
 * Builds the inner {@code <DigitalisRx>} document that travels inside the PresPlus member.
 *
 * <p>This is a second, complete XML document embedded in a CDATA section of the first — the
 * shape Prescriptor expects, preserved from the JSON interface.
 */
final class DigitalisRxBuilder {

	private static final DateTimeFormatter ISO_DATE = DateTimeFormatter.ISO_LOCAL_DATE;

	/**
	 * Seconds always, because the engine branches on the string's length: {@code StringToDate} in
	 * {@code uCREFormatSettings.pas} treats exactly ten characters as a date and everything else as
	 * {@code yyyy-mm-ddThh:mm:ss}. {@link DateTimeFormatter#ISO_LOCAL_DATE_TIME} would omit
	 * zero seconds and produce neither.
	 */
	private static final DateTimeFormatter UPSTREAM_MOMENT =
			DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss");

	private DigitalisRxBuilder() {
	}

	static String build(PatientContext patient,
			List<MedicationCodes> medication,
			PrescriptorCredentials credentials) {
		return XmlWriter.document(xml -> xml.element("DigitalisRx", root -> {
			root.empty("schemaVersion").attribute("id", "id0");
			// Populated since v2 of the contract; the JSON interface sent this element empty
			// until then. Prescriptor reads the organisation from here as well as from the
			// PracticeID/LicenseKey members of the outer call.
			root.element("xisInfo", x -> x.empty("licenseKey")
					.attribute("password", credentials.licenseKey())
					.attribute("organisationUnitId", credentials.practiceId())
					.attribute("key", credentials.licenseKey()));
			root.element("patient", p -> {
				p.text("gender", patient.gender());
				p.text("dob", patient.dateOfBirth().format(ISO_DATE));
				p.text("patientId", "");
				p.element("medicalExaminer", m -> {
				});

				p.element("allergies", a -> {
					for (CodedItem allergy : patient.allergies()) {
						writeGStandaard(a, allergy);
					}
				});

				p.element("indications", i -> {
				});

				p.element("contraIndications", c -> {
					for (CodedItem contraIndication : patient.contraIndications()) {
						writeGStandaard(c, contraIndication);
					}
				});

				// Current medication, resolved to the PRK/GPK pair Prescriptor needs for
				// medication surveillance. PRK is the attribute the upstream actually reads —
				// see XmlRpcRequestBuilder.medicationType — and is why a host may mix code
				// levels per entry. HPK is written alongside it when the host identified the
				// drug at that level, as the more precise fact, not as the one read.
				p.element("medication", m -> {
					for (MedicationCodes codes : medication) {
						m.element("drug", drug -> {
							drug.attribute("pending", "false");
							drug.empty("GStandaard")
									.attribute("PRK", String.valueOf(codes.prk()))
									.attribute("GPK", String.valueOf(codes.gpk()));

							if (codes.hpk() != null) {
								drug.attribute("HPK", String.valueOf(codes.hpk()));
							}
						});
					}
				});

				// Lab data as LOINC, which is what the host sent and what the MFB datatests test:
				// the generator in g-standaard/apps/mfb builds a DatatestLOINC keyed on this
				// number. The unit is carried too — Prescriptor_Units reads it to convert weight
				// and height — though the value is already in the unit the rules evaluate in.
				//
				// Results are sent as they arrived: no de-duplication and no ordering. Where a host
				// sends several for one determination the engine takes the most recent, which is
				// its decision to make and needs the date attribute to be precise enough to make it
				// — see upstreamMoment below.
				p.element("laboratoryData", l -> {
					for (LabResult lab : patient.laboratoryData()) {
						l.empty("LOINC")
								.attribute("num", lab.loinc())
								.attribute("caption", lab.caption())
								.attribute("date", upstreamMoment(lab))
								.attribute("value", lab.value())
								.attribute("unit", lab.unit());
					}
				});

				p.empty("referrals");
			});
		}));
	}

	/**
	 * The {@code date} attribute of one determination, in one of the two forms the rules engine
	 * parses: {@code yyyy-mm-dd} when the host stated only a date, {@code yyyy-mm-ddThh:mm:ss} when
	 * it stated a time.
	 *
	 * <p>The time is not decoration. Several results for one determination are resolved by
	 * {@code TCRELabValueList.MostRecent}, which compares this attribute and keeps the first of a
	 * tie — so with date-only values, two results from one day are ordered by the sequence they were
	 * sent in rather than by which is later.
	 */
	private static String upstreamMoment(LabResult lab) {
		if (lab.time() == null) {
			return lab.date().format(ISO_DATE);
		}

		return lab.date().atTime(lab.time()).format(UPSTREAM_MOMENT);
	}

	/**
	 * {@code caption} and {@code UID} stay empty here even though {@link CodedItem} carries both
	 * since the surveillance contract needed them. Two reasons, and neither is that they would be
	 * wrong: this wire format is pinned by {@code XmlRpcRequestBuilderTest} against a legacy
	 * endpoint nobody can re-test on a whim, and Prescriptor resolves its own descriptions from
	 * the G-Standaard by code rather than reading these. On the surveillance contract the Hub does
	 * read them, which is why the builder there writes them — see
	 * {@code MedicationSurveillanceRequestBuilder.writeGStandaard}.
	 */
	private static void writeGStandaard(XmlWriter xml, CodedItem item) {
		xml.empty("GStandaard")
				.attribute(item.codeSystem(), item.code())
				.attribute("caption", "")
				.attribute("UID", "");
	}
}
