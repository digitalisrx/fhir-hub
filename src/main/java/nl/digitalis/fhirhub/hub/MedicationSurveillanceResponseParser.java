package nl.digitalis.fhirhub.hub;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.time.LocalDateTime;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import javax.xml.XMLConstants;
import javax.xml.parsers.DocumentBuilder;
import javax.xml.parsers.DocumentBuilderFactory;
import javax.xml.parsers.ParserConfigurationException;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.w3c.dom.Node;
import org.w3c.dom.NodeList;
import org.xml.sax.SAXException;

import ca.uhn.fhir.rest.server.exceptions.InternalErrorException;
import nl.digitalis.fhirhub.model.CodedItem;
import nl.digitalis.fhirhub.model.FindingContext;
import nl.digitalis.fhirhub.model.FindingContext.Kind;
import nl.digitalis.fhirhub.model.SurveillanceFinding;
import nl.digitalis.fhirhub.model.SurveillanceReport;
import nl.digitalis.fhirhub.prescriptor.CodeSystemTokens;

/**
 * Reads the {@code MedicationSurveillanceResponse} envelope back into a
 * {@link SurveillanceReport}.
 *
 * <h2>Absent is not empty</h2>
 * The one thing this class must never do is turn a failure into an all-clear. A response with no
 * {@code <report>} element in it — a SOAP fault, an HTML error page from the container, a body
 * that parses but says nothing — is refused with a 500, because "no rule fired" and "no rules
 * ran" are the same payload to a prescriber and opposite facts about the patient. A report that
 * <em>is</em> present and carries no rules is a genuine all-clear and is returned as one.
 *
 * <p>That distinction is not theoretical here. The Hub calls the rules engine and its own
 * G-Standaard checks in one try/except each and logs a failure of either rather than raising, so a
 * half-run check is a shape this code can actually receive.
 *
 * <h2>Read by local name</h2>
 * Every lookup ignores namespaces. The deployed service answers with the report in
 * {@code http://digitalis.nl/schema/DigitalisRx} wrapped in an element its own WSDL does not
 * describe, and it builds that envelope by hand — so the namespace of a given element is not
 * something to depend on. What is stable is the element names, which are the schema's.
 */
@Component
public class MedicationSurveillanceResponseParser {

	private static final Logger log = LoggerFactory.getLogger(MedicationSurveillanceResponseParser.class);

	/**
	 * The double-escaped character references the rules engine writes into its message text:
	 * {@code Pati&amp;#235;nt} arrives as the six characters {@code &#235;} in a text node rather
	 * than as {@code ë}. Decoded here rather than passed on, because passing it on means a
	 * prescriber reads "Pati&#235;nt" — the escaping is upstream's mistake and this is the last
	 * place that can tell it apart from text.
	 */
	private static final Pattern CHARACTER_REFERENCE = Pattern.compile("&#(x[0-9a-fA-F]+|[0-9]+);");

	/** Runs of whitespace inside one flattened line, including the newlines in the source XHTML. */
	private static final Pattern WHITESPACE = Pattern.compile("\\s+");

	public SurveillanceReport parse(String xml) {
		Element report = report(document(xml).getDocumentElement());

		List<SurveillanceFinding> findings = new ArrayList<>();
		for (Element rule : childrenNamed(report, "rule")) {
			findings.add(finding(rule));
		}

		SurveillanceReport parsed = new SurveillanceReport(
				attribute(report, "CRID"),
				attribute(report, "serviceVersion"),
				moment(attribute(report, "date")),
				integer(attribute(report, "activatedRules")),
				findings);

		log.info("Medication surveillance report {} from service {}: {} finding(s), {} rule(s) activated",
				parsed.crid(), parsed.serviceVersion(), findings.size(), parsed.activatedRules());

		return parsed;
	}

	/**
	 * The report element, or a 500 naming what came back instead.
	 *
	 * <p>A SOAP fault is called out separately because it is the one failure that carries a usable
	 * message: the Hub raises {@code InvalidInputError} for anything it could not process, so the
	 * fault string is the closest thing to a reason there is. It is logged rather than returned —
	 * an upstream fault string is written for us, not for an integrator, and can carry a stack
	 * trace.
	 */
	private Element report(Element envelope) {
		Element fault = firstNamed(envelope, "Fault");
		if (fault != null) {
			log.error("Medication surveillance refused the request: {}", text(fault));

			throw new InternalErrorException(
					"Medication surveillance could not be run: the clinical-rules service refused the"
							+ " request. Nothing may be concluded about this patient's medication.");
		}

		List<Element> reports = allNamed(envelope, "report");
		if (reports.isEmpty()) {
			throw new InternalErrorException(
					"Medication surveillance returned no report, so no conclusion may be drawn about"
							+ " this patient's medication.");
		}

		if (reports.size() > 1) {
			// The schema allows several, told apart by their date, for a response that re-evaluates
			// a report handed in with the request. This interface never sends one, so a second
			// report means an assumption here is wrong and the log should say so.
			log.warn("Medication surveillance returned {} reports; reading the first", reports.size());
		}

		return reports.getFirst();
	}

