package edu.cit.sibi.supplier;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.dataformat.xml.annotation.JacksonXmlProperty;
import com.fasterxml.jackson.dataformat.xml.annotation.JacksonXmlRootElement;

/** GET /purchase-orders/{PoNumber} response body — Ack fields plus CheckedAt. */
@JacksonXmlRootElement(localName = "PurchaseOrderStatus")
@JsonIgnoreProperties(ignoreUnknown = true)
class PurchaseOrderStatusXml {

    @JacksonXmlProperty(localName = "PoNumber")
    public String poNumber;

    @JacksonXmlProperty(localName = "StatusCode")
    public int statusCode;

    @JacksonXmlProperty(localName = "SupplierSku")
    public String supplierSku;

    @JacksonXmlProperty(localName = "Qty")
    public int qty;

    @JacksonXmlProperty(localName = "Uom")
    public String uom;

    @JacksonXmlProperty(localName = "BuyerRef")
    public String buyerRef;

    @JacksonXmlProperty(localName = "CreatedAt")
    public String createdAt;

    @JacksonXmlProperty(localName = "CheckedAt")
    public String checkedAt;
}
