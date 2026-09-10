package nl.digitalis.fhirhub.hub;

import java.math.BigDecimal;
import java.time.format.DateTimeFormatter;

import org.springframework.stereotype.Component;

import nl.digitalis.fhirhub.fhir.LabDeterminations;
import nl.digitalis.fhirhub.fhir.LabDeterminations.Determination;
import nl.digitalis.fhirhub.fhir.LabDeterminations.NhgEquivalent;
import nl.digitalis.fhirhub.model.CodedItem;
import nl.digitalis.fhirhub.model.LabResult;
import nl.digitalis.fhirhub.model.PrescriptorCredentials;
import nl.digitalis.fhirhub.model.SurveillanceDrug;
import nl.digitalis.fhirhub.model.SurveillanceRequest;
import nl.digitalis.fhirhub.prescriptor.CodeSystemTokens;
import nl.digitalis.fhirhub.xml.XmlWriter;

/**
 * Builds the {@code MedicationSurveillance} SOAP call the Digitalis Hub takes: a SOAP envelope
 * wrapped around a complete {@code DigitalisRx} document.
 *
 * <p>Not the shape {@code prescriptor/} sends. The Hub takes the DigitalisRx document as XML in
 * the SOAP body, where Prescriptor takes it as a string in a CDATA section of an XML-RPC struct —
 * so the document is written twice in this codebase rather than shared. What each upstream reads
 * out of it differs as much as the envelope does, which is the other half of the reason: see
 * {@link #drug} for the six attributes that only matter here.
 *
 * <h2>The envelope</h2>
 * The wrapper element is {@code {http://hub.digitalis.nl/call}MedicationSurveillance} holding a
 * {@code DigitalisRx} element in the same namespace, which is what the Hub's own documented
 * example sends ({@code hub/docs/examples_call/script_curl_post_medicationsurveillance.sh}) — and
 * not the {@code dummy}/{@code asDigitalisRx} nesting its WSDL describes, which the deployed
 * service does not require. The service resolves the payload with a single xpath for
 * {@code //DigitalisRx} in the schema namespace and ignores the wrapper entirely, so the two
 * spellings are equivalent to it; this one is the one that has been run against it.
 *
 * <h2>Which drug is being checked</h2>
 * The proposed prescriptions are marked <strong>twice</strong>, and both marks are load-bearing
 * because the two engines behind this one call read different attributes:
 *
 * <ul>
 * <li><strong>{@code pending="true"}</strong> is what the Hub's own G-Standaard medicatiebewaking
 * selects on ({@code MbFactorySettings.get_pending_medications}). Its allergy, age, duplicate
 * medication and dose checks <em>do not run at all</em> unless at least one drug carries it — so
 * a request without it is answered by the beslisregels alone, and the answer looks like a
 * complete one.
 * <li><strong>{@code trigger="true"}</strong> is what the clinical-rules engine reads
 * ({@code TCREDrug.ReadFromXML}). It deliberately does <em>not</em> read {@code pending="true"} as
 * pending — the value it looks for there is {@code trueevs} — because marking the drug under test
 * with {@code pending} "had too many side effects" (RM#6332) and grew a property of its own.
 * </ul>
 *
 * Sending one and not the other silently halves the check. The two published examples each send
 * one of them, which is how this was found.
 *
 * <h2>Dates decide what is weighed at all</h2>
 * Both engines filter the standing dossier on {@code date}: a drug whose start lies in the future
 * is skipped, and so is one with no start at all — the Hub's xpath compares
 * {@code substring(@date,1,10)} numerically, and an absent attribute is not a number. So every
 * drug carries a start date, and {@code SurveillanceParametersMapper} is where a host's silence
 * about it is turned into one. Dates are written as {@code yyyy-mm-dd} because that is the
 * engine's clean branch: {@code TCREFormatsettings.StringToDate} treats a ten-character string as
 * a date and hands anything else to a datetime parser.
 */
@Component
public class MedicationSurveillanceRequestBuilder {

	private final LabDeterminations determinations;

	public MedicationSurveillanceRequestBuilder(LabDeterminations determinations) {
		this.determinations = determinations;
	}

	static final String SOAP_NS = "http://schemas.xmlsoap.org/soap/envelope/";

	static final String HUB_NS = "http://hub.digitalis.nl/call";

	static final String DIGITALIS_RX_NS = "http://digitalis.nl/schema/DigitalisRx";

