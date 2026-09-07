package nl.digitalis.fhirhub.xml;

import java.io.StringWriter;
import java.util.function.Consumer;

import javax.xml.stream.XMLOutputFactory;
import javax.xml.stream.XMLStreamException;
import javax.xml.stream.XMLStreamWriter;

/**
 * Thin wrapper over StAX for building the upstream XML.
 *
 * <p>The predecessor built this XML by string templating, interpolating patient data straight
 * into the template with no escaping. A single {@code &} or {@code "} in a drug description,
 * practice id or lab value produced a malformed request, and the same hole let caller-supplied
 * text inject arbitrary XML. Everything here goes through StAX, which escapes text and
 * attribute values as a matter of course.
 *
 * <p>It lives in a package of its own because there are two upstreams now: the XML-RPC dialect
 * Prescriptor speaks ({@code prescriptor/}) and the SOAP call the Hub takes ({@code hub/}).
 * Whichever one is edited, the escaping is the same escaping.
 */
public final class XmlWriter {

	private static final XMLOutputFactory FACTORY = XMLOutputFactory.newFactory();

	private final XMLStreamWriter out;

	private XmlWriter(XMLStreamWriter out) {
		this.out = out;
	}

	/** Builds a standalone XML document and returns it as a string. */
	public static String document(Consumer<XmlWriter> body) {
		StringWriter buffer = new StringWriter();
		try {
			XMLStreamWriter writer = FACTORY.createXMLStreamWriter(buffer);
			writer.writeStartDocument("UTF-8", "1.0");
			body.accept(new XmlWriter(writer));
			writer.writeEndDocument();
			writer.flush();
			writer.close();
		}
		catch (XMLStreamException e) {
			throw new IllegalStateException("Could not build XML request", e);
		}

		return buffer.toString();
	}

	/**
	 * An element with element content, e.g. {@code <patient>...</patient>}.
	 *
	 * <p>The zero-length text event before the end tag looks pointless and is not: without it,
	 * an element whose body writes nothing is serialised as {@code <medication/>} by Woodstox
	 * and as {@code <medication></medication>} by the JDK, and which one is in charge depends on
	 * whichever StAX provider happens to be on the classpath. Woodstox arrived as a transitive
	 * of hapi-fhir-validation and silently changed every request this service sends. The two
	 * forms are the same XML infoset, but the upstream is a legacy endpoint that nobody here can
	 * re-test on a whim, so the wire format is pinned rather than left to dependency resolution.
	 * {@code empty()} still self-closes deliberately, and is unaffected.
	 */
	public XmlWriter element(String name, Consumer<XmlWriter> body) {
		run(() -> out.writeStartElement(name));
		body.accept(this);
		run(() -> {
			out.writeCharacters("");
			out.writeEndElement();
		});

		return this;
	}

	public XmlWriter empty(String name) {
		run(() -> out.writeEmptyElement(name));

		return this;
	}

	/**
	 * An element that declares a default namespace, so that it and every unprefixed element
	 * inside it are in that namespace — {@code <DigitalisRx xmlns="http://digitalis.nl/…">}.
	 *
	 * <p>Written as a default declaration rather than with a prefix on purpose. The SOAP body the
	 * Hub takes contains a whole second document in its own namespace, and every element of that
	 * document is qualified ({@code elementFormDefault="qualified"}); declaring the namespace once
	 * at its root means the rest of the builder is prefix-free and reads like the schema. A
	 * prefixed spelling is the same infoset, and would put the prefix in every line of it.
	 *
	 * <p>The namespace is a literal from a schema in every use here, never caller-supplied — an
	 * attacker-controlled URI in a declaration would be a way to smuggle markup that
	 * {@link #attribute} otherwise escapes.
	 */
	public XmlWriter element(String name, String defaultNamespace, Consumer<XmlWriter> body) {
		run(() -> {
			out.writeStartElement(name);
			out.writeDefaultNamespace(defaultNamespace);
		});
		body.accept(this);
		run(() -> {
			out.writeCharacters("");
			out.writeEndElement();
		});

		return this;
	}

	public XmlWriter attribute(String name, String value) {
		run(() -> out.writeAttribute(name, value == null ? "" : value));

		return this;
	}

	/** An element with simple text content, e.g. {@code <name>BirthDate</name>}. */
	public XmlWriter text(String name, String value) {
		run(() -> {
			out.writeStartElement(name);
			out.writeCharacters(value == null ? "" : value);
			out.writeEndElement();
		});

		return this;
	}

	/**
	 * Writes a CDATA section, splitting it if the payload itself contains {@code ]]>}.
	 *
	 * <p>An unsplit {@code ]]>} would terminate the section early and corrupt the enclosing
	 * document — the one escaping hazard StAX does not handle for you.
	 */
	public XmlWriter cdata(String value) {
		String[] chunks = (value == null ? "" : value).split("]]>", -1);
		for (int i = 0; i < chunks.length; i++) {
			if (i > 0) {
				// Written as text so StAX escapes the ">"; it parses back to "]]>".
				final String separator = "]]>";
				run(() -> out.writeCharacters(separator));
			}

			final String chunk = chunks[i];
			run(() -> out.writeCData(chunk));
		}

		return this;
	}

	private void run(XmlAction action) {
		try {
			action.perform();
		}
		catch (XMLStreamException e) {
			throw new IllegalStateException("Could not build XML request", e);
		}
	}

	@FunctionalInterface
	private interface XmlAction {
		void perform() throws XMLStreamException;
	}
}
