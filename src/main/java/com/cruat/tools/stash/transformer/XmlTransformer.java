package com.cruat.tools.stash.transformer;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.io.PrintWriter;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Map.Entry;
import java.util.Optional;
import java.util.Set;

import javax.xml.parsers.DocumentBuilder;
import javax.xml.parsers.DocumentBuilderFactory;
import javax.xml.parsers.ParserConfigurationException;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.w3c.dom.Document;
import org.w3c.dom.NamedNodeMap;
import org.w3c.dom.Node;
import org.w3c.dom.NodeList;
import org.xml.sax.SAXException;

import com.cruat.tools.stash.config.Instruction;
import com.cruat.tools.stash.directives.Directive;
import com.cruat.tools.stash.exceptions.NotImplementedException;
import com.cruat.tools.stash.exceptions.StashException;
import com.cruat.tools.stash.exceptions.StashRuntimeException;
import com.cruat.tools.stash.utils.Serializer;
import com.cruat.tools.stash.utils.XmlUtils;

public class XmlTransformer extends AbstractTransformer {

	public static final String ADD = "add";
	public static final String REMOVE = "remove";
	private static final Logger logger = LogManager.getLogger();
	Document document;

	public XmlTransformer() {
		this(TransformerFactory.CACHE.get(XmlTransformer.class));
	}
	
	XmlTransformer(Set<Directive> directives) {
		super(directives);
	}

	@Override
	public void load(InputStream stream) throws IOException {
		DocumentBuilderFactory docFactory;
		try {
			docFactory = DocumentBuilderFactory.newInstance();

			docFactory.setValidating(false);
			docFactory.setNamespaceAware(true);
			docFactory.setFeature("http://xml.org/sax/features/namespaces", false);
			docFactory.setFeature("http://xml.org/sax/features/validation", false);
			docFactory.setFeature("http://apache.org/xml/features/nonvalidating/load-dtd-grammar", false);
			docFactory.setFeature("http://apache.org/xml/features/nonvalidating/load-external-dtd", false);
			docFactory.setFeature("http://apache.org/xml/features/nonvalidating/load-external-dtd", false);
			docFactory.setFeature("http://xml.org/sax/features/external-general-entities", false);

			DocumentBuilder docBuilder = docFactory.newDocumentBuilder();
			document = docBuilder.parse(stream);
		} catch (ParserConfigurationException e) {
			// this is impossible and not recoverable
			logger.error("Unknown error occurred", e);
			throw new IllegalStateException(e);
		} catch (SAXException e) {
			logger.error("malformed xml loaded", e);
			throw new IllegalArgumentException(e);
		}
	}

	@Override
	public void transformInternal(Map<String, Instruction> kvp) {
		for (Entry<String, Instruction> entry : kvp.entrySet()) {
			String key = entry.getKey();
			Instruction inst = entry.getValue();
			String value = inst.getValue();

			Optional<Node> t = XmlUtils.findFirst(key, document);
			if (t.isPresent()) {
				Node e = t.get();
				if (handleAddIfPresent(inst, e)) {
					logger.trace("added node to [{}]", key);
				} else if (handleRemoveIfPresent(inst, e)) {
					logger.trace("removed node from [{}]", key);
				} else {
					e.setTextContent(value);
					logger.trace(e);
				}
			} else {
				// this is valid but not yet implemented
				// I'd imagine we want to create the node here.
				String err = "Unable to find node with query [{}]";
				logger.error(err, key);
				throw new NotImplementedException();
			}
		}
	}

	@Override
	public void save(OutputStream stream) throws IOException {
		Serializer.serialize(document, new PrintWriter(stream));
	}

	boolean handleAddIfPresent(Instruction i, Node e) {
		if (i.getDirectives().contains(ADD)) {
			String value = i.getValue();
			value = String.format("<wrapper>%s</wrapper>", value);
			byte[] valueBytes = value.getBytes();
			ByteArrayInputStream is = new ByteArrayInputStream(valueBytes);
			try {
				NodeList list = DocumentBuilderFactory.newInstance().newDocumentBuilder().parse(is).getDocumentElement()
						.getChildNodes();
				for (int c = 0; c < list.getLength(); c++) {
					Node n = list.item(c);
					if (!isNodeExisting(e, n)) {
						Node imported = document.importNode(n, true);
						e.appendChild(imported);
					}
				}

			} catch (Exception ex) {
				String err = "error adding {} to {}";
				logger.error(err, i.getValue(), Serializer.serialize(e));
				throw new StashRuntimeException(ex);
			}
			return true;

		} else {
			return false;
		}
	}

	boolean isNodeExisting(Node target, Node searchable) {
		String name = searchable.getNodeName();
		NodeList list = target.getChildNodes();
		NamedNodeMap attrs = searchable.getAttributes();

		// check names
		List<Node> possibleDuplicates = new ArrayList<>();
		for (int i = 0; i < list.getLength(); i++) {
			Node child = list.item(i);
			if (name.equals(child.getNodeName())) {
				possibleDuplicates.add(child);
			}
		}
		// check attributes and values
		Iterator<Node> it = possibleDuplicates.iterator();
		while (it.hasNext()) {
			Node child = it.next();
			if (isAttributesEqual(attrs, child.getAttributes())) {
				return true;
			}
		}
		return false;
	}

	boolean isAttributesEqual(NamedNodeMap a, NamedNodeMap b) {
		if (a == b) {
			return true;
		}

		if (a == null || a.getLength() != b.getLength()) {
			return false;
		}

		for (int i = 0; i < b.getLength(); i++) {
			Node childAttr = b.item(i);
			Node attr = a.getNamedItem(childAttr.getNodeName());
			if (!attr.getNodeValue().equals(childAttr.getNodeValue())) {
				return false;
			}
		}
		return true;
	}

	boolean handleRemoveIfPresent(Instruction i, Node e) {
		if (i.getDirectives().contains(REMOVE)) {
			e.getParentNode().removeChild(e);
			return true;

		} else {
			return false;
		}
	}


	@Override
	public void validateDirectivesInternal(Instruction i) throws StashException {
		List<String> dirs = i.getDirectives();
		if (dirs.contains(REMOVE) && dirs.contains(ADD)) {
			String err = "remove and add directives together is not supported";
			throw new StashException(err);
		}
	}

	@Override
	public Set<Directive> getDirectives() {
		return directives;
	}
}