	/** The SOAP action of the operation, as its WSDL declares it. */
	static final String SOAP_ACTION = "MedicationSurveillance";

	private static final DateTimeFormatter ISO_DATE = DateTimeFormatter.ISO_LOCAL_DATE;

	private static final DateTimeFormatter MOMENT = DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss");

	/**
	 * The ATC the schema reserves for a product that has none — a bandage, a homeopathic product.
	 * Written rather than left off so the attribute is always present, which is what its
	 * {@code use="required"} says and what keeps an absent ATC distinguishable from an
	 * unresolved one.
	 */
	private static final String NO_ATC = "ZZZZZZ";

	public String medicationSurveillance(SurveillanceRequest request, PrescriptorCredentials credentials) {
		return XmlWriter.document(xml -> xml.element("Envelope", SOAP_NS, envelope -> envelope
				.element("Body", body -> body
						.element("MedicationSurveillance", HUB_NS, call -> call
								.element("DigitalisRx", wrapper -> digitalisRx(wrapper, request, credentials))))));
	}

	private void digitalisRx(XmlWriter xml, SurveillanceRequest request, PrescriptorCredentials credentials) {
		xml.element("DigitalisRx", DIGITALIS_RX_NS, root -> {
			root.empty("schemaVersion").attribute("id", "id0");

			// The Hub overwrites all four of these with its own clinical-rules licence before it
			// calls the engine (CreCall.use_prescriptor_license), so what is sent here is not what
			// is adjudicated — but the element has to be there for it to overwrite, and a future
			// Hub that checks the caller's licence would check this. The practice id and licence
			// key are the ones on the Authorization header, exactly as for a session.
			root.element("xisInfo", x -> x.empty("licenseKey")
					.attribute("email", credentials.practiceId())
					.attribute("password", credentials.licenseKey())
					.attribute("organisationUnitId", credentials.practiceId())
					.attribute("key", credentials.licenseKey()));

			root.element("patient", p -> {
				// Required by the schema and deliberately empty: no name, identifier or address of
				// a patient is forwarded by this interface, and the published guide says so.
				// Nothing upstream reads it — the rules engine correlates on the UID of each
				// record instead, which is the host's own id and is echoed back in the findings.
				p.text("patientId", "");
				p.text("gender", request.gender());
				p.text("dob", request.dateOfBirth().format(ISO_DATE));

				p.element("allergies", a -> {
					for (CodedItem allergy : request.allergies()) {
						writeGStandaard(a, allergy);
					}
				});

				// ICPC-coded conditions go here and G-Standaard-coded ones into
				// contraIndications, because those are the two elements the schema has for them:
				// an <ICPC> is an indication and a <GStandaard CICode> is a contra-indication.
				// The session contract routes the same distinction into two XML-RPC members.
				p.element("indications", i -> {
					for (CodedItem indication : request.indications()) {
						i.empty("ICPC")
								.attribute("value", indication.code())
								// Deprecated in the schema in favour of `value`, and documented as
								// carrying the same string.
								.attribute("name", indication.code())
								.attribute("caption", caption(indication))
								.attribute("UID", uid(indication));
					}
				});

				p.element("contraIndications", c -> {
					for (CodedItem contraIndication : request.contraIndications()) {
						writeGStandaard(c, contraIndication);
					}
				});

				p.element("medication", m -> {
					for (SurveillanceDrug proposed : request.proposed()) {
						drug(m, proposed, true);
					}

					for (SurveillanceDrug current : request.currentMedication()) {
						drug(m, current, false);
					}
				});

				// LOINC, as the host sent it and as the datatests test it — see fhir/LabDeterminations.
				//
				// The moment goes in the `date` attribute, with the time folded into it, because
				// that is the only one anything reads: TCRELabValue.ReadFromXML takes `date` and
				// nothing in either the engine or the Hub reads `time` at all. `time` is written
				// beside it because the schema defines it and the Hub's examples carry it — it is
				// the redundant copy, not the load-bearing one.
				//
				// One result per LOINC code reaches here: ClinicalContextMapper keeps the most
				// recent of a series. That matters most for the NHG pair below, which the Hub
				// selects by id without reading a date, so a stale weight sent beside a current one
				// would otherwise be the one a dose is computed against.
				p.element("laboratoryData", l -> {
					for (LabResult lab : request.laboratoryData()) {
						XmlWriter loinc = l.empty("LOINC")
								.attribute("num", lab.loinc())
								.attribute("caption", lab.caption())
								.attribute("date", moment(lab))
								.attribute("value", lab.value())
								.attribute("UID", uid(lab.uid()));

						if (lab.time() != null) {
							loinc.attribute("time", lab.time().toString());
						}

						nhg(l, lab);
					}
				});
			});
		});
	}

