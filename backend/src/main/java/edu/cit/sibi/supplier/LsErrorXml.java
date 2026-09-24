package edu.cit.sibi.supplier;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.dataformat.xml.annotation.JacksonXmlProperty;
import com.fasterxml.jackson.dataformat.xml.annotation.JacksonXmlRootElement;

/** Error body LegacySupply returns on any non-2xx response. */
@JacksonXmlRootElement(localName = "LSError")
@JsonIgnoreProperties(ignoreUnknown = true)
class LsErrorXml {

    @JacksonXmlProperty(localName = "Code")
    public String code;

    @JacksonXmlProperty(localName = "Message")
    public String message;

    @Override
    public String toString() {
        return code + ": " + message;
    }
}
