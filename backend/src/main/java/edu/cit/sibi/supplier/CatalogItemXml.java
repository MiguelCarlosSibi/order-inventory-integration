package edu.cit.sibi.supplier;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.dataformat.xml.annotation.JacksonXmlProperty;

@JsonIgnoreProperties(ignoreUnknown = true)
class CatalogItemXml {

    @JacksonXmlProperty(localName = "SupplierSku")
    public String supplierSku;

    @JacksonXmlProperty(localName = "Description")
    public String description;

    @JacksonXmlProperty(localName = "PackSize")
    public int packSize;

    @JacksonXmlProperty(localName = "UnitCost")
    public String unitCost;
}