	/**
	 * The same determination a second time, in its NHG identity, for the two that have one.
	 *
	 * <p>The G-Standaard dose-band model is the only consumer that cannot read a LOINC code: the
	 * Hub selects a weight with {@code NHG[@id="357" or @id="2408"]} and a length with
	 * {@code NHG[@id="560"]}, and finds nothing in a document that carries only
	 * {@code <LOINC num="29463-7">}. Before this element existed, a request that carried a weight
	 * still produced "gewicht ontbreekt" from a weight-dependent dose band — the check reported
	 * that it could not run, which is the good version of that failure and still a check that did
	 * not run.
	 *
	 * <p>Written <em>beside</em> the LOINC element and not instead of it, because the beslisregels
	 * read the LOINC form and the two engines answer the same call. See
	 * {@code LabDeterminations.NhgEquivalent} for why two rows of NHG is not the NHG mapping this
	 * codebase refuses to have, and for the units: a weight is kilograms in both forms, a length is
	 * metres here and centimetres in the LOINC one.
	 *
	 * <p>{@code memo}, {@code mat} and {@code bijz} are written as well as {@code id}, because the
	 * Hub is not the only reader that matches on them: {@code TCRELabValueNHG.Matches} compares
	 * memo, material and particularity, so a rule that tests an NHG determination finds nothing in
	 * an element that carries the id alone.
	 */
	private void nhg(XmlWriter xml, LabResult lab) {
		Determination determination = determinations.forLoinc(lab.loinc());
		NhgEquivalent nhg = determination == null ? null : determination.nhg();
		if (nhg == null) {
			return;
		}

		XmlWriter element = xml.empty("NHG")
				.attribute("id", String.valueOf(nhg.id()))
				.attribute("memo", nhg.memo())
				.attribute("mat", nhg.mat())
				.attribute("caption", lab.caption())
				.attribute("date", moment(lab))
				.attribute("value", nhg.value(lab.value()))
				.attribute("UID", uid(lab.uid()));

		if (nhg.bijz() != null) {
			element.attribute("bijz", nhg.bijz());
		}
	}

	private String uid(String uid) {
		return uid == null ? "" : uid;
	}

	/**
	 * One {@code <drug>}, and the six attributes that are only read on this contract.
	 *
	 * <p>{@code ATC} first, because it decides whether the drug is seen at all by most of the
	 * beslisregels: they select on ATC far more often than on a product code, and
	 * {@code MedicationCodeResolver} looks it up for exactly this reason.
	 *
	 * <p>{@code directionCoded} is the NHG Tabel 25 string, which the Hub's dose check parses
	 * ({@code dosageControl.py}) and the engine reads for its own dosing rules. It is passed
	 * through undecoded, like everywhere else in this codebase.
	 *
	 * <p>{@code supplyQuantity} and {@code supplyUnit} come from the prescription's dispense
	 * request. {@code RVV} is the reason for prescribing as an ICPC-1 code, which is what makes a
	 * dose check indication-specific.
	 *
	 * <p><strong>{@code DDD} and {@code PDD} are not written, and their absence is a known gap.</strong>
	 * The prescribed daily dose has to be computed from the coded dosage and the product's
	 * strength, which means decoding Tabel 25 — deliberately not done here, see
	 * {@code T25DosageMapper}. Rules that compare PDD against DDD cannot fire on a request from
	 * this interface. The engine guards the division, so the effect is a rule that stays silent
	 * rather than an error; that is the whole reason it is written down.
	 */
	private void drug(XmlWriter xml, SurveillanceDrug drug, boolean proposed) {
		xml.element("drug", d -> {
			d.attribute("ATC", drug.atc() == null || drug.atc().isBlank() ? NO_ATC : drug.atc());
			d.attribute("UID", drug.uid());
			d.attribute("pending", String.valueOf(proposed));

			if (proposed) {
				d.attribute("trigger", "true");
			}

			d.attribute("date", drug.startDate().format(ISO_DATE));

			if (drug.endDate() != null) {
				d.attribute("dateEnd", drug.endDate().format(ISO_DATE));
			}

			if (drug.directions() != null && !drug.directions().isBlank()) {
				d.attribute("directionCoded", drug.directions());
			}

			if (drug.dosageText() != null && !drug.dosageText().isBlank()) {
				d.attribute("directionCaption", drug.dosageText());
			}

			if (drug.supplyQuantity() != null) {
				d.attribute("supplyQuantity", plain(drug.supplyQuantity()));

				if (drug.supplyUnit() != null && !drug.supplyUnit().isBlank()) {
					d.attribute("supplyUnit", drug.supplyUnit());
				}
			}

			gStandaard(d, drug);

			if (drug.reasonIcpc() != null && !drug.reasonIcpc().isBlank()) {
				d.empty("RVV")
						.attribute("codeSystem", "ICPC1")
						.attribute("codeValue", drug.reasonIcpc());
			}
		});
	}

	/**
	 * The product codes. All three are written where they are known and none is "the" one: the
	 * engine collects every GPK, PRK and HPK it finds and matches a rule against all of them
	 * ({@code TCREGStandaard.ReadFromGstdNode}), so this is not the session contract's
	 * {@code MedicationType}, where announcing the wrong level drops drugs silently.
	 *
	 * <p>{@code selected} records which level the host coded the drug at. Neither engine reads it;
	 * it is Prescriptor's own field, and it is written because it is true and because the Hub's
	 * examples carry it.
	 */
	private void gStandaard(XmlWriter xml, SurveillanceDrug drug) {
		XmlWriter gs = xml.empty("GStandaard")
				.attribute("PRK", String.valueOf(drug.codes().prk()))
				.attribute("GPK", String.valueOf(drug.codes().gpk()));

		if (drug.codes().hpk() != null) {
			gs.attribute("HPK", String.valueOf(drug.codes().hpk()));
		}

		gs.attribute("selected", drug.codes().hpk() == null ? CodeSystemTokens.PRK : CodeSystemTokens.HPK)
				// Read by the Hub's duplicate-medication check, which titles its signal with it,
				// and shown to the prescriber. The host's own wording where it sent one.
				.attribute("caption", drug.caption())
				.attribute("UID", drug.uid());
	}

	/**
	 * An allergy or a contra-indication, with the host's own wording and its own record id.
	 *
	 * <p>Both attributes are load-bearing here in a way they are not on the session contract. The
	 * Hub's allergy check interpolates {@code caption} into the title <em>and</em> the body of the
	 * signal it raises, so an empty one produces "Allergie  (ongewenste groep)" and "In het
	 * dossier is een allergie ( (SNK)) geregistreerd" — a signal that does not say what it is
	 * about. That was measured against the live service, which is the only place it shows: the
	 * schema calls the attribute required and nothing rejects an empty one.
	 */
	private void writeGStandaard(XmlWriter xml, CodedItem item) {
		xml.empty("GStandaard")
				.attribute(item.codeSystem(), item.code())
				.attribute("caption", caption(item))
				.attribute("UID", uid(item));
	}

	/** Never blank — see {@link #writeGStandaard}. {@code ClinicalContextMapper} supplies it. */
	private String caption(CodedItem item) {
		return item.caption() == null || item.caption().isBlank()
				? item.codeSystem() + " " + item.code()
				: item.caption();
	}

	private String uid(CodedItem item) {
		return item.uid() == null ? "" : item.uid();
	}

	/**
	 * {@code toPlainString}, so a quantity never reaches the wire in scientific notation: the
	 * upstream reads these with a float parser that would take {@code 1E+2} as a zero.
	 */
	private String plain(BigDecimal value) {
		return value.stripTrailingZeros().toPlainString();
	}

	/**
	 * When a determination was taken, in one of the two forms the engine parses: {@code yyyy-mm-dd}
	 * where the host stated only a date, {@code yyyy-mm-ddThh:mm:ss} where it stated a time.
	 * Seconds always — {@code StringToDate} branches on the string being exactly ten characters,
	 * so {@link DateTimeFormatter#ISO_LOCAL_DATE_TIME} would drop zero seconds and match neither
	 * form.
	 */
	private String moment(LabResult lab) {
		if (lab.time() == null) {
			return lab.date().format(ISO_DATE);
		}

		return lab.date().atTime(lab.time()).format(MOMENT);
	}
}
