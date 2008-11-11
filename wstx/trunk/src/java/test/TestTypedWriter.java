package test;

import java.io.*;

import javax.xml.stream.*;

import org.codehaus.stax2.typed.*;

/**
 * Simple manually run helper class for verifying typed output.
 */
public class TestTypedWriter
{
    protected XMLOutputFactory getFactory()
    {
        return new com.ctc.wstx.stax.WstxOutputFactory();
    }

    protected void test()
        throws Exception
    {
        XMLOutputFactory f = getFactory();
        Writer w = new PrintWriter(System.out);
        TypedXMLStreamWriter sw = (TypedXMLStreamWriter) f.createXMLStreamWriter(w);

        sw.writeStartDocument();

        final String STR =
            //"Let's test this base64 thing with some arbitrary test data gotten out of this String as UTF-8 encoded"
"1234567"
            ;
        byte[] data = STR.getBytes("UTF-8");

        sw.writeStartElement("root");
        //sw.writeCharacters("\n");
        sw.writeBinary(data, 0, data.length);
        sw.writeEndElement();
        sw.writeCharacters("\n");
        sw.writeEndDocument();

        sw.flush();
        sw.close();

        w.close();
    }

    public static void main(String[] args)
        throws Exception
    {
        new TestTypedWriter().test();
    }
}
