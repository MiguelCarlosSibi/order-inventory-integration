package edu.cit.sibi.supplier;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.dataformat.xml.annotation.JacksonXmlElementWrapper;
import com.fasterxml.jackson.dataformat.xml.annotation.JacksonXmlProperty;
import com.fasterxml.jackson.dataformat.xml.annotation.JacksonXmlRootElement;

import java.util.List;

/**
 * GET /purchase-orders?buyerRef={BuyerRef} response body. Used by
 * LegacySupplyClient to check whether a reorder already exists under a
 * given BuyerRef before resending a PENDING one — belt-and-suspenders
 * alongside the X-Request-Id header for avoiding duplicate purchase orders.
 */
@JacksonXmlRootElement(localName = "PurchaseOrderList")
@JsonIgnoreProperties(ignoreUnknown = true)
class PurchaseOrderListXml {

    @JacksonXmlProperty(localName = "Count")
    public int count;

    @JacksonXmlElementWrapper(useWrapping = false)
    @JacksonXmlProperty(localName = "PurchaseOrderStatus")
    public List<PurchaseOrderStatusXml> orders;
}
