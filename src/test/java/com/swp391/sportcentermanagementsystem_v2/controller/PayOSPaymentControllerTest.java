package com.swp391.sportcentermanagementsystem_v2.controller;

import com.swp391.sportcentermanagementsystem_v2.service.OnlinePaymentService;
import com.swp391.sportcentermanagementsystem_v2.service.PayOSGatewayClient;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import vn.payos.exception.WebhookException;
import vn.payos.model.webhooks.Webhook;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class PayOSPaymentControllerTest {

    @Mock
    PayOSGatewayClient gatewayClient;
    @Mock
    OnlinePaymentService onlinePaymentService;

    @InjectMocks
    PayOSPaymentController controller;

    @Test
    void webhookWithInvalidSignatureIsRejectedAndIgnored() {
        when(gatewayClient.verifyWebhook(any())).thenThrow(new WebhookException("invalid signature"));

        ResponseEntity<Void> response = controller.webhook(new Webhook());

        assertEquals(HttpStatus.BAD_REQUEST, response.getStatusCode());
        verify(onlinePaymentService, never()).confirmFromWebhook(anyLong());
    }

    @Test
    void webhookWithValidSignatureConfirmsThePayment() {
        when(gatewayClient.verifyWebhook(any())).thenReturn(123L);

        ResponseEntity<Void> response = controller.webhook(new Webhook());

        assertEquals(HttpStatus.OK, response.getStatusCode());
        verify(onlinePaymentService).confirmFromWebhook(123L);
    }
}
