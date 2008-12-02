package org.codehaus.staxbind.dbconv;

import java.io.*;
import java.util.*;

import javax.xml.stream.*;

public class StaxXmlConverter
    extends DbConverter
{
    final XMLInputFactory _staxInFactory;
    final XMLOutputFactory _staxOutFactory;

    public StaxXmlConverter(String infClass, String outfClass)
    {
        try {
            _staxInFactory = (XMLInputFactory) Class.forName(infClass).newInstance();
            _staxOutFactory = (XMLOutputFactory) Class.forName(outfClass).newInstance();
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    public DbData readData(InputStream in)
        throws XMLStreamException
    {
        XMLStreamReader sr = _staxInFactory.createXMLStreamReader(in);
        DbData result = new DbData();

        sr.nextTag();
        expectTag(FIELD_TABLE, sr);

        try {
            while (sr.nextTag() == XMLStreamReader.START_ELEMENT) {
                result.addRow(readRow(sr));
            }
        } catch (IllegalArgumentException iae) {
            throw new XMLStreamException("Data problem: "+iae.getMessage(), sr.getLocation());
        }

        sr.close();
        return result;
    }

    private final DbRow readRow(XMLStreamReader sr)
        throws XMLStreamException
    {
        expectTag(FIELD_ROW, sr);
        DbRow row = new DbRow();
        while (sr.nextTag() == XMLStreamReader.START_ELEMENT) {
            String elemName = sr.getLocalName();
            String value = sr.getElementText();
            
            try {
                if (!row.assign(elemName, value)) {
                    throw new XMLStreamException("Unexpected element <"+elemName+">: not one of recognized field names");
                }
            } catch (IllegalArgumentException iae) {
                throw new XMLStreamException("Typed access problem with input '"+value+"': "+iae.getMessage(), sr.getLocation(), iae);
            }
        }
        return row;
    }

    public int writeData(OutputStream out, DbData data) throws Exception
    {
        XMLStreamWriter sw = _staxOutFactory.createXMLStreamWriter(out, "UTF-8");
        sw.close();
        sw.writeStartDocument();
        sw.writeStartElement(FIELD_TABLE);
        Iterator<DbRow> it = data.rows();
        while (it.hasNext()) {
            DbRow row = it.next();
            sw.writeStartElement(FIELD_ROW); // <row>

            sw.writeStartElement(DbRow.Field.id.name());
            sw.writeCharacters(String.valueOf(row.getId()));
            sw.writeEndElement();

            sw.writeStartElement(DbRow.Field.firstname.name());
            sw.writeCharacters(row.getFirstname());
            sw.writeEndElement();

            sw.writeStartElement(DbRow.Field.lastname.name());
            sw.writeCharacters(row.getLastname());
            sw.writeEndElement();

            sw.writeStartElement(DbRow.Field.zip.name());
            sw.writeCharacters(String.valueOf(row.getZip()));
            sw.writeEndElement();

            sw.writeStartElement(DbRow.Field.street.name());
            sw.writeCharacters(row.getStreet());
            sw.writeEndElement();

            sw.writeStartElement(DbRow.Field.city.name());
            sw.writeCharacters(row.getCity());
            sw.writeEndElement();

            sw.writeStartElement(DbRow.Field.state.name());
            sw.writeCharacters(row.getState());
            sw.writeEndElement();

            sw.writeEndElement(); // </row>
        }
        sw.writeEndElement();
        sw.writeEndDocument();
        sw.close();
        return -1;
    }

    private final void expectTag(String expElem, XMLStreamReader sr)
        throws XMLStreamException
    {
        if (!expElem.equals(sr.getLocalName())) {
            throw new XMLStreamException("Unexpected element <"+sr.getLocalName()+">: expecting <"+expElem+">", sr.getLocation());
        }
    }
}