package com.pabonaki.model;

import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class OrderTest {
    @Test void defaultsToCodAndUnpaid() {
        Order order = new Order();
        assertEquals("COD", order.getPaymentMethod());
        assertEquals("UNPAID", order.getPaymentStatus());
        assertEquals("PENDING", order.getStatus());
        assertNotNull(order.getCreatedAt());
        assertNotNull(order.getUpdatedAt());
    }

    @Test void onlinePaymentFieldsCanBePersisted() {
        Order order = new Order();
        order.setPaymentMethod("ONLINE");
        order.setPaymentStatus("PAID");
        order.setTransactionId("PNPAY-test");
        assertEquals("ONLINE", order.getPaymentMethod());
        assertEquals("PAID", order.getPaymentStatus());
        assertEquals("PNPAY-test", order.getTransactionId());
    }
}
