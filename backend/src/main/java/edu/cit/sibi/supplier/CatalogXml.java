package edu.cit.sibi.supplier;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.dataformat.xml.annotation.JacksonXmlElementWrapper;
import com.fasterxml.jackson.dataformat.xml.annotation.JacksonXmlProperty;
import com.fasterxml.jackson.dataformat.xml.annotation.JacksonXmlRootElement;

import java.util.List;

/**
 * GET /catalog response body. Only used during Part B discovery (to build
 * the product-mapping table in INTEGRATION.md) and, optionally, by an
 * admin/debug endpoint — never referenced outside this package.
 */
@JacksonXmlRootElement(localName = "Catalog")
@JsonIgnoreProperties(ignoreUnknown = true)
class CatalogXml {

    @JacksonXmlElementWrapper(useWrapping = false)
    @JacksonXmlProperty(localName = "Item")
    public List<CatalogItemXml> items;
}
