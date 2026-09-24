package edu.cit.sibi.supplier;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.dataformat.xml.annotation.JacksonXmlProperty;
import com.fasterxml.jackson.dataformat.xml.annotation.JacksonXmlRootElement;

/** POST /auth/token success response. */
@JacksonXmlRootElement(localName = "AuthResponse")
@JsonIgnoreProperties(ignoreUnknown = true)
class AuthResponseXml {

    @JacksonXmlProperty(localName = "SessionToken")
    public String sessionToken;

    @JacksonXmlProperty(localName = "IssuedAt")
    public String issuedAt;
}
