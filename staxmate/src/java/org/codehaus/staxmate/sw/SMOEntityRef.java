package org.codehaus.staxmate.sw;

import javax.xml.stream.XMLStreamException;

public class SMOEntityRef
    extends SMSimpleOutput
{
    final String mName;

    public SMOEntityRef(String name) {
        super();
        mName = name;
    }

    protected boolean doOutput(SMOutputContext ctxt, boolean canClose)
        throws XMLStreamException
    {
        ctxt.writeEntityRef(mName);
        return true;
    }
}