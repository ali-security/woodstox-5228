/* Woodstox XML processor
 *
 * Copyright (c) 2004- Tatu Saloranta, tatu.saloranta@iki.fi
 *
 * Licensed under the License specified in file LICENSE, included with
 * the source code.
 * You may not use this file except in compliance with the License.
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.ctc.wstx.sw;

import java.io.IOException;
import java.io.Writer;
import java.math.BigDecimal;
import java.math.BigInteger;
import java.text.MessageFormat;

import javax.xml.namespace.NamespaceContext;
import javax.xml.namespace.QName;
import javax.xml.stream.Location;
import javax.xml.stream.XMLReporter;
import javax.xml.stream.XMLStreamConstants;
import javax.xml.stream.XMLStreamException;
import javax.xml.stream.XMLStreamWriter;

// unfortunate dependencies to StAX events:
import javax.xml.stream.events.Characters;
import javax.xml.stream.events.StartElement;

import org.codehaus.stax2.DTDInfo;
import org.codehaus.stax2.XMLStreamLocation2;
import org.codehaus.stax2.XMLStreamReader2;
import org.codehaus.stax2.XMLStreamWriter2;
import org.codehaus.stax2.validation.*;

import com.ctc.wstx.api.WriterConfig;
import com.ctc.wstx.api.WstxOutputProperties;
import com.ctc.wstx.cfg.ErrorConsts;
import com.ctc.wstx.cfg.OutputConfigFlags;
import com.ctc.wstx.cfg.XmlConsts;
import com.ctc.wstx.exc.*;
import com.ctc.wstx.io.WstxInputLocation;
import com.ctc.wstx.sr.StreamReaderImpl;
import com.ctc.wstx.sr.AttributeCollector;
import com.ctc.wstx.sr.InputElementStack;
import com.ctc.wstx.util.DataUtil;
import com.ctc.wstx.util.NumberUtil;
import com.ctc.wstx.util.StringUtil;

/**
 * Base class for {@link XMLStreamWriter} implementations Woodstox has.
 * Contains partial stream writer implementation, plus utility methods
 * shared by concrete implementation classes. Main reason for such
 * abstract base class is to allow other parts of Woodstox core to refer
 * to any of stream writer implementations in general way.
 */
public abstract class BaseStreamWriter
    implements XMLStreamWriter2, ValidationContext,
               XMLStreamConstants, OutputConfigFlags
{
    protected final static int STATE_PROLOG = 1;
    protected final static int STATE_TREE = 2;
    protected final static int STATE_EPILOG = 3;

    protected final static char CHAR_SPACE = ' ';

    /**
     * This constant defines minimum length of a String, for which it
     * is beneficial to do an intermediate copy (using String.getChars()),
     * and iterate over intermediate array, instead of iterating using
     * String.charAt(). Former is generally faster for longer Strings, but
     * has some overhead for shorter Strings. Tests indicate that the
     * threshold is somewhere between 8 and 16 characters, at least on
     * x86 platform.
     */
    protected final static int MIN_ARRAYCOPY = 12;

    protected final static int ATTR_MIN_ARRAYCOPY = 12;

    protected final static int DEFAULT_COPYBUFFER_LEN = 512;

    /*
    ////////////////////////////////////////////////////
    // Output objects
    ////////////////////////////////////////////////////
     */

    /**
     * Actual physical writer to output serialized XML content to
     */
    protected final XmlWriter mWriter;
    
    /**
     * Intermediate buffer into which characters of a String can be
     * copied, in cases where such a copy followed by array access
     * is faster than calling <code>String.charAt()</code> (which
     * perhaps surprisingly is often case, and especially significant
     * for longer buffers).
     */
    protected char[] mCopyBuffer = null;

    /*
    ////////////////////////////////////////////////////
    // Per-factory configuration (options, features)
    ////////////////////////////////////////////////////
     */

    protected final WriterConfig mConfig;

    // // // Specialized configuration flags, extracted from config flags:

    protected final boolean mCfgCDataAsText;
    protected final boolean mCfgCopyDefaultAttrs;
    protected final boolean mCfgAutomaticEmptyElems;

    // NOTE: can not be final, may be enabled when schema (etc) validation enabled

    protected boolean mCheckStructure;
    protected boolean mCheckAttrs;

    /*
    ////////////////////////////////////////////////////
    // Per-writer configuration
    ////////////////////////////////////////////////////
     */

    /**
     * Encoding to use; may be passed from the factory (when
     * a method that defines encoding is used), updated by
     * a call to {@link #writeStartDocument}, or null if
     * neither. Is passed to the escaping writer factory to
     * allow escaping writers to do additional escaping if
     * necessary (like encapsulating non-ascii chars in a doc
     * encoded usig ascii).
     */
    protected String mEncoding;

    /**
     * Optional validator to use for validating output against
     * one or more schemas, and/or for safe pretty-printing (indentation).
     */
    protected XMLValidator mValidator = null;
    
    /**
     * Since XML 1.1 has some differences to 1.0, we need to keep a flag
     * to indicate if we were to output XML 1.1 document.
     */
    protected boolean mXml11 = false;

    /**
     * Custom validation problem handler, if any.
     */
    protected ValidationProblemHandler mVldProbHandler = null;

    /*
    ////////////////////////////////////////////////////
    // State information
    ////////////////////////////////////////////////////
     */

    protected int mState = STATE_PROLOG;

    /**
     * Flag that is set to true first time something has been output.
     * Generally needed to keep track of whether XML declaration
     * (START_DOCUMENT) can be output or not.
     */
    protected boolean mAnyOutput = false;

    /**
     * Flag that is set during time that a start element is "open", ie.
     * START_ELEMENT has been output (and possibly zero or more name
     * space declarations and attributes), before other main-level
     * constructs have been output.
     */
    protected boolean mStartElementOpen = false;

    /**
     * Flag that indicates that current element is an empty element (one
     * that is explicitly defined as one, by calling a method -- NOT one
     * that just happens to be empty).
     * This is needed to know what to do when next non-ns/attr node
     * is output; normally a new context is opened, but for empty
     * elements not.
     */
    protected boolean mEmptyElement = false;

    /**
     * State value used with validation, to track types of content
     * that is allowed at this point in output stream. Only used if
     * validation is enabled: if so, value is determined via validation
     * callbacks.
     */
    protected int mVldContent = XMLValidator.CONTENT_ALLOW_ANY_TEXT;

    /**
     * Value passed as the expected root element, when using the multiple
     * argument {@link #writeDTD} method. Will be used in structurally
     * validating mode (and in dtd-validating mode, since that automatically
     * enables structural validation as well, to pre-filter well-formedness
     * errors that validators might have trouble dealing with).
     */
    protected String mDtdRootElem = null;

    /*
    ////////////////////////////////////////////////////
    // Problem reporting state
    ////////////////////////////////////////////////////
     */

    // // These are bit masks used for ensuring only one of each problem
    // // type is reported via a single stream writer instance

    //final static int PROB_NS_WRITE = 0x0001;

    /**
     * Bit mask used to ensure certain problems are only reported the
     * first time they occur
     */
    //int mReportedProblems = 0;


    /*
    ////////////////////////////////////////////////////
    // State needed for efficient copy-through output
    // (copyEventFromReader)
    ////////////////////////////////////////////////////
     */

    /**
     * Reader that was last used for copy-through operation;
     * used in conjunction with the other copy-through state
     * variables.
     */
    protected XMLStreamReader2 mLastReader = null;

    protected StreamReaderImpl mLastReaderImpl = null;

    protected AttributeCollector mAttrCollector = null;

    protected InputElementStack mInputElemStack = null;

    /*
    ////////////////////////////////////////////////////
    // Life-cycle (ctors)
    ////////////////////////////////////////////////////
     */

    protected BaseStreamWriter(XmlWriter xw, String enc, WriterConfig cfg)
    {
        mWriter = xw;
        mEncoding = enc;
        mConfig = cfg;

        int flags = cfg.getConfigFlags();

        mCheckStructure = (flags & OutputConfigFlags.CFG_VALIDATE_STRUCTURE) != 0;
        mCheckAttrs = (flags & OutputConfigFlags.CFG_VALIDATE_ATTR) != 0;

        mCfgAutomaticEmptyElems = (flags & OutputConfigFlags.CFG_AUTOMATIC_EMPTY_ELEMS) != 0;
        mCfgCDataAsText = (flags & OutputConfigFlags.CFG_OUTPUT_CDATA_AS_TEXT) != 0;
        mCfgCopyDefaultAttrs = (flags & OutputConfigFlags.CFG_COPY_DEFAULT_ATTRS) != 0;
    }

    /*
    ////////////////////////////////////////////////////
    // XMLStreamWriter API
    ////////////////////////////////////////////////////
     */

    public void close()
        throws XMLStreamException
    {
        /* 19-Jul-2004, TSa: Hmmh. Let's actually close all still open
         *    elements, starting with currently open start (-> empty)
         *    element, if one exists, and then closing scopes by adding
         *    matching end elements.
         */
        finishDocument();
    }

    public void flush()
        throws XMLStreamException
    {
        /* Note: there have been changes to exact scope of flushing
         * (with Woodstox versions 2.x and 3.x); but the current 
         * one of just flushing the underlying OutputStream or Writer
         * should be the interpretation compatible with the Stax specs.
         */
        try {
            mWriter.flush();
        } catch (IOException ie) {
            throw new WstxIOException(ie);
        }
    }

    public abstract NamespaceContext getNamespaceContext();

    public abstract String getPrefix(String uri);

    public Object getProperty(String name)
    {
        /* These properties just exist for interoperability with
         * toolkits that were designed to work with Sun's parser (which
         * introduced properties)
         */
        if (name.equals(WstxOutputProperties.P_OUTPUT_UNDERLYING_STREAM)) {
            return mWriter.getOutputStream();
        }
        if (name.equals(WstxOutputProperties.P_OUTPUT_UNDERLYING_WRITER)) {
            return mWriter.getWriter();
        }
        return mConfig.getProperty(name);
    }

    public abstract void setDefaultNamespace(String uri)
        throws XMLStreamException;

    public abstract void setNamespaceContext(NamespaceContext context)
        throws XMLStreamException;

    public abstract void setPrefix(String prefix, String uri)
        throws XMLStreamException;

    public abstract void writeAttribute(String localName, String value)
        throws XMLStreamException;
    
    public abstract void writeAttribute(String nsURI, String localName,
                                        String value)
        throws XMLStreamException;

    public abstract void writeAttribute(String prefix, String nsURI,
                                        String localName, String value)
        throws XMLStreamException;

    public void writeCData(String data)
        throws XMLStreamException
    {
        /* 02-Dec-2004, TSa: Maybe the writer is to "re-direct" these
         *   writes as normal text? (sometimes useful to deal with broken
         *   XML parsers, for example)
         */
        if (mCfgCDataAsText) {
            writeCharacters(data);
            return;
        }

        mAnyOutput = true;
        // Need to finish an open start element?
        if (mStartElementOpen) {
            closeStartElement(mEmptyElement);
        }
        verifyWriteCData();
        if (mVldContent == XMLValidator.CONTENT_ALLOW_VALIDATABLE_TEXT
            && mValidator != null) {
            /* Last arg is false, since we do not know if more text
             * may be added with additional calls
             */
            mValidator.validateText(data, false);
        }
        int ix;
        try {
            ix = mWriter.writeCData(data);
        } catch (IOException ioe) {
            throw new WstxIOException(ioe);
        }
        if (ix >= 0) { // unfixable problems?
            reportNwfContent(ErrorConsts.WERR_CDATA_CONTENT, DataUtil.Integer(ix));
        }
    }

    public void writeCharacters(char[] text, int start, int len)
        throws XMLStreamException
    {
        mAnyOutput = true;
        // Need to finish an open start element?
        if (mStartElementOpen) {
            closeStartElement(mEmptyElement);
        }

        /* Not legal outside main element tree, except if it's all
         * white space
         */
        if (mCheckStructure) {
            if (inPrologOrEpilog()) {
                if (!StringUtil.isAllWhitespace(text, start, len)) {
                    reportNwfStructure(ErrorConsts.WERR_PROLOG_NONWS_TEXT);
                }
            }
        }
        // 08-Dec-2005, TSa: validator-based validation?
        if (mVldContent <= XMLValidator.CONTENT_ALLOW_WS) {
            if (mVldContent == XMLValidator.CONTENT_ALLOW_NONE) { // never ok
                reportInvalidContent(CHARACTERS);
            } else { // all-ws is ok...
                if (!StringUtil.isAllWhitespace(text, start, len)) {
                    reportInvalidContent(CHARACTERS);
                }
            }
        } else if (mVldContent == XMLValidator.CONTENT_ALLOW_VALIDATABLE_TEXT) {
            if (mValidator != null) {
                /* Last arg is false, since we do not know if more text
                 * may be added with additional calls
                 */
                mValidator.validateText(text, start, len, false);
            }
        }

        if (len > 0) { // minor optimization
            try {
                /* 21-Jun-2006, TSa: Fixing [WSTX-59]: no quoting can be done
                 *   outside of element tree.
                 */
                if (inPrologOrEpilog()) {
                    mWriter.writeRaw(text, start, len);
                } else {
                    mWriter.writeCharacters(text, start, len);
                }
            } catch (IOException ioe) {
                throw new WstxIOException(ioe);
            }
        }
    }

    public void writeCharacters(String text)
        throws XMLStreamException
    {
        mAnyOutput = true;
        // Need to finish an open start element?
        if (mStartElementOpen) {
            closeStartElement(mEmptyElement);
        }

        // Need to validate structure?
        if (mCheckStructure) {
            // Not valid in prolog/epilog, except if it's all white space:
            if (inPrologOrEpilog()) {
                if (!StringUtil.isAllWhitespace(text)) {
                    reportNwfStructure(ErrorConsts.WERR_PROLOG_NONWS_TEXT);
                }
            }
        }

        /* 08-Dec-2005, TSa: validator-based validation?
         *   Note: although it'd be good to check validity first, we
         *   do not know allowed textual content before actually writing
         *   pending start element (if any)... so can't call this earlier
         */
        if (mVldContent <= XMLValidator.CONTENT_ALLOW_WS) {
            if (mVldContent == XMLValidator.CONTENT_ALLOW_NONE) { // never ok
                reportInvalidContent(CHARACTERS);
            } else { // all-ws is ok...
                if (!StringUtil.isAllWhitespace(text)) {
                    reportInvalidContent(CHARACTERS);
                }
            }
        } else if (mVldContent == XMLValidator.CONTENT_ALLOW_VALIDATABLE_TEXT) {
            if (mValidator != null) {
                /* Last arg is false, since we do not know if more text
                 * may be added with additional calls
                 */
                mValidator.validateText(text, false);
            }
        }

        // Ok, let's just write it out
        /* 21-Jun-2006, TSa: Fixing [WSTX-59]: no quoting can be done
         *   outside of element tree.
         */
        if (inPrologOrEpilog()) {
            try {
                mWriter.writeRaw(text);
            } catch (IOException ioe) {
                throw new WstxIOException(ioe);
            }
            return;
        }

        /* Now, would it pay off to make an intermediate copy?
         * String.getChars (which uses System.arraycopy()) is
         * very fast compared to access via String.charAt.
         */
        int len = text.length();
        if (len >= MIN_ARRAYCOPY) {
            char[] buf = getCopyBuffer();

            int offset = 0;
            while (len > 0) {
                int thisLen = (len > buf.length) ? buf.length : len;
                text.getChars(offset, offset+thisLen, buf, 0);
                try {
                    mWriter.writeCharacters(buf, 0, thisLen);
                } catch (IOException ioe) {
                    throw new WstxIOException(ioe);
                }
                offset += thisLen;
                len -= thisLen;
            }
        } else { // nope, let's just access String using charAt().
            try {
                mWriter.writeCharacters(text);
            } catch (IOException ioe) {
                throw new WstxIOException(ioe);
            }
        }
    }

    public void writeComment(String data)
        throws XMLStreamException
    {
        mAnyOutput = true;
        // Need to finish an open start element?
        if (mStartElementOpen) {
            closeStartElement(mEmptyElement);
        }

        // 08-Dec-2005, TSa: validator-based validation?
        if (mVldContent == XMLValidator.CONTENT_ALLOW_NONE) {
            reportInvalidContent(COMMENT);
        }

        /* No structural validation needed per se, for comments; they are
         * allowed anywhere in XML content. However, content may need to
         * be checked (by XmlWriter)
         */
        int ix;
        try {
            ix = mWriter.writeComment(data);
        } catch (IOException ioe) {
            throw new WstxIOException(ioe);
        }

        if (ix >= 0) {
            reportNwfContent(ErrorConsts.WERR_COMMENT_CONTENT, DataUtil.Integer(ix));
        }
    }

    public abstract void writeDefaultNamespace(String nsURI)
        throws XMLStreamException;

    public void writeDTD(String dtd)
        throws XMLStreamException
    {
        verifyWriteDTD();
        mDtdRootElem = ""; // marker to verify only one is output
        try {
            mWriter.writeDTD(dtd);
        } catch (IOException ioe) {
            throw new WstxIOException(ioe);
        }

        /* 20-Dec-2005, TSa: Should we try to decipher what was actually
         *   written, for validation?
         */
    }

    public abstract void writeEmptyElement(String localName)
        throws XMLStreamException;

    public abstract void writeEmptyElement(String nsURI, String localName)
        throws XMLStreamException;

    public abstract void writeEmptyElement(String prefix, String localName, String nsURI)
        throws XMLStreamException;

    public void writeEndDocument() throws XMLStreamException
    {
        finishDocument();
    }

    public abstract void writeEndElement() throws XMLStreamException;

    public void writeEntityRef(String name)
        throws XMLStreamException
    {
        mAnyOutput = true;
        // Need to finish an open start element?
        if (mStartElementOpen) {
            closeStartElement(mEmptyElement);
        }

        // Structurally, need to check we are not in prolog/epilog.
        if (mCheckStructure) {
            if (inPrologOrEpilog()) {
                reportNwfStructure("Trying to output an entity reference outside main element tree (in prolog or epilog)");
            }
        }
        // 08-Dec-2005, TSa: validator-based validation?
        if (mVldContent == XMLValidator.CONTENT_ALLOW_NONE) {
            /* May be char entity, general entity; whatever it is it's
             * invalid!
             */
            reportInvalidContent(ENTITY_REFERENCE);
        }

        //if (mValidator != null) {
            /* !!! 11-Dec-2005, TSa: Should be able to use DTD based validators
             *    to check if entity has been declared...
             */
        //}

        try {
            mWriter.writeEntityReference(name);
        } catch (IOException ioe) {
            throw new WstxIOException(ioe);
        }
    }

    public abstract void writeNamespace(String prefix, String nsURI)
        throws XMLStreamException;

    public void writeProcessingInstruction(String target)
        throws XMLStreamException
    {
        writeProcessingInstruction(target, null);
    }

    public void writeProcessingInstruction(String target, String data)
        throws XMLStreamException
    {
        mAnyOutput = true;
        // Need to finish an open start element?
        if (mStartElementOpen) {
            closeStartElement(mEmptyElement);
        }

        // Structurally, PIs are always ok (content might not be)
        // 08-Dec-2005, TSa: validator-based validation?
        if (mVldContent == XMLValidator.CONTENT_ALLOW_NONE) {
            reportInvalidContent(PROCESSING_INSTRUCTION);
        }
        int ix;
        try {
            ix = mWriter.writePI(target, data);
        } catch (IOException ioe) {
            throw new WstxIOException(ioe);
        }
        if (ix >= 0) {
            throw new XMLStreamException("Illegal input: processing instruction content has embedded '?>' in it (index "+ix+")");
        }
    }

    public void writeStartDocument()
        throws XMLStreamException
    {
        /* 03-Feb-2005, TSa: As per StAX 1.0 specs, version should
         *   be "1.0", and encoding "utf-8" (yes, lower case... it's
         *   wrong, but specs mandate it)
         */
        /* 11-Jan-2006, TSa: Let's actually rather use whatever was passed
         *   in, if anything; only if none then default to something else.
         *   Plus, what the heck; let's use properly capitalized value
         *   too (and ignore faulty def in stax specs).
         */
        if (mEncoding == null) {
            mEncoding = WstxOutputProperties.DEFAULT_OUTPUT_ENCODING;
        }
        writeStartDocument(mEncoding, WstxOutputProperties.DEFAULT_XML_VERSION);
    }

    public void writeStartDocument(String version)
        throws XMLStreamException
    {
        writeStartDocument(mEncoding, version);
    }

    public void writeStartDocument(String encoding, String version)
        throws XMLStreamException
    {
        doWriteStartDocument(version, encoding, null);
    }

    protected void doWriteStartDocument(String version, String encoding,
                                        String standAlone)
        throws XMLStreamException
    {
        /* Not legal to output XML declaration if there has been ANY
         * output prior... that is, if we validate the structure.
         */
        if (mCheckStructure) {
            if (mAnyOutput) {
                reportNwfStructure("Can not output XML declaration, after other output has already been done.");
            }
        }

        mAnyOutput = true;

        if (mConfig.willValidateContent()) {
            // !!! 06-May-2004, TSa: Should validate encoding?
            /*if (encoding != null) {
            }*/
            if (version != null && version.length() > 0) {
                if (!(version.equals(XmlConsts.XML_V_10_STR)
                      || version.equals(XmlConsts.XML_V_11_STR))) {
                    reportNwfContent("Illegal version argument ('"+version
                                     +"'); should only use '"+XmlConsts.XML_V_10_STR
                                    +"' or '"+XmlConsts.XML_V_11_STR+"'");
                }
            }
        }

        if (version == null || version.length() == 0) {
            version = WstxOutputProperties.DEFAULT_XML_VERSION;
        }

        /* 04-Feb-2006, TSa: Need to know if we are writing XML 1.1
         *   document...
         */
        mXml11 = XmlConsts.XML_V_11_STR.equals(version);
        if (mXml11) {
            mWriter.enableXml11();
        }

        if (encoding != null && encoding.length() > 0) {
            /* 03-May-2005, TSa: But what about conflicting encoding? Let's
             *   only update encoding, if it wasn't set.
             */
            if (mEncoding == null || mEncoding.length() == 0) {
                mEncoding = encoding;
            }
        }
        try {
            mWriter.writeXmlDeclaration(version, encoding, standAlone);
        } catch (IOException ioe) {
            throw new WstxIOException(ioe);
        }
    }

    public abstract void writeStartElement(String localName)
        throws XMLStreamException;

    public abstract void writeStartElement(String nsURI, String localName)
        throws XMLStreamException;

    public abstract void writeStartElement(String prefix, String localName,
                                           String nsURI)
        throws XMLStreamException;
    
    /*
    /////////////////////////////////////////////////
    // TypedXMLStreamWriter2 implementation
    // (Typed Access API, Stax v3.0)
    /////////////////////////////////////////////////
     */

    /* There is still some overhead in serializing Strings, compared
     * to serializing character arrays (as proven by profiling which
     * resulted in making intermediate copies, which still was faster
     * than iterating over characters). Because of this, it makes
     * sense to pre-fetch char arrays for constants we need.
     */

    final static char[] TYPE_CONST_TRUE = "true".toCharArray();
    final static char[] TYPE_CONST_FALSE = "false".toCharArray();

    // // // Typed element content write methods

    public void writeBoolean(boolean b)
        throws XMLStreamException
    {
        char[] value = b ? TYPE_CONST_TRUE : TYPE_CONST_FALSE;
        writeCharacters(value, 0, value.length);
    }

    public void writeInt(int value)
        throws XMLStreamException
    {
        // Copy buffer always has room for needed (11) chars...
        char[] buf = getCopyBuffer();
        int len = NumberUtil.writeInt(value, buf, 0);
        doWriteTyped(buf, 0, len);
    }

    public void writeLong(long value)
        throws XMLStreamException
    {
        // Copy buffer always has room for needed (21) chars...
        char[] buf = getCopyBuffer();
        int len = NumberUtil.writeLong(value, buf, 0);
        doWriteTyped(buf, 0, len);
    }

    public void writeFloat(float value)
        throws XMLStreamException
    {
        // Copy buffer always has room for needed (~30) chars
        char[] buf = getCopyBuffer();
        int len = NumberUtil.writeFloat(value, buf, 0);
        doWriteTyped(buf, 0, len);
    }

    public void writeDouble(double value)
        throws XMLStreamException
    {
        // Copy buffer always has room for needed (~30) chars
        char[] buf = getCopyBuffer();
        int len = NumberUtil.writeDouble(value, buf, 0);
        doWriteTyped(buf, 0, len);
    }

    public void writeInteger(BigInteger value)
        throws XMLStreamException
    {
        /* No really efficient method exposed by JDK, keep it simple
         * (esp. considering that length is actually not bound)
         */
        doWriteTyped(String.valueOf(value));
    }

    public void writeDecimal(BigDecimal value)
        throws XMLStreamException
    {
        /* No really efficient method exposed by JDK, keep it simple
         * (esp. considering that length is actually not bound)
         */
        doWriteTyped(String.valueOf(value));
    }

    protected void doWriteTyped(String value)
        throws XMLStreamException
    {
        int len = value.length();
        char[] buf = getCopyBuffer(len);
        value.getChars(0, len, buf, 0);
        doWriteTyped(buf, 0, len);
    }

    /**
     * Method called when typed content to be written has been
     * serialized to characters, and can be output using
     * underlying methods without escaping.
     * However, there is still preparation
     * to do, to enforce constraints and invoke validators if need be.
     *<p>
     * For the most part, checks are similar to those done when
     * calling regular {@link #writeCharacters} method.
     */
    protected void doWriteTyped(char[] buffer, int offset, int len)
        throws XMLStreamException
    {
        if (mStartElementOpen) {
            closeStartElement(mEmptyElement);
        }
        // Not legal outside main element tree (won't be all white space)
        if (mCheckStructure) {
            if (inPrologOrEpilog()) {
                reportNwfStructure(ErrorConsts.WERR_PROLOG_NONWS_TEXT);
            }
        }
        if (mVldContent <= XMLValidator.CONTENT_ALLOW_WS) {
            reportInvalidContent(CHARACTERS);
        } else if (mVldContent == XMLValidator.CONTENT_ALLOW_VALIDATABLE_TEXT) {
            if (mValidator != null) {
                /* Hmmh. Not sure if we can pass 'true' as last arg,
                 * to indicate it's all output for this element? Seems
                 * logical; may need to document this with javadocs?
                 */
                mValidator.validateText(buffer, offset, len, true);
            }
        }
        try {
            // We know it won't need quoting, so let's call this method:
            mWriter.writeRawAscii(buffer, offset, len);
        } catch (IOException ioe) {
            throw new WstxIOException(ioe);
        }
    }

    // // // Typed attribute value write methods

    public void writeBooleanAttribute(String prefix, String nsURI, String localName, boolean value)
        throws XMLStreamException
    {
        char[] valueCh = value ? TYPE_CONST_TRUE : TYPE_CONST_FALSE;
        writeEscapedAttribute(prefix, nsURI, localName, valueCh, 0, valueCh.length);
    }

    public void writeIntAttribute(String prefix, String nsURI, String localName, int value)
        throws XMLStreamException
    {
        char[] buf = getCopyBuffer();
        int len = NumberUtil.writeInt(value, buf, 0);
        writeEscapedAttribute(prefix, nsURI, localName, buf, 0, len);
    }

    public void writeLongAttribute(String prefix, String nsURI, String localName, long value)
        throws XMLStreamException
    {
        char[] buf = getCopyBuffer();
        int len = NumberUtil.writeLong(value, buf, 0);
        writeEscapedAttribute(prefix, nsURI, localName, buf, 0, len);
    }

    public void writeFloatAttribute(String prefix, String nsURI, String localName, float value)
        throws XMLStreamException
    {
        char[] buf = getCopyBuffer();
        int len = NumberUtil.writeFloat(value, buf, 0);
        writeEscapedAttribute(prefix, nsURI, localName, buf, 0, len);
    }

    public void writeDoubleAttribute(String prefix, String nsURI, String localName, double value)
        throws XMLStreamException
    {
        char[] buf = getCopyBuffer();
        int len = NumberUtil.writeDouble(value, buf, 0);
        writeEscapedAttribute(prefix, nsURI, localName, buf, 0, len);
    }

    public void writeIntegerAttribute(String prefix, String nsURI, String localName, BigInteger value)
        throws XMLStreamException
    {
        // not optimal, but has to do:
        writeAttribute(prefix, nsURI, localName, value.toString());
    }

    public void writeDecimalAttribute(String prefix, String nsURI, String localName, BigDecimal value)
        throws XMLStreamException
    {
        // not optimal, but has to do:
        writeAttribute(prefix, nsURI, localName, value.toString());
    }

    /*
2y    ////////////////////////////////////////////////////
    // XMLStreamWriter2 methods (StAX2)
    ////////////////////////////////////////////////////
     */

    /**
     * Method that essentially copies event that the specified reader has
     * just read.
     *
     * @param sr Stream reader to use for accessing event to copy
     * @param preserveEventData If true, writer is not allowed to change
     *   the state of the reader (so that all the data associated with the
     *   current event has to be preserved); if false, writer is allowed
     *   to use methods that may cause some data to be discarded. Setting
     *   this to false may improve the performance, since it may allow
     *   full no-copy streaming of data, especially textual contents.
     */
    public void copyEventFromReader(XMLStreamReader2 sr, boolean preserveEventData)
        throws XMLStreamException
    {
        try {
            switch (sr.getEventType()) {
            case START_DOCUMENT:
                {
                    String version = sr.getVersion();
                    /* No real declaration? If so, we don't want to output
                     * anything, to replicate as closely as possible the
                     * source document
                     */
                    if (version == null || version.length() == 0) {
                        ; // no output if no real input
                    } else {
                        if (sr.standaloneSet()) {
                            writeStartDocument(sr.getVersion(),
                                               sr.getCharacterEncodingScheme(),
                                               sr.isStandalone());
                        } else {
                            writeStartDocument(sr.getCharacterEncodingScheme(),
                                               sr.getVersion());
                        }
                    }
                }
                return;
                
            case END_DOCUMENT:
                writeEndDocument();
                return;
                
                // Element start/end events:
            case START_ELEMENT:
                {
                    if (sr != mLastReader) {
                        mLastReader = sr;
                        // Should probably work with non-Woodstox stream
                        // readers too... but that's not implemented yet
                        if (!(sr instanceof StreamReaderImpl)) {
                            throw new XMLStreamException("Can not yet copy START_ELEMENT events from non-Woodstox stream readers (class "+sr.getClass()+")");
                        }
                        mLastReaderImpl = (StreamReaderImpl) sr;
                        mAttrCollector = mLastReaderImpl.getAttributeCollector();
                        mInputElemStack = mLastReaderImpl.getInputElementStack();
                    }
                    copyStartElement(mInputElemStack, mAttrCollector);
                }
                return;

            case END_ELEMENT:
                writeEndElement();
                return;
                
            case SPACE:
                {
                    mAnyOutput = true;
                    // Need to finish an open start element?
                    if (mStartElementOpen) {
                        closeStartElement(mEmptyElement);
                    }
                    /* No need to write as chars, should be pure space
                     * (caller should have verified); also, no escaping
                     * necessary.
                     */
                    sr.getText(wrapAsRawWriter(), preserveEventData);
                }
                return;

            case CDATA:

                // First; is this to be changed to 'normal' text output?
                if (!mCfgCDataAsText) {
                    mAnyOutput = true;
                    // Need to finish an open start element?
                    if (mStartElementOpen) {
                        closeStartElement(mEmptyElement);
                    }

                    // Not legal outside main element tree:
                    if (mCheckStructure) {
                        if (inPrologOrEpilog()) {
                            reportNwfStructure(ErrorConsts.WERR_PROLOG_CDATA);
                        }
                    }
                    /* Note: no need to check content, since reader is assumed
                     * to have verified it to be valid XML.
                     */
                    mWriter.writeCDataStart();
                    sr.getText(wrapAsRawWriter(), preserveEventData);
                    mWriter.writeCDataEnd();
                    return;
                }
                // fall down if it is to be converted...

            case CHARACTERS:
                {
                    // Let's just assume content is fine... not 100% reliably
                    // true, but usually is (not true if input had a root
                    // element surrounding text, but omitted for output)
                    mAnyOutput = true;
                    // Need to finish an open start element?
                    if (mStartElementOpen) {
                        closeStartElement(mEmptyElement);
                    }
                    sr.getText(wrapAsTextWriter(), preserveEventData);
                }
                return;
                
            case COMMENT:
                {
                    mAnyOutput = true;
                    if (mStartElementOpen) {
                        closeStartElement(mEmptyElement);
                    }
                    // No need to check for content (embedded '--'); reader
                    // is assumed to have verified it's ok (otherwise should
                    // have thrown an exception for non-well-formed XML)
                    mWriter.writeCommentStart();
                    sr.getText(wrapAsRawWriter(), preserveEventData);
                    mWriter.writeCommentEnd();
                }
                return;

            case PROCESSING_INSTRUCTION:
                {
                    mWriter.writePIStart(sr.getPITarget(), true);
                    sr.getText(wrapAsRawWriter(), preserveEventData);
                    mWriter.writePIEnd();
                }
                return;
                
            case DTD:
                {
                    DTDInfo info = sr.getDTDInfo();
                    if (info == null) {
                        // Hmmmh. It is legal for this to happen, for
                        // non-DTD-aware readers. But what is the right
                        // thing to do here?
                        throwOutputError("Current state DOCTYPE, but not DTDInfo Object returned -- reader doesn't support DTDs?");
                    }
                    // Could optimize this a bit (stream the int. subset
                    // possible), but it's never going to occur more than
                    // once per document, so it's probably not much of a
                    // bottleneck, ever
                    writeDTD(info);
                }
                return;
                
            case ENTITY_REFERENCE:
                writeEntityRef(sr.getLocalName());
                return;

            case ATTRIBUTE:
            case NAMESPACE:
            case ENTITY_DECLARATION:
            case NOTATION_DECLARATION:
                // Let's just fall back to throw the exception
            }
        } catch (IOException ioe) {
            throw new WstxIOException(ioe);
        }

        throw new XMLStreamException("Unrecognized event type ("
                                     +sr.getEventType()+"); not sure how to copy");
    }

    /*
    ////////////////////////////////////////////////////
    // StAX2, config
    ////////////////////////////////////////////////////
     */

    // NOTE: getProperty() defined in Stax 1.0 interface

    public boolean isPropertySupported(String name) {
        // !!! TBI: not all these properties are really supported
        return mConfig.isPropertySupported(name);
    }

    /**
     * @param name Name of the property to set
     * @param value Value to set property to.
     *
     * @return True, if the specified property was <b>succesfully</b>
     *    set to specified value; false if its value was not changed
     */
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
        XMLValidator vld = schema.createValidator(this);

        if (mValidator == null) {
            /* Need to enable other validation modes? Structural validation
             * should always be done when we have other validators as well,
             * as well as attribute uniqueness checks.
             */
            mCheckStructure = true;
            mCheckAttrs = true;
            mValidator = vld;
        } else {
            mValidator = new ValidatorPair(mValidator, vld);
        }
        return vld;
    }

    public XMLValidator stopValidatingAgainst(XMLValidationSchema schema)
        throws XMLStreamException
    {
        XMLValidator[] results = new XMLValidator[2];
        XMLValidator found = null;
        if (ValidatorPair.removeValidator(mValidator, schema, results)) { // found
            found = results[0];
            mValidator = results[1];
            found.validationCompleted(false);
            if (mValidator == null) {
                resetValidationFlags();
            }
        }
        return found;
    }

    public XMLValidator stopValidatingAgainst(XMLValidator validator)
        throws XMLStreamException
    {
        XMLValidator[] results = new XMLValidator[2];
        XMLValidator found = null;
        if (ValidatorPair.removeValidator(mValidator, validator, results)) { // found
            found = results[0];
            mValidator = results[1];
            found.validationCompleted(false);
            if (mValidator == null) {
                resetValidationFlags();
            }
        }
        return found;
    }

    public ValidationProblemHandler setValidationProblemHandler(ValidationProblemHandler h)
    {
        ValidationProblemHandler oldH = mVldProbHandler;
        mVldProbHandler = h;
        return oldH;
    }

    private void resetValidationFlags()
    {
        int flags = mConfig.getConfigFlags();
        mCheckStructure = (flags & CFG_VALIDATE_STRUCTURE) != 0;
        mCheckAttrs = (flags & CFG_VALIDATE_ATTR) != 0;
    }

    /*
    ////////////////////////////////////////////////////
    // StAX2, other accessors, mutators
    ////////////////////////////////////////////////////
     */

    public XMLStreamLocation2 getLocation()
    {
        return new WstxInputLocation(null, // no parent
                                     null, null, // pub/sys ids not yet known
                                     mWriter.getAbsOffset(),
                                     mWriter.getRow(), mWriter.getColumn());
    }

    public String getEncoding() {
        return mEncoding;
    }

    /*
    ////////////////////////////////////////////////////
    // StAX2, output methods
    ////////////////////////////////////////////////////
     */

    public void writeCData(char[] cbuf, int start, int len)
        throws XMLStreamException
    {
        /* 02-Dec-2004, TSa: Maybe the writer is to "re-direct" these
         *   writes as normal text? (sometimes useful to deal with broken
         *   XML parsers, for example)
         */
        if (mCfgCDataAsText) {
            writeCharacters(cbuf, start, len);
            return;
        }

        mAnyOutput = true;
        // Need to finish an open start element?
        if (mStartElementOpen) {
            closeStartElement(mEmptyElement);
        }
        verifyWriteCData();
        if (mVldContent == XMLValidator.CONTENT_ALLOW_VALIDATABLE_TEXT
            && mValidator != null) {
            /* Last arg is false, since we do not know if more text
             * may be added with additional calls
             */
            mValidator.validateText(cbuf, start, len, false);
        }
        int ix;
        try {
            ix = mWriter.writeCData(cbuf, start, len);
        } catch (IOException ioe) {
            throw new WstxIOException(ioe);
        }
        if (ix >= 0) { // problems that could not to be fixed?
            throwOutputError(ErrorConsts.WERR_CDATA_CONTENT, DataUtil.Integer(ix));
        }
    }

    public void writeDTD(DTDInfo info)
        throws XMLStreamException
    {
        writeDTD(info.getDTDRootName(), info.getDTDSystemId(),
                 info.getDTDPublicId(), info.getDTDInternalSubset());
    }

    public void writeDTD(String rootName, String systemId, String publicId,
                         String internalSubset)
        throws XMLStreamException
    {
        verifyWriteDTD();
        mDtdRootElem = rootName;
        try {
            mWriter.writeDTD(rootName, systemId, publicId, internalSubset);
        } catch (IOException ioe) {
            throw new WstxIOException(ioe);
        }
    }

    public abstract void writeFullEndElement() throws XMLStreamException;

    public void writeStartDocument(String version, String encoding,
                                   boolean standAlone)
        throws XMLStreamException
    {
        doWriteStartDocument(version, encoding, standAlone ? "yes" : "no");
    }

    public void writeRaw(String text)
        throws XMLStreamException
    {
        mAnyOutput = true;
        if (mStartElementOpen) {
            closeStartElement(mEmptyElement);
        }
        try {
            mWriter.writeRaw(text, 0, text.length());
        } catch (IOException ioe) {
            throw new WstxIOException(ioe);
        }
    }

    public void writeRaw(String text, int start, int offset)
        throws XMLStreamException
    {
        mAnyOutput = true;
        if (mStartElementOpen) {
            closeStartElement(mEmptyElement);
        }
        try {
            mWriter.writeRaw(text, start, offset);
        } catch (IOException ioe) {
            throw new WstxIOException(ioe);
        }
    }

    public void writeRaw(char[] text, int start, int offset)
        throws XMLStreamException
    {
        mAnyOutput = true;
        if (mStartElementOpen) {
            closeStartElement(mEmptyElement);
        }
        try {
            mWriter.writeRaw(text, start, offset);
        } catch (IOException ioe) {
            throw new WstxIOException(ioe);
        }
    }

    public void writeSpace(String text)
        throws XMLStreamException
    {
        /* For now, let's just use writeRaw(): otherwise would need
         * to add it to all backend writers:
         */
        writeRaw(text);
    }

    public void writeSpace(char[] text, int offset, int length)
        throws XMLStreamException
    {
        // For now, let's just use writeRaw() (see above)
        writeRaw(text, offset, length);
    }

    /*
    ////////////////////////////////////////////////////
    // ValidationContext interface (StAX2, validation)
    ////////////////////////////////////////////////////
     */

    public String getXmlVersion() {
        return mXml11 ? XmlConsts.XML_V_11_STR : XmlConsts.XML_V_10_STR;
    }

    public abstract QName getCurrentElementName();

    public abstract String getNamespaceURI(String prefix);

    /**
     * As of now, there is no way to specify the base URI. Could be improved
     * in future, if xml:base is supported.
     */
    public String getBaseUri() {
        return null;
    }

    public Location getValidationLocation() {
        return getLocation();
    }

    public void reportProblem(XMLValidationProblem prob)
        throws XMLValidationException
    {
        // Custom handler set? If so, it'll take care of it:
        if (mVldProbHandler != null) {
            mVldProbHandler.reportProblem(prob);
            return;
        }

        /* For now let's implement basic functionality: warnings get
         * reported via XMLReporter, errors and fatal errors result in
         * immediate exceptions.
         */
        if (prob.getSeverity() >= XMLValidationProblem.SEVERITY_ERROR) {
            if (isValidating()) {
                throw WstxValidationException.create(prob);
            }
        }
        XMLReporter rep = mConfig.getProblemReporter();
        if (rep != null) {
            doReportProblem(rep, ErrorConsts.WT_VALIDATION, prob.getMessage(),
                            prob.getLocation());
        }
    }

    /**
     * Adding default attribute values does not usually make sense on
     * output side, so the implementation is a NOP for now.
     */
    public int addDefaultAttribute(String localName, String uri, String prefix,
                                   String value)
    {
        // nothing to do, but to indicate we didn't add it...
        return -1;
    }

    // // // Notation/entity access: not (yet?) implemented

    public boolean isNotationDeclared(String name) { return false; }

    public boolean isUnparsedEntityDeclared(String name) { return false; }


    // // // Attribute access: not yet implemented:

    /* !!! TODO: Implement attribute access (iff validate-attributes
     *   enabled?
     */

    public int getAttributeCount() { return 0; }

    public String getAttributeLocalName(int index) { return null; }

    public String getAttributeNamespace(int index) { return null; }

    public String getAttributePrefix(int index) { return null; }

    public String getAttributeValue(int index) { return null; }

    public String getAttributeValue(String nsURI, String localName) {
        return null;
    }

    public String getAttributeType(int index) {
        return "";
    }

    public int findAttributeIndex(String nsURI, String localName) {
        return -1;
    }

    /*
    ////////////////////////////////////////////////////
    // Package methods (ie not part of public API)
    ////////////////////////////////////////////////////
     */

    /**
     * Method that can be called to get a wrapper instance that
     * can be used to essentially call the <code>writeRaw</code>
     * method via regular <code>Writer</code> interface.
     */
    public final Writer wrapAsRawWriter()
    {
        return mWriter.wrapAsRawWriter();
    }

    /**
     * Method that can be called to get a wrapper instance that
     * can be used to essentially call the <code>writeCharacters</code>
     * method via regular <code>Writer</code> interface.
     */
    public final Writer wrapAsTextWriter()
    {
        return mWriter.wrapAsTextWriter();
    }

    /**
     * Method that is used by output classes to determine whether we
     * are in validating mode.
     *<p>
     * Note: current implementation of this method is not perfect; it
     * may be possible it can return true even if we are only using a DTD
     * to get some limited info, without validating?
     */
    protected boolean isValidating() {
        return (mValidator != null);
    }

    /**
     * Convenience method needed by {@link javax.xml.stream.XMLEventWriter}
     * implementation, to use when
     * writing a start element, and possibly its attributes and namespace
     * declarations.
     */
    public abstract void writeStartElement(StartElement elem)
        throws XMLStreamException;

    /**
     * Method called by {@link javax.xml.stream.XMLEventWriter}
     * (instead of the version
     * that takes no argument), so that we can verify it does match the
     * start element if necessary.
     */
    public abstract void writeEndElement(QName name)
        throws XMLStreamException;

    /**
     * Method called by {@link javax.xml.stream.XMLEventWriter}
     * (instead of more generic
     * text output methods), so that we can verify (if necessary) that
     * this character output type is legal in this context. Specifically,
     * it's not acceptable to add non-whitespace content outside root
     * element (in prolog/epilog).
     *<p>
     * Note: cut'n pasted from the main <code>writeCharacters</code>; not
     * good... but done to optimize white-space cases.
     */
    public void writeCharacters(Characters ch)
        throws XMLStreamException
    {
        // Need to finish an open start element?
        if (mStartElementOpen) {
            closeStartElement(mEmptyElement);
        }

        /* Not legal outside main element tree, except if it's all
         * white space
         */
        if (mCheckStructure) {
            if (inPrologOrEpilog()) {
                if (!ch.isIgnorableWhiteSpace() && !ch.isWhiteSpace()) {
                    reportNwfStructure(ErrorConsts.WERR_PROLOG_NONWS_TEXT);
                }
            }
        }

        if (mVldContent <= XMLValidator.CONTENT_ALLOW_WS) {
            if (mVldContent == XMLValidator.CONTENT_ALLOW_NONE) { // never ok
                reportInvalidContent(CHARACTERS);
            } else { // all-ws is ok...
                if (!ch.isIgnorableWhiteSpace() && !ch.isWhiteSpace()) {
                    reportInvalidContent(CHARACTERS);
                }
            }
        } else if (mVldContent == XMLValidator.CONTENT_ALLOW_VALIDATABLE_TEXT) {
            if (mValidator != null) {
                /* Last arg is false, since we do not know if more text
                 * may be added with additional calls
                 */
                mValidator.validateText(ch.getData(), false);
            }
        }

        // Ok, let's just write it out:
        try {
            mWriter.writeCharacters(ch.getData());
        } catch (IOException ioe) {
            throw new WstxIOException(ioe);
        }
    }

    /**
     * Method that will write attribute with value that is known not to
     * require additional escaping.
     */
    protected abstract void writeEscapedAttribute(String prefix, String nsURI,
                                                  String localName,
                                                  char[] buf, int offset, int len)
        throws XMLStreamException;
    
    /**
     * Method called to close an open start element, when another
     * main-level element (not namespace declaration or attribute)
     * is being output; except for end element which is handled differently.
     */
    protected abstract void closeStartElement(boolean emptyElem)
        throws XMLStreamException;

    protected final boolean inPrologOrEpilog() {
        return (mState != STATE_TREE);
    }

    private final void finishDocument() throws XMLStreamException
    {
        // Is tree still open?
        if (mState != STATE_EPILOG) {
            if (mCheckStructure  && mState == STATE_PROLOG) {
                reportNwfStructure("Trying to write END_DOCUMENT when document has no root (ie. trying to output empty document).");
            }
            // 20-Jul-2004, TSa: Need to close the open sub-tree, if it exists...
            // First, do we have an open start element?
            if (mStartElementOpen) {
                closeStartElement(mEmptyElement);
            }
            // Then, one by one, need to close open scopes:
            while (mState != STATE_EPILOG) {
                writeEndElement();
            }
        }

        /* And finally, inform the underlying writer that it should flush
         * and release its buffers, and close components it uses if any.
         */
        try {
            char[] buf = mCopyBuffer;
            if (buf != null) {
                mCopyBuffer = null;
                mConfig.freeMediumCBuffer(buf);
            }
            mWriter.close();
        } catch (IOException ie) {
            throw new WstxIOException(ie);
        }
    }

    /**
     * Implementation-dependant method called to fully copy START_ELEMENT
     * event that the passed-in stream reader points to
     */
    public abstract void copyStartElement(InputElementStack elemStack,
                                          AttributeCollector attrCollector)
        throws IOException, XMLStreamException;

    /*
    ////////////////////////////////////////////////////
    // Package methods, validation
    ////////////////////////////////////////////////////
     */

    protected final void verifyWriteCData()
        throws XMLStreamException
    {
        // Not legal outside main element tree:
        if (mCheckStructure) {
            if (inPrologOrEpilog()) {
                reportNwfStructure(ErrorConsts.WERR_PROLOG_CDATA);
            }
        }
        // 08-Dec-2005, TSa: validator-based validation?
        if (mVldContent <= XMLValidator.CONTENT_ALLOW_WS) {
            // there's no ignorable white space CDATA...
            reportInvalidContent(CDATA);
        }
    }

    protected final void verifyWriteDTD()
        throws XMLStreamException
    {
        // 20-Nov-2004, TSa: can check that we are in prolog
        if (mCheckStructure) {
            if (mState != STATE_PROLOG) {
                throw new XMLStreamException("Can not write DOCTYPE declaration (DTD) when not in prolog any more (state "+mState+"; start element(s) written)");
            }
            // 20-Dec-2005, TSa: and that we only output one...
            if (mDtdRootElem != null) {
                throw new XMLStreamException("Trying to write multiple DOCTYPE declarations");
            }
        }
    }

    protected void verifyRootElement(String localName, String prefix)
        throws XMLValidationException
    {
        /* Note: this check is bit lame, due to DOCTYPE declaration (and DTD
         * in general) being namespace-ignorant...
         */
        if (isValidating()) {
            /* 17-Mar-2006, TSa: Ideally, this should be a validity
             *   problem?
             */
            if (mDtdRootElem != null && mDtdRootElem.length() > 0) {
                String wrongElem = null;
                
                /* Ugh. It is possible that we just don't know the prefix --
                 * in repairing mode it's assigned after this check. So for
                 * now, let's only verify the local name
                 */
                if (localName.equals(mDtdRootElem)) {
                    // good
                } else {
                    int lnLen = localName.length();
                    int oldLen = mDtdRootElem.length();
                    
                    if (oldLen > lnLen
                        && mDtdRootElem.endsWith(localName)
                        && mDtdRootElem.charAt(oldLen - lnLen - 1) == ':') {
                        // good also
                    } else {
                        if (prefix == null) { // doesn't and won't have one
                            wrongElem = localName;
                        } else if (prefix.length() == 0) { // don't know what it'd be
                            wrongElem = "[unknown]:"+localName;
                        } else {
                            wrongElem = prefix + ":" + localName;
                        }
                    }
                }
                if (wrongElem != null) {
                    reportValidationProblem(ErrorConsts.ERR_VLD_WRONG_ROOT, wrongElem, mDtdRootElem);
                }
            }
        }
        mState = STATE_TREE;
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

    /**
     * Method called when an illegal method (namespace-specific method
     * on non-ns writer) is called by the application.
     */
    protected static void reportIllegalMethod(String msg)
        throws XMLStreamException
    {
        throwOutputError(msg);
    }

    /**
     * This is the method called when an output method call violates
     * structural well-formedness checks
     * and {@link WstxOutputProperties#P_OUTPUT_VALIDATE_STRUCTURE} is
     * is enabled.
     */
    protected static void reportNwfStructure(String msg)
        throws XMLStreamException
    {
        throwOutputError(msg);
    }

    protected static void reportNwfStructure(String msg, Object arg)
        throws XMLStreamException
    {
        throwOutputError(msg, arg);
    }

    /**
     * This is the method called when an output method call violates
     * content well-formedness checks
     * and {@link WstxOutputProperties#P_OUTPUT_VALIDATE_CONTENT} is
     * is enabled.
     */
    protected static void reportNwfContent(String msg)
        throws XMLStreamException
    {
        throwOutputError(msg);
    }

    protected static void reportNwfContent(String msg, Object arg)
        throws XMLStreamException
    {
        throwOutputError(msg, arg);
    }

    /**
     * This is the method called when an output method call violates
     * attribute well-formedness checks (trying to output dup attrs)
     * and {@link WstxOutputProperties#P_OUTPUT_VALIDATE_NAMES} is
     * is enabled.
     */
    protected static void reportNwfAttr(String msg)
        throws XMLStreamException
    {
        throwOutputError(msg);
    }

    protected static void reportNwfAttr(String msg, Object arg)
        throws XMLStreamException
    {
        throwOutputError(msg, arg);
    }

    protected static void throwFromIOE(IOException ioe)
        throws XMLStreamException
    {
        throw new WstxIOException(ioe);
    }

    protected static void reportIllegalArg(String msg)
        throws IllegalArgumentException
    {
        throw new IllegalArgumentException(msg);
    }

    /*
    ///////////////////////////////////////////////////////
    // Package methods, output validation problem reporting
    ///////////////////////////////////////////////////////
     */

    protected void reportInvalidContent(int evtType)
        throws XMLStreamException
    {
        switch (mVldContent) {
        case XMLValidator.CONTENT_ALLOW_NONE:
            reportValidationProblem(ErrorConsts.ERR_VLD_EMPTY,
                                    getTopElementDesc(),
                                    ErrorConsts.tokenTypeDesc(evtType));
            break;
        case XMLValidator.CONTENT_ALLOW_WS:
            reportValidationProblem(ErrorConsts.ERR_VLD_NON_MIXED,
                                    getTopElementDesc());
            break;
        case XMLValidator.CONTENT_ALLOW_VALIDATABLE_TEXT:
        case XMLValidator.CONTENT_ALLOW_ANY_TEXT:
            /* Not 100% sure if this should ever happen... depends on
             * interpretation of 'any' content model?
             */
            reportValidationProblem(ErrorConsts.ERR_VLD_ANY,
                                    getTopElementDesc(),
                                    ErrorConsts.tokenTypeDesc(evtType));
            break;
        default: // should never occur:
            reportValidationProblem("Internal error: trying to report invalid content for "+evtType);
        }
    }

    public void reportValidationProblem(String msg, Location loc, int severity)
        throws XMLValidationException
    {
        reportProblem(new XMLValidationProblem(loc, msg, severity));
    }

    public void reportValidationProblem(String msg, int severity)
        throws XMLValidationException
    {
        reportProblem(new XMLValidationProblem(getValidationLocation(),
                                               msg, severity));
    }

    public void reportValidationProblem(String msg)
        throws XMLValidationException
    {
        reportProblem(new XMLValidationProblem(getValidationLocation(),
                                               msg,
                                               XMLValidationProblem.SEVERITY_ERROR));
    }

    public void reportValidationProblem(Location loc, String msg)
        throws XMLValidationException
    {
        reportProblem(new XMLValidationProblem(getValidationLocation(),
                                                         msg));
    }

    public void reportValidationProblem(String format, Object arg)
        throws XMLValidationException
    {
        String msg = MessageFormat.format(format, new Object[] { arg });
        reportProblem(new XMLValidationProblem(getValidationLocation(),
                                                         msg));
    }

    public void reportValidationProblem(String format, Object arg, Object arg2)
        throws XMLValidationException
    {
        String msg = MessageFormat.format(format, new Object[] { arg, arg2 });
        reportProblem(new XMLValidationProblem(getValidationLocation(), msg));
    }

    protected final void doReportProblem(XMLReporter rep, String probType,
                                         String msg, Location loc)
    {
        if (rep != null) {
            try {
                rep.report(msg, probType, null, loc);
            } catch (XMLStreamException e) {
                // Hmmh. Weird that a reporter is allowed to do this...
                System.err.println("Problem reporting a problem: "+e);
            }
        }
    }

    /**
     * Method needed for error message generation
     */
    protected abstract String getTopElementDesc();

    /*
    ////////////////////////////////////////////////////
    // Package methods, other
    ////////////////////////////////////////////////////
     */

    private final char[] getCopyBuffer()
    {
        char[] buf = mCopyBuffer;
        if (buf == null) {
            mCopyBuffer = buf = mConfig.allocMediumCBuffer(DEFAULT_COPYBUFFER_LEN);
        }
        return buf;
    }

    private final char[] getCopyBuffer(int minLen)
    {
        char[] buf = mCopyBuffer;
        if (buf == null || minLen > buf.length) {
            mCopyBuffer = buf = mConfig.allocMediumCBuffer(Math.max(DEFAULT_COPYBUFFER_LEN, minLen));
        }
        return buf;
    }

    public String toString()
    {
        return "[StreamWriter: "+getClass()+", underlying outputter: "
            +((mWriter == null) ? "NULL" : mWriter.toString());
    }
}