	private SurveillanceFinding finding(Element rule) {
		Element message = firstNamed(rule, "message");

		return new SurveillanceFinding(
				attribute(rule, "id"),
				attribute(rule, "version"),
				alertLevel(rule),
				text(firstNamed(rule, "title")),
				message == null ? null : text(firstNamed(message, "caption")),
				message == null ? null : plainText(firstNamed(message, "text")),
				context(firstNamed(rule, "context")));
	}

	/**
	 * 1 red, 2 orange, 3 green. Anything else is left absent rather than mapped to a severity:
	 * the Hub's own signal template starts life at level 9 and a check that never sets a level
	 * sends that, so an out-of-range value is a real possibility and "no severity stated" is the
	 * honest rendering of it.
	 */
	private Integer alertLevel(Element rule) {
		Element alert = firstNamed(rule, "alert");
		Integer level = alert == null ? null : integer(attribute(alert, "level"));

		if (level != null && (level < 1 || level > 3)) {
			log.warn("Rule {} reported alert level {}, which is outside 1..3", attribute(rule, "id"), level);

			return null;
		}

		return level;
	}

	/**
	 * What the rule was looking at. The upstream echoes the elements it read back inside the
	 * finding, which is what lets a signal be pointed at the row of the dossier it is about.
	 */
	private List<FindingContext> context(Element context) {
		List<FindingContext> items = new ArrayList<>();
		if (context == null) {
			return items;
		}

		for (Element drug : allNamed(context, "drug")) {
			items.add(drug(drug));
		}

		for (Element loinc : allNamed(context, "LOINC")) {
			items.add(new FindingContext(Kind.LAB_RESULT, attribute(loinc, "UID"),
					attribute(loinc, "caption"),
					codes(loinc, CodeSystemTokens.LOINC, "num"),
					attribute(loinc, "value")));
		}

		// The rules engine resolves a LOINC determination to the NHG one it corresponds to and
		// echoes that back instead of what was sent — so a lab result may return as either.
		for (Element nhg : allNamed(context, "NHG")) {
			items.add(new FindingContext(Kind.LAB_RESULT, attribute(nhg, "UID"),
					attribute(nhg, "caption"), List.of(), attribute(nhg, "value")));
		}

		for (Element ci : allNamed(firstNamed(context, "contraIndications"), "GStandaard")) {
			items.add(new FindingContext(Kind.CONTRA_INDICATION, attribute(ci, "UID"),
					attribute(ci, "caption"),
					codes(ci, CodeSystemTokens.CI_CODE, CodeSystemTokens.CI_CODE), null));
		}

		for (Element allergy : allNamed(firstNamed(context, "allergies"), "GStandaard")) {
			List<CodedItem> codes = new ArrayList<>();
			codes.addAll(codes(allergy, CodeSystemTokens.SNK, CodeSystemTokens.SNK));
			codes.addAll(codes(allergy, CodeSystemTokens.SSK, CodeSystemTokens.SSK));
			codes.addAll(codes(allergy, CodeSystemTokens.OGGRP, CodeSystemTokens.OGGRP));

			items.add(new FindingContext(Kind.ALLERGY, attribute(allergy, "UID"),
					attribute(allergy, "caption"), codes, null));
		}

		for (Element icpc : allNamed(firstNamed(context, "indications"), "ICPC")) {
			items.add(new FindingContext(Kind.INDICATION, attribute(icpc, "UID"),
					attribute(icpc, "caption"),
					codes(icpc, CodeSystemTokens.ICPC, "value"), null));
		}

		return items;
	}

