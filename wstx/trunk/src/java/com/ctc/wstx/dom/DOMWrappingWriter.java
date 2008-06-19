package com.ctc.wstx.dom;

import java.math.BigDecimal;
import java.math.BigInteger;
import java.text.MessageFormat;
import java.util.*;

import javax.xml.XMLConstants;
import javax.xml.namespace.*;
import javax.xml.stream.*;
import javax.xml.transform.dom.DOMResult;

import org.w3c.dom.*;

import org.codehaus.stax2.XMLStreamLocation2;
import org.codehaus.stax2.XMLStreamReader2;
import org.codehaus.stax2.XMLStreamWriter2;
import org.codehaus.stax2.ri.typed.DefaultValueEncoder;
import org.codehaus.stax2.validation.ValidationProblemHandler;
import org.codehaus.stax2.validation.XMLValidationSchema;
import org.codehaus.stax2.validation.XMLValidator;

import com.ctc.wstx.api.WriterConfig;
import com.ctc.wstx.api.WstxOutputProperties;
import com.ctc.wstx.cfg.ErrorConsts;
import com.ctc.wstx.sw.OutputElementBase;
import com.ctc.wstx.util.EmptyNamespaceContext;

/* TODO:
 *
 * - validator interface implementation
 */

/**
 * This is an adapter class that allows building a DOM tree using
 * {@link XMLStreamWriter} interface.
 *<p>
 * Note that the implementation is only to be used for use with
 * <code>javax.xml.transform.dom.DOMResult</code>.
 *<p>
 * Some notes regarding missing/incomplete functionality:
 * <ul>
 *  </ul>
 *
 * @author Tatu Saloranta
 * @author Dan Diephouse
 */
