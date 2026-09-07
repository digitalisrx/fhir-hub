package nl.digitalis.fhirhub;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalTime;
import java.util.List;

import nl.digitalis.fhirhub.model.CodedItem;
import nl.digitalis.fhirhub.model.LabResult;
import nl.digitalis.fhirhub.model.MedicationCodes;
import nl.digitalis.fhirhub.model.PatientContext;
import nl.digitalis.fhirhub.model.PrescriptorCredentials;
import nl.digitalis.fhirhub.model.SessionRequest;
import nl.digitalis.fhirhub.model.SessionType;
import nl.digitalis.fhirhub.model.SurveillanceDrug;
import nl.digitalis.fhirhub.model.SurveillanceRequest;
import nl.digitalis.fhirhub.model.XisInfo;

/** Shared test data, kept in one place so a change to the model does not ripple through every test. */
public final class Fixtures {

	public static final PrescriptorCredentials CREDENTIALS =
			new PrescriptorCredentials("practice-123", "license-key");

	private Fixtures() {
	}

	public static String xml(String name) {
		return resource("/xmlrpc/" + name);
	}

	/** A response from the Digitalis Hub, for the surveillance contract. */
	public static String hubXml(String name) {
		return resource("/hub/" + name);
	}

	private static String resource(String path) {
		try (var in = Fixtures.class.getResourceAsStream(path)) {
			if (in == null) {
				throw new IllegalArgumentException("No such fixture: " + path);
			}

			return new String(in.readAllBytes(), StandardCharsets.UTF_8);
		}
		catch (IOException e) {
			throw new UncheckedIOException(e);
		}
	}

	public static PatientContext patient() {
		return new PatientContext(
				"F",
				LocalDate.of(1980, 1, 1),
				List.of(new CodedItem("SNK", "10499"), new CodedItem("OGGrp", "77"), new CodedItem("SSK", "3204")),
				List.of(new CodedItem("CICode", "228"), new CodedItem("ICPC", "A01")),
				List.of(new CodedItem("PRK", "8060")),
				List.of(new LabResult("62238-1", "eGFR volgens CKD-EPI", "mL/min/{1.73_m2}",
						LocalDate.of(2024, 7, 4), null, "65")));
	}

	public static final XisInfo XIS = new XisInfo("xis-001", "1.0");

	public static SessionRequest formularySession() {
		return new SessionRequest(
				SessionType.FORMULARY, "A01", patient(), "https://someurl.example/done", XIS, null);
	}

	/**
	 * A surveillance question shaped like the published example: metformine being prescribed
	 * against a dossier of ibuprofen and omeprazol, a pregnancy contra-indication, two allergies
	 * and an eGFR of 35.
	 */
	public static SurveillanceRequest surveillanceRequest() {
		return new SurveillanceRequest(
				XIS,
				"F",
				LocalDate.of(2007, 9, 7),
				List.of(new CodedItem("OGGrp", "35"), new CodedItem("SNK", "10499")),
				List.of(new CodedItem("CICode", "1320")),
				List.of(new CodedItem("ICPC", "K86")),
				List.of(new SurveillanceDrug("rx-1",
						new MedicationCodes(1090, 3816, 693332, "A10BA02"),
						"A10BA02",
						"METFORMINE TABLET   500MG",
						"1D1T",
						"1 X per dag 1 tablet",
						new BigDecimal("20"),
						"ST",
						"K86",
						LocalDate.of(2026, 9, 6),
						LocalDate.of(2026, 9, 26))),
				List.of(new SurveillanceDrug("ms-1",
						new MedicationCodes(27278, 51004, null, "M01AE01"),
						"M01AE01",
						"IBUPROFEN TABLET 400MG",
						null, null, null, null, null,
						LocalDate.of(2026, 9, 6),
						null)),
				List.of(new LabResult("62238-1", "kreatinineklaring", "mL/min/{1.73_m2}",
						LocalDate.of(2026, 9, 6), LocalTime.of(9, 5, 11), "35")));
	}
}