	/**
	 * One drug from a finding's context, with every code it carries.
	 *
	 * <p>Whether it was one of the proposals or part of the standing dossier is read back off the
	 * {@code pending} and {@code trigger} attributes this interface set on the way out — the two
	 * arrive in the same {@code <medication>} list, and the distinction decides which of the
	 * host's own resources a finding is about.
	 */
	private FindingContext drug(Element drug) {
		Element gStandaard = firstNamed(drug, "GStandaard");

		List<CodedItem> codes = new ArrayList<>();
		if (gStandaard != null) {
			codes.addAll(codes(gStandaard, CodeSystemTokens.PRK, CodeSystemTokens.PRK));
			codes.addAll(codes(gStandaard, CodeSystemTokens.GPK, CodeSystemTokens.GPK));
			codes.addAll(codes(gStandaard, CodeSystemTokens.HPK, CodeSystemTokens.HPK));
		}

		codes.addAll(codes(drug, CodeSystemTokens.ATC, "ATC"));

		boolean proposed = "true".equalsIgnoreCase(attribute(drug, "trigger"))
				|| "true".equalsIgnoreCase(attribute(drug, "pending"))
				|| "1".equals(attribute(drug, "pending"));

		return new FindingContext(
				proposed ? Kind.PROPOSED_DRUG : Kind.CURRENT_DRUG,
				attribute(drug, "UID"),
				gStandaard == null ? attribute(drug, "caption") : attribute(gStandaard, "caption"),
				codes,
				null);
	}

	/** One code, if the attribute carries a usable one. A zero means "not coded at this level". */
	private List<CodedItem> codes(Element element, String token, String attribute) {
		String code = attribute(element, attribute);
		if (code == null || code.isBlank() || "0".equals(code.trim())) {
			return List.of();
		}

		return List.of(new CodedItem(token, code.trim()));
	}

	/**
	 * The message text, flattened to lines.
	 *
	 * <p>The upstream sends XHTML — paragraphs, ordered lists of dosing steps, line breaks — and
	 * this keeps the structure that carries meaning while dropping the markup: a list item becomes
	 * its own line with its number, a paragraph and a {@code <br/>} become line breaks. What is
	 * lost is emphasis and layout, and what is kept is every word and the order of the steps.
	 *
	 * <p>It is deliberately not carried through as a FHIR narrative. A {@code Narrative.div} is
	 * validated against a fixed list of permitted elements and attributes, and this text arrives
	 * with at least one that is not on it ({@code schemaLocation} on the wrapping div) — so a
	 * narrative would put the validity of every response at the mercy of the next rule an editor
	 * writes. Carrying the XHTML is worth revisiting with a sanitiser behind it; guessing that the
	 * markup is safe is not.
	 */
	String plainText(Element text) {
		if (text == null) {
			return null;
		}

		StringBuilder flattened = new StringBuilder();
		flatten(text, flattened);

		List<String> lines = new ArrayList<>();
		for (String line : flattened.toString().split("\n")) {
			String cleaned = WHITESPACE.matcher(decodeCharacterReferences(line)).replaceAll(" ").trim();
			if (!cleaned.isEmpty()) {
				lines.add(cleaned);
			}
		}

		return lines.isEmpty() ? null : String.join("\n", lines);
	}

	private void flatten(Element element, StringBuilder out) {
		int item = 0;
		for (Node child : children(element)) {
			if (child.getNodeType() == Node.TEXT_NODE || child.getNodeType() == Node.CDATA_SECTION_NODE) {
				out.append(child.getNodeValue());

				continue;
			}

			if (!(child instanceof Element nested)) {
				continue;
			}

			String name = localName(nested);
			if ("br".equalsIgnoreCase(name)) {
				out.append('\n');

				continue;
			}

			if ("li".equalsIgnoreCase(name)) {
				out.append('\n');
				// Numbered where the parent is an ordered list, so the steps of a dosing advice
				// keep the order the editor wrote them in.
				out.append("ol".equalsIgnoreCase(localName(element)) ? (++item) + ". " : "- ");
				flatten(nested, out);

				continue;
			}

			out.append('\n');
			flatten(nested, out);
			out.append('\n');
		}
	}

	/**
	 * A reference that is not a usable code point is left as the text it already is, rather than
	 * being allowed to fail: the alternative is one malformed entity in one rule's text costing
	 * the whole report, which is the trade this class exists to refuse.
	 */
	private String decodeCharacterReferences(String value) {
		Matcher matcher = CHARACTER_REFERENCE.matcher(value);
		StringBuilder decoded = new StringBuilder();
		while (matcher.find()) {
			String replacement = codePoint(matcher.group(1));
			matcher.appendReplacement(decoded,
					Matcher.quoteReplacement(replacement == null ? matcher.group() : replacement));
		}

		return matcher.appendTail(decoded).toString();
	}

