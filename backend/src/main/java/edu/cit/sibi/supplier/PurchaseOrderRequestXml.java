package edu.cit.sibi.supplier;

import com.fasterxml.jackson.dataformat.xml.annotation.JacksonXmlProperty;
import com.fasterxml.jackson.dataformat.xml.annotation.JacksonXmlRootElement;

/** POST /purchase-orders request body. */
@JacksonXmlRootElement(localName = "PurchaseOrder")
class PurchaseOrderRequestXml {

    @JacksonXmlProperty(localName = "SupplierSku")
    public String supplierSku;

    @JacksonXmlProperty(localName = "Qty")
    public int qty;

    @JacksonXmlProperty(localName = "BuyerRef")
    public String buyerRef;

    public PurchaseOrderRequestXml() {
    }

    public PurchaseOrderRequestXml(String supplierSku, int qty, String buyerRef) {
        this.supplierSku = supplierSku;
        this.qty = qty;
        this.buyerRef = buyerRef;
    }
}
