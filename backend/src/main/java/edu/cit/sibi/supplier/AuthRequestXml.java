package edu.cit.sibi.supplier;

import com.fasterxml.jackson.dataformat.xml.annotation.JacksonXmlProperty;
import com.fasterxml.jackson.dataformat.xml.annotation.JacksonXmlRootElement;

/**
 * POST /auth/token request body. Package-private and framework-only — this
 * (and every other class in this sub-package) never crosses out of the
 * supplier module. See LegacySupply manual, "Sessions".
 */
@JacksonXmlRootElement(localName = "AuthRequest")
class AuthRequestXml {

    @JacksonXmlProperty(localName = "ClientId")
    public String clientId;

    @JacksonXmlProperty(localName = "ApiKey")
    public String apiKey;

    public AuthRequestXml() {
    }

    public AuthRequestXml(String clientId, String apiKey) {
        this.clientId = clientId;
        this.apiKey = apiKey;
    }
}