	private String codePoint(String reference) {
		try {
			int codePoint = reference.startsWith("x")
					? Integer.parseInt(reference.substring(1), 16)
					: Integer.parseInt(reference);

			// The characters XML itself forbids are refused too: a NUL or a lone surrogate in a
			// FHIR string is not valid, and this is not the place to introduce one.
			if (codePoint < 0x20 || !Character.isValidCodePoint(codePoint)
					|| Character.isSurrogate((char) codePoint)) {
				return null;
			}

			return Character.toString(codePoint);
		}
		catch (NumberFormatException e) {
			return null;
		}
	}

	/**
	 * The report's own timestamp. Absent rather than invented when it cannot be read: it ends up
	 * on the Bundle as the moment the check was run, and a made-up one would be worse than none.
	 */
	private LocalDateTime moment(String value) {
		if (value == null || value.isBlank()) {
			return null;
		}

		try {
			return LocalDateTime.parse(value.trim().substring(0, Math.min(19, value.trim().length())));
		}
		catch (DateTimeParseException | IndexOutOfBoundsException e) {
			log.warn("Medication surveillance reported an unreadable report date '{}'", value);

			return null;
		}
	}

	private Integer integer(String value) {
		if (value == null || value.isBlank()) {
			return null;
		}

		try {
			return Integer.valueOf(value.trim());
		}
		catch (NumberFormatException e) {
			return null;
		}
	}

	/**
	 * A DOM parser with entity resolution and DTDs switched off.
	 *
	 * <p>This parses a response from a service this interface trusts, which is exactly the
	 * assumption an XXE turns into a file read: the parser is on this side of the network, and a
	 * compromised or misconfigured upstream would be reading local files with it. The rest of the
	 * codebase never parses XML by hand — HAPI does the inbound FHIR — so this is the only place
	 * the setting has to be got right.
	 */
	private Document document(String xml) {
		if (xml == null || xml.isBlank()) {
			throw new InternalErrorException(
					"Medication surveillance returned an empty response, so no conclusion may be"
							+ " drawn about this patient's medication.");
		}

		try {
			DocumentBuilderFactory factory = DocumentBuilderFactory.newInstance();
			factory.setFeature(XMLConstants.FEATURE_SECURE_PROCESSING, true);
			factory.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true);
			factory.setFeature("http://xml.org/sax/features/external-general-entities", false);
			factory.setFeature("http://xml.org/sax/features/external-parameter-entities", false);
			factory.setXIncludeAware(false);
			factory.setExpandEntityReferences(false);
			factory.setNamespaceAware(true);

			DocumentBuilder builder = factory.newDocumentBuilder();

			return builder.parse(new ByteArrayInputStream(xml.getBytes(StandardCharsets.UTF_8)));
		}
		catch (ParserConfigurationException e) {
			throw new IllegalStateException("Could not configure the XML parser", e);
		}
		catch (SAXException | IOException e) {
			log.error("Medication surveillance returned a response that is not XML", e);

			throw new InternalErrorException(
					"Medication surveillance returned an unreadable response, so no conclusion may be"
							+ " drawn about this patient's medication.");
		}
	}

	private String localName(Element element) {
		return element.getLocalName() == null ? element.getNodeName() : element.getLocalName();
	}

	private List<Node> children(Element element) {
		List<Node> children = new ArrayList<>();
		NodeList nodes = element.getChildNodes();
		for (int i = 0; i < nodes.getLength(); i++) {
			children.add(nodes.item(i));
		}

		return children;
	}

	/** Every descendant with this local name, in document order. */
	private List<Element> allNamed(Element root, String name) {
		List<Element> found = new ArrayList<>();
		if (root == null) {
			return found;
		}

		NodeList nodes = root.getElementsByTagNameNS("*", name);
		for (int i = 0; i < nodes.getLength(); i++) {
			found.add((Element) nodes.item(i));
		}

		return found;
	}

	/** The first descendant with this local name, or null. */
	private Element firstNamed(Element root, String name) {
		List<Element> found = allNamed(root, name);

		return found.isEmpty() ? null : found.getFirst();
	}

	/** The direct children with this local name — {@code rule} is only ever read this way. */
	private List<Element> childrenNamed(Element parent, String name) {
		List<Element> found = new ArrayList<>();
		for (Node child : children(parent)) {
			if (child instanceof Element element && name.equals(localName(element))) {
				found.add(element);
			}
		}

		return found;
	}

	private String attribute(Element element, String name) {
		if (element == null) {
			return null;
		}

		String value = element.getAttribute(name);

		return value.isEmpty() ? null : value;
	}

	private String text(Element element) {
		if (element == null) {
			return null;
		}

		String value = element.getTextContent();

		return value == null || value.isBlank() ? null : value.trim();
	}
}