public class DOMWrappingWriter
    implements XMLStreamWriter2
{
    /*
    ////////////////////////////////////////////////////
    // Constants
    ////////////////////////////////////////////////////
     */

    final protected static String sPrefixXml = "xml";

    final protected static String sPrefixXmlns = "xmlns";

    final protected static String ERR_NSDECL_WRONG_STATE =
        "Trying to write a namespace declaration when there is no open start element.";

    /*
    ////////////////////////////////////////////////////
    // Configuration
    ////////////////////////////////////////////////////
     */

    protected final WriterConfig mConfig;

    protected final boolean mNsAware;

    protected final boolean mNsRepairing;

    /**
     * This member variable is to keep information about encoding
     * that seems to be used for the document (or fragment) to output,
     * if known.
     */
    protected String mEncoding = null;

    /**
     * If we are being given info about existing bindings, it'll come
     * as a NamespaceContet.
     */
    protected NamespaceContext mNsContext;

    protected DefaultValueEncoder mValueEncoder;

    /*
    ////////////////////////////////////////////////////
    // State
    ////////////////////////////////////////////////////
     */

    /**
     * We need a reference to the document hosting nodes to
     * be able to create new nodes
     */
    protected final Document mDocument;

    /**
     * This element is the current context element, under which
     * all other nodes are added, until matching end element
     * is output. Null outside of the main element tree.
     *<p>
     * Note: explicit empty element (written using
     * <code>writeEmptyElement</code>) will never become
     * current element.
     */
    protected DOMOutputElement mCurrElem;

    /**
     * This element is non-null right after a call to
     * either <code>writeStartElement</code> and
     * <code>writeEmptyElement</code>, and can be used to
     * add attributes and namespace declarations.
     *<p>
     * Note: while this is often the same as {@link #mCurrElem},
     * it's not always. Specifically, an empty element (written
     * explicitly using <code>writeEmptyElement</code>) will
     * become open element but NOT current element. Conversely,
     * regular elements will remain current element when
     * non elements are written (text, comments, PI), but
     * not the open element.
     */
    protected DOMOutputElement mOpenElement;

    /**
     *  for NsRepairing mode
     */
    protected int[] mAutoNsSeq;
    protected String mSuggestedDefNs = null;
    protected String mAutomaticNsPrefix;

    /**
     * Map that contains URI-to-prefix entries that point out suggested
     * prefixes for URIs. These are populated by calls to
     * {@link #setPrefix}, and they are only used as hints for binding;
     * if there are conflicts, repairing writer can just use some other
     * prefix.
     */
    HashMap mSuggestedPrefixes = null;

    /*
    ////////////////////////////////////////////////////
    // Life-cycle
    ////////////////////////////////////////////////////
     */
    
    private DOMWrappingWriter(WriterConfig cfg, Node treeRoot)
        throws XMLStreamException
    {
        if (treeRoot == null) {
            throw new IllegalArgumentException("Can not pass null Node for constructing a DOM-based XMLStreamWriter");
        }
        mConfig = cfg;
        mNsAware = cfg.willSupportNamespaces();
        mNsRepairing = mNsAware && cfg.automaticNamespacesEnabled();
        mAutoNsSeq = null;
        mAutomaticNsPrefix = mNsRepairing ? mConfig.getAutomaticNsPrefix() : null;

        Element elem = null;

        /* Ok; we need a document node; or an element node; or a document
         * fragment node.
         */
        switch (treeRoot.getNodeType()) {
        case Node.DOCUMENT_NODE: // fine
            mDocument = (Document) treeRoot;

            /* Should try to find encoding, version and stand-alone
             * settings... but is there a standard way of doing that?
             */
            break;

        case Node.ELEMENT_NODE: // can make sub-tree... ok
            mDocument = treeRoot.getOwnerDocument();
            elem = (Element) treeRoot;
            break;

        case Node.DOCUMENT_FRAGMENT_NODE: // as with element...
            mDocument = treeRoot.getOwnerDocument();
            // Above types are fine
            break;

        default: // other Nodes not usable
            throw new XMLStreamException("Can not create an XMLStreamWriter for a DOM node of type "+treeRoot.getClass());
        }
        if (mDocument == null) {
            throw new XMLStreamException("Can not create an XMLStreamWriter for given node (of type "+treeRoot.getClass()+"): did not have owner document");
        }
        mCurrElem = DOMOutputElement.createRoot();
        if(elem == null) {
            mOpenElement = null;
        } else {
            mOpenElement = mCurrElem = mCurrElem.createChild(elem);
        }
    }

    public static DOMWrappingWriter createFrom(WriterConfig cfg, DOMResult dst)
        throws XMLStreamException
    {
        Node rootNode = dst.getNode();
        return new DOMWrappingWriter(cfg, rootNode);
    }

    /*
    ////////////////////////////////////////////////////
    // XMLStreamWriter API (Stax 1.0)
    ////////////////////////////////////////////////////
     */

    public void close() {
        // NOP
    }

    public void flush() {
        // NOP
    }

    public NamespaceContext getNamespaceContext()
    {
        if (!mNsAware) {
            return EmptyNamespaceContext.getInstance();
        }
        return mCurrElem;
    }

    public String getPrefix(String uri)
    {
        if (!mNsAware) {
            return null;
        }
        if (mNsContext != null) {
            String prefix = mNsContext.getPrefix(uri);
            if (prefix != null) {
                return prefix;
            }
        }
        return mCurrElem.getPrefix(uri);
    }

    public Object getProperty(String name) {
        return mConfig.getProperty(name);
    }

    public void setDefaultNamespace(String uri) {
        mSuggestedDefNs = (uri == null || uri.length() == 0) ? null : uri;
    }

    public void setNamespaceContext(NamespaceContext context) {
        mNsContext = context;
    }

    public void setPrefix(String prefix, String uri)
        throws XMLStreamException
    {
        if (prefix == null) {
            throw new NullPointerException("Can not pass null 'prefix' value");
        }
        // Are we actually trying to set the default namespace?
        if (prefix.length() == 0) {
            setDefaultNamespace(uri);
            return;
        }
        if (uri == null) {
            throw new NullPointerException("Can not pass null 'uri' value");
        }

        /* Let's verify that xml/xmlns are never (mis)declared; as
         * mandated by XML NS specification
         */
        {
            if (prefix.equals("xml")) {
                if (!uri.equals(XMLConstants.XML_NS_URI)) {
                    throwOutputError(ErrorConsts.ERR_NS_REDECL_XML, uri);
                }
            } else if (prefix.equals("xmlns")) { // prefix "xmlns"
                if (!uri.equals(XMLConstants.XMLNS_ATTRIBUTE_NS_URI)) {
                    throwOutputError(ErrorConsts.ERR_NS_REDECL_XMLNS, uri);
                }
            } else {
                // Neither of prefixes.. but how about URIs?
                if (uri.equals(XMLConstants.XML_NS_URI)) {
                    throwOutputError(ErrorConsts.ERR_NS_REDECL_XML_URI, prefix);
                } else if (uri.equals(XMLConstants.XMLNS_ATTRIBUTE_NS_URI)) {
                    throwOutputError(ErrorConsts.ERR_NS_REDECL_XMLNS_URI, prefix);
                }
            }
        }

        if (mSuggestedPrefixes == null) {
            mSuggestedPrefixes = new HashMap(16);
        }
        mSuggestedPrefixes.put(uri, prefix);

    }

    public void writeAttribute(String localName, String value)
        throws XMLStreamException
    {
        outputAttribute(null, null, localName, value);
    }

    public void writeAttribute(String nsURI, String localName, String value)
        throws XMLStreamException
    {
        outputAttribute(nsURI, null, localName, value);
    }

    public void writeAttribute(String prefix, String nsURI, String localName, String value)
        throws XMLStreamException
    {
        outputAttribute(nsURI, prefix, localName, value);
    }

    public void writeCData(String data) {
        appendLeaf(mDocument.createCDATASection(data));
    }

    public void writeCharacters(char[] text, int start, int len)
    {
        writeCharacters(new String(text, start, len));
    }

    public void writeCharacters(String text) {
        appendLeaf(mDocument.createTextNode(text));
    }

    public void writeComment(String data) {
        appendLeaf(mDocument.createCDATASection(data));
    }

    public void writeDefaultNamespace(String nsURI)
    {
        if (mOpenElement == null) {
            throw new IllegalStateException("No currently open START_ELEMENT, cannot write attribute");
        }
        setDefaultNamespace(nsURI);
        mOpenElement.addAttribute(XMLConstants.XMLNS_ATTRIBUTE_NS_URI, "xmlns", nsURI);
    }

    public void writeDTD(String dtd)
    {
        // Would have to parse, not worth trying...
        reportUnsupported("writeDTD()");
    }

    public void writeEmptyElement(String localName)
        throws XMLStreamException
    {
        writeEmptyElement(null, localName);
    }

    public void writeEmptyElement(String nsURI, String localName)
        throws XMLStreamException
    {
        // First things first: must 

        /* Note: can not just call writeStartElement(), since this
         * element will only become the open elem, but not a parent elem
         */
        createStartElem(nsURI, null, localName, true);
    }

    public void writeEmptyElement(String prefix, String localName, String nsURI)  
        throws XMLStreamException
    {
        if (prefix == null) { // passing null would mean "dont care", if repairing
            prefix = "";
        }
        createStartElem(nsURI, prefix, localName, true);
    }

    public void writeEndDocument()
    {
        mCurrElem = mOpenElement = null;
    }

    public void writeEndElement()
    {
        // Simple, just need to traverse up... if we can
        if (mCurrElem == null || mCurrElem.isRoot()) {
            throw new IllegalStateException("No open start element to close");
        }
        mOpenElement = null; // just in case it was open
        mCurrElem = mCurrElem.getParent();
    }

    public void writeEntityRef(String name) {
        appendLeaf(mDocument.createEntityReference(name));
    }

    public void writeNamespace(String prefix, String nsURI) throws XMLStreamException
    {
        if (prefix == null || prefix.length() == 0) {
            writeDefaultNamespace(nsURI);
            return;
        }
        if (!mNsAware) {
            throwOutputError("Can not set write namespaces with non-namespace writer.");
        }
        outputAttribute(XMLConstants.XMLNS_ATTRIBUTE_NS_URI, "xmlns", prefix, nsURI);
        mCurrElem.addPrefix(prefix, nsURI);
    }

    public void writeProcessingInstruction(String target) {
        writeProcessingInstruction(target, null);
    }

    public void writeProcessingInstruction(String target, String data) {
        appendLeaf(mDocument.createProcessingInstruction(target, data));
    }

    public void writeSpace(char[] text, int start, int len) {
        writeSpace(new String(text, start, len));
    }

    public void writeSpace(String text) {
        /* This won't work all that well, given there's no way to
         * prevent quoting/escaping. But let's do what we can, since
         * the alternative (throwing an exception) doesn't seem
         * especially tempting choice.
         */
        writeCharacters(text);
    }

    public void writeStartDocument()
    {
        writeStartDocument(WstxOutputProperties.DEFAULT_OUTPUT_ENCODING,
                WstxOutputProperties.DEFAULT_XML_VERSION);
    }

    public void writeStartDocument(String version)
    {
        writeStartDocument(null, version);
    }

    public void writeStartDocument(String encoding, String version)
    {
        // Is there anything here we can or should do? No?
        mEncoding = encoding;
    }

    public void writeStartElement(String localName)
        throws XMLStreamException
    {
        writeStartElement(null, localName);
    }

    public void writeStartElement(String nsURI, String localName)
        throws XMLStreamException
    {
        createStartElem(nsURI, null, localName, false);
    }

    public void writeStartElement(String prefix, String localName, String nsURI) 
        throws XMLStreamException
    {
        createStartElem(nsURI, prefix, localName, false);
    }

    /*
    /////////////////////////////////////////////////
    // TypedXMLStreamWriter2 implementation
    // (Typed Access API, Stax v3.0)
    /////////////////////////////////////////////////
     */

    // // // Typed element content write methods

    public void writeBoolean(boolean value) throws XMLStreamException
    {
        writeCharacters(value ? "true" : "false");
    }

    public void writeInt(int value) throws XMLStreamException
    {
        writeCharacters(String.valueOf(value));
    }

    public void writeLong(long value) throws XMLStreamException
    {
        writeCharacters(String.valueOf(value));
    }

    public void writeFloat(float value) throws XMLStreamException
    {
        writeCharacters(String.valueOf(value));
    }

    public void writeDouble(double value) throws XMLStreamException
    {
        writeCharacters(String.valueOf(value));
    }

    public void writeInteger(BigInteger value) throws XMLStreamException
    {
        writeCharacters(value.toString());
    }

    public void writeDecimal(BigDecimal value) throws XMLStreamException
    {
        writeCharacters(value.toString());
    }

    public void writeQName(QName name) throws XMLStreamException
    {
        String value = name.getLocalPart();
        String prefix = name.getPrefix();
        if (prefix != null && prefix.length() > 0) {
            value = prefix+":"+value;
        }
        writeCharacters(value);
    }

    public void writeIntArray(int[] value, int from, int length)
        throws XMLStreamException
    {
        /* true -> start with space, to allow for multiple consecutive
         * to be written
         */
        writeCharacters(getValueEncoder().encodeAsString(true, value, from, length));
    }

    // // // Typed attribute value write methods

    public void writeBooleanAttribute(String prefix, String nsURI, String localName, boolean value)
        throws XMLStreamException
    {
        writeAttribute(prefix, nsURI, localName, value ? "true" : "false");
    }

    public void writeIntAttribute(String prefix, String nsURI, String localName, int value)
        throws XMLStreamException
    {
        writeAttribute(prefix, nsURI, localName, String.valueOf(value));
    }

    public void writeLongAttribute(String prefix, String nsURI, String localName, long value)
        throws XMLStreamException
    {
        writeAttribute(prefix, nsURI, localName, String.valueOf(value));
    }

    public void writeFloatAttribute(String prefix, String nsURI, String localName, float value)
        throws XMLStreamException
    {
        writeAttribute(prefix, nsURI, localName, String.valueOf(value));
    }

    public void writeDoubleAttribute(String prefix, String nsURI, String localName, double value)
        throws XMLStreamException
    {
        writeAttribute(prefix, nsURI, localName, String.valueOf(value));
    }

    public void writeIntegerAttribute(String prefix, String nsURI, String localName, BigInteger value)
        throws XMLStreamException
    {
        writeAttribute(prefix, nsURI, localName, value.toString());
    }

    public void writeDecimalAttribute(String prefix, String nsURI, String localName, BigDecimal value)
        throws XMLStreamException
    {
        writeAttribute(prefix, nsURI, localName, value.toString());
    }

    public void writeQNameAttribute(String prefix, String nsURI, String localName, QName name)
        throws XMLStreamException
    {
        String value = name.getLocalPart();
        String vp = name.getPrefix();
        if (vp != null && vp.length() > 0) {
            value = vp+":"+value;
        }
        writeAttribute(prefix, nsURI, localName, value);
    }

    public void writeIntArrayAttribute(String prefix, String nsURI, String localName, int[] value)
        throws XMLStreamException
    {
        // false -> no need to start with a space
        writeAttribute(prefix, nsURI, localName,
                       getValueEncoder().encodeAsString(false, value, 0, value.length));
    }

    /*
    ////////////////////////////////////////////////////
    // XMLStreamWriter2 API (Stax2 v2.0)
    ////////////////////////////////////////////////////
     */

    public boolean isPropertySupported(String name)
    {
        // !!! TBI: not all these properties are really supported
        return mConfig.isPropertySupported(name);
    }

    public boolean setProperty(String name, Object value)
    {
        /* Note: can not call local method, since it'll return false for
         * recognized but non-mutable properties
         */
        return mConfig.setProperty(name, value);
    }

    public XMLValidator validateAgainst(XMLValidationSchema schema)
        throws XMLStreamException
    {
        // !!! TBI
        return null;
    }

    public XMLValidator stopValidatingAgainst(XMLValidationSchema schema)
        throws XMLStreamException
    {
        // !!! TBI
        return null;
    }

    public XMLValidator stopValidatingAgainst(XMLValidator validator)
        throws XMLStreamException
    {
        // !!! TBI
        return null;
    }

    public ValidationProblemHandler setValidationProblemHandler(ValidationProblemHandler h)
    {
        // !!! TBI
        return null;
    }

    public XMLStreamLocation2 getLocation() {
        // !!! TBI
        return null;
    }

    public String getEncoding() {
        return mEncoding;
    }

    public void writeCData(char[] text, int start, int len)
        throws XMLStreamException
    {
        writeCData(new String(text, start, len));
    }

    public void writeDTD(String rootName, String systemId, String publicId,
                         String internalSubset)
        throws XMLStreamException
    {
        /* Alas: although we can create a DocumentType object, there
         * doesn't seem to be a way to attach it in DOM-2!
         */
        if (mCurrElem != null) {
            throw new IllegalStateException("Operation only allowed to the document before adding root element");
        }
        reportUnsupported("writeDTD()");
    }

    public void writeFullEndElement() throws XMLStreamException
    {
        // No difference with DOM
        writeEndElement();
    }

    public void writeStartDocument(String version, String encoding,
                                   boolean standAlone)
        throws XMLStreamException
    {
        writeStartDocument(encoding, version);
    }

    /*
    ///////////////////////////////
    // Stax2, pass-through methods
    ///////////////////////////////
     */

    public void writeRaw(String text)
        throws XMLStreamException
    {
        reportUnsupported("writeRaw()");
    }

    public void writeRaw(String text, int start, int offset)
        throws XMLStreamException
    {
        reportUnsupported("writeRaw()");
    }

    public void writeRaw(char[] text, int offset, int length)
        throws XMLStreamException
    {
        reportUnsupported("writeRaw()");
    }

    public void copyEventFromReader(XMLStreamReader2 r, boolean preserveEventData)
        throws XMLStreamException
    {
        // !!! TBI
    }

    /*
    ///////////////////////////////
    // Internal methods
    ///////////////////////////////
     */

    protected void appendLeaf(Node n)
        throws IllegalStateException
    {
        mCurrElem.appendNode(n);
        mOpenElement = null;
    }

    /* Note: copied from regular RepairingNsStreamWriter#writeStartOrEmpty
     * (and its non-repairing counterpart)
     */
    protected void createStartElem(String nsURI, String prefix, String localName, boolean isEmpty)
        throws XMLStreamException
    {
        DOMOutputElement elem;

        if (!mNsAware) {
            if(nsURI != null && nsURI.length() > 0) {
                throwOutputError("Can not specify non-empty uri/prefix in non-namespace mode");
            }
            elem =  mCurrElem.createChild(mDocument.createElement(localName));
        } else {
            if (mNsRepairing) {
                String actPrefix = validateElemPrefix(prefix, nsURI, mCurrElem);
                if(actPrefix != null && actPrefix.length() != 0) {// fine, an existing binding we can use:
                    elem = mCurrElem.createChild(mDocument.createElementNS(nsURI, actPrefix+":"+localName));
                } else { // nah, need to create a new binding...
                    /* Need to ensure that we'll pass "" as prefix, not null,
                     * so it is understood as "I want to use the default NS",
                     * not as "whatever prefix, I don't care"
                     */
                    if (prefix == null) {
                        prefix = "";
                    }
                    actPrefix = generateElemPrefix(prefix, nsURI, mCurrElem);
                    boolean hasPrefix = (actPrefix.length() != 0);
                    if (hasPrefix) {
                        localName = actPrefix + ":" + localName;
                    }
                    elem = mCurrElem.createChild(mDocument.createElementNS(nsURI, localName));
                    /* Hmmh. writeNamespace method requires open element
                     * to be defined. So we'll need to set it first
                     * (will be set again at a later point -- would be
                     * good to refactor this method into separate
                     * sub-classes or so)
                     */
                    mOpenElement = elem;
                    // Need to add new ns declaration as well
                    if (hasPrefix) {
                        writeNamespace(actPrefix, nsURI);
                        elem.addPrefix(actPrefix, nsURI);
                    } else {
                        writeDefaultNamespace(nsURI);
                        elem.setDefaultNsUri(nsURI);
                    }
                }
            } else {
                /* Non-repairing; if non-null prefix (including "" to
                 * indicate "no prefix") passed, use as is, otherwise
                 * try to locate the prefix
                 */
                if (prefix == null) {
                    if (nsURI == null) {
                        nsURI = "";
                    }
                    prefix = (mSuggestedPrefixes == null) ? null : (String) mSuggestedPrefixes.get(nsURI);
                    if (prefix == null) {
                        throwOutputError("Can not find prefix for namespace \""+nsURI+"\"");
                    }
                }
                if (prefix.length() != 0) {
                    localName = prefix + ":" +localName;
                }
                elem = mCurrElem.createChild(mDocument.createElementNS(nsURI, localName));
            }
        }
        /* Got the element; need to make it the open element, and
         * if it's not an (explicit) empty element, current element as well
         */
        mOpenElement = elem;
        if (!isEmpty) {
            mCurrElem = elem;
        }
    }

    protected void outputAttribute(String nsURI, String prefix, String localName, String value)
        throws XMLStreamException
    {
        if (mOpenElement == null) {
            throw new IllegalStateException("No currently open START_ELEMENT, cannot write attribute");
        }

        if (mNsAware) {
            if (mNsRepairing) {
                prefix = findOrCreateAttrPrefix(prefix, nsURI, mOpenElement);
            }
            if (prefix != null && prefix.length() > 0) {
                localName = prefix + ":" + localName;
            }
            mOpenElement.addAttribute(nsURI, localName, value);
        } else { // non-ns, simple
            if (prefix != null && prefix.length() > 0) {
                localName = prefix + ":" + localName;
            }
            mOpenElement.addAttribute(localName, value);
        }
    }

    private void reportUnsupported(String operName)
    {
        throw new UnsupportedOperationException(operName+" can not be used with DOM-backed writer");
    }

    private final String validateElemPrefix(String prefix, String nsURI,
                                            DOMOutputElement elem)
        throws XMLStreamException
    {
        /* 06-Feb-2005, TSa: Special care needs to be taken for the
         *   "empty" (or missing) namespace:
         *   (see comments from findOrCreatePrefix())
         */
        if (nsURI == null || nsURI.length() == 0) {
            String currURL = elem.getDefaultNsUri();
            if (currURL == null || currURL.length() == 0) {
                // Ok, good:
                return "";
            }
            // Nope, needs to be re-bound:
            return null;
        }

        int status = elem.isPrefixValid(prefix, nsURI, true);
        if (status == DOMOutputElement.PREFIX_OK) {
            return prefix;
        }
        return null;
    }

    /*
    ////////////////////////////////////////////////////
    // Internal methods
    ////////////////////////////////////////////////////
     */

    /**
     * Method called to find an existing prefix for the given namespace,
     * if any exists in the scope. If one is found, it's returned (including
     * "" for the current default namespace); if not, null is returned.
     *
     * @param nsURI URI of namespace for which we need a prefix
     */
    protected final String findElemPrefix(String nsURI, DOMOutputElement elem)
        throws XMLStreamException
    {
        /* Special case: empty NS URI can only be bound to the empty
         * prefix...
         */
        if (nsURI == null || nsURI.length() == 0) {
            String currDefNsURI = elem.getDefaultNsUri();
            if (currDefNsURI != null && currDefNsURI.length() > 0) {
                // Nope; won't do... has to be re-bound, but not here:
                return null;
            }
            return "";
        }
        return mCurrElem.getPrefix(nsURI);
    }
    
    
    /**
     * Method called after {@link #findElemPrefix} has returned null,
     * to create and bind a namespace mapping for specified namespace.
     */
    protected final String generateElemPrefix(String suggPrefix, String nsURI,
                                              DOMOutputElement elem)
        throws XMLStreamException
    {
        /* Ok... now, since we do not have an existing mapping, let's
         * see if we have a preferred prefix to use.
         */
        /* Except if we need the empty namespace... that can only be
         * bound to the empty prefix:
         */
        if (nsURI == null || nsURI.length() == 0) {
            return "";
        }

        /* Ok; with elements this is easy: the preferred prefix can
         * ALWAYS be used, since it can mask preceding bindings:
         */
        if (suggPrefix == null) {
            // caller wants this URI to map as the default namespace?
            if (mSuggestedDefNs != null && mSuggestedDefNs.equals(nsURI)) {
                suggPrefix = "";
            } else {
                suggPrefix = (mSuggestedPrefixes == null) ? null:
                    (String) mSuggestedPrefixes.get(nsURI);
                if (suggPrefix == null) {
                    /* 16-Oct-2005, TSa: We have 2 choices here, essentially;
                     *   could make elements always try to override the def
                     *   ns... or can just generate new one. Let's do latter
                     *   for now.
                     */
                    if (mAutoNsSeq == null) {
                        mAutoNsSeq = new int[1];
                        mAutoNsSeq[0] = 1;
                    }
                    suggPrefix = elem.generateMapping(mAutomaticNsPrefix, nsURI,
                                                      mAutoNsSeq);
                }
            }
        }

        // Ok; let's let the caller deal with bindings
        return suggPrefix;
    }
    
    
    /**
     * Method called to somehow find a prefix for given namespace, to be
     * used for a new start element; either use an existing one, or
     * generate a new one. If a new mapping needs to be generated,
     * it will also be automatically bound, and necessary namespace
     * declaration output.
     *
     * @param suggPrefix Suggested prefix to bind, if any; may be null
     *   to indicate "no preference"
     * @param nsURI URI of namespace for which we need a prefix
     * @param elem Currently open start element, on which the attribute
     *   will be added.
     */
    protected final String findOrCreateAttrPrefix(String suggPrefix, String nsURI,
                                                  DOMOutputElement elem)
        throws XMLStreamException
    {
        if (nsURI == null || nsURI.length() == 0) {
            /* Attributes never use the default namespace; missing
             * prefix always leads to the empty ns... so nothing
             * special is needed here.
             */
             return null;
        }
        // Maybe the suggested prefix is properly bound?
        if (suggPrefix != null) {
            int status = elem.isPrefixValid(suggPrefix, nsURI, false);
            if (status == OutputElementBase.PREFIX_OK) {
                return suggPrefix;
            }
            /* Otherwise, if the prefix is unbound, let's just bind
             * it -- if caller specified a prefix, it probably prefers
             * binding that prefix even if another prefix already existed?
             * The remaining case (already bound to another URI) we don't
             * want to touch, at least not yet: it may or not be safe
             * to change binding, so let's just not try it.
             */
            if (status == OutputElementBase.PREFIX_UNBOUND) {
                elem.addPrefix(suggPrefix, nsURI);
                writeNamespace(suggPrefix, nsURI);
                return suggPrefix;
            }
        }

        // If not, perhaps there's another existing binding available?
        String prefix = elem.getExplicitPrefix(nsURI);
        if (prefix != null) { // already had a mapping for the URI... cool.
            return prefix;
        }

        /* Nope, need to create one. First, let's see if there's a
         * preference...
         */
        if (suggPrefix != null) {
            prefix = suggPrefix;
        } else if (mSuggestedPrefixes != null) {
            prefix = (String) mSuggestedPrefixes.get(nsURI);
            // note: def ns is never added to suggested prefix map
        }

        if (prefix != null) {
            /* Can not use default namespace for attributes.
             * Also, re-binding is tricky for attributes; can't
             * re-bind anything that's bound on this scope... or
             * used in this scope. So, to simplify life, let's not
             * re-bind anything for attributes.
             */
            if (prefix.length() == 0
                || (elem.getNamespaceURI(prefix) != null)) {
                prefix = null;
            }
        }

        if (prefix == null) {
            if (mAutoNsSeq == null) {
                mAutoNsSeq = new int[1];
                mAutoNsSeq[0] = 1;
            }
            prefix = mCurrElem.generateMapping(mAutomaticNsPrefix, nsURI,
                                               mAutoNsSeq);
        }

        // Ok; so far so good: let's now bind and output the namespace:
        elem.addPrefix(prefix, nsURI);
        writeNamespace(prefix, nsURI);
        return prefix;
    }
    
    
    /*
    ////////////////////////////////////////////////////
    // Package methods, basic output problem reporting
    ////////////////////////////////////////////////////
     */

    protected static void throwOutputError(String msg)
        throws XMLStreamException
    {
        throw new XMLStreamException(msg);
    }

    protected static void throwOutputError(String format, Object arg)
        throws XMLStreamException
    {
        String msg = MessageFormat.format(format, new Object[] { arg });
        throwOutputError(msg);
    }

    protected DefaultValueEncoder getValueEncoder()
    {
        if (mValueEncoder == null) {
            mValueEncoder = new DefaultValueEncoder();
        }
        return mValueEncoder;
    }
}
