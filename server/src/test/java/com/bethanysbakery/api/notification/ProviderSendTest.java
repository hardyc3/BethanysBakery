package com.bethanysbakery.api.notification;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.content;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.header;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.method;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

import com.bethanysbakery.api.config.NotificationProperties;
import com.bethanysbakery.api.notification.NotificationMessages.DeliveryResult;
import com.bethanysbakery.api.notification.NotificationMessages.EmailMessage;
import com.bethanysbakery.api.notification.NotificationMessages.PushMessage;
import com.bethanysbakery.api.notification.NotificationMessages.SmsMessage;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestTemplate;

class ProviderSendTest {

    private final ObjectMapper mapper = new ObjectMapper();
    private final NotificationProperties properties = new NotificationProperties(
        false, "admin@example.com",
        new NotificationProperties.Mailgun("key-123", "mg.example.com", "https://api.mailgun.net/v3",
            "Bakery <bakery@example.com>"),
        new NotificationProperties.Textbee("https://sms.example.com", "tb-key", "device-1", "secret"),
        new NotificationProperties.Fcm("project-1", "{}"));

    @Test
    void mailgunSendsFormWithBasicAuthAndParsesMessageId() {
        RestTemplate rest = new RestTemplate();
        MockRestServiceServer server = MockRestServiceServer.bindTo(rest).build();
        server.expect(requestTo("https://api.mailgun.net/v3/mg.example.com/messages"))
            .andExpect(method(HttpMethod.POST))
            .andExpect(header("Authorization", "Basic YXBpOmtleS0xMjM="))
            .andExpect(content().string(org.hamcrest.Matchers.containsString("subject=Order+BB-1")))
            .andRespond(withSuccess("{\"id\":\"<2026@mailgun>\"}", MediaType.APPLICATION_JSON));

        DeliveryResult result = new MailgunEmailSender(properties, mapper, rest).send(
            new EmailMessage("to@example.com", "Order BB-1", "<b>Hi</b>", "Hi"));

        assertThat(result.success()).isTrue();
        assertThat(result.providerMessageId()).isEqualTo("<2026@mailgun>");
        server.verify();
    }

    @Test
    void textbeeSendsJsonAndParsesProviderId() {
        RestTemplate rest = new RestTemplate();
        MockRestServiceServer server = MockRestServiceServer.bindTo(rest).build();
        server.expect(requestTo("https://sms.example.com/api/v1/sendSMS"))
            .andExpect(method(HttpMethod.POST))
            .andExpect(content().json("""
                {"apiKey":"tb-key","deviceID":"device-1","receiver":"+15555550123","message":"Order BB-1"}
                """))
            .andRespond(withSuccess("{\"success\":true,\"id\":\"sms-42\"}", MediaType.APPLICATION_JSON));

        DeliveryResult result = new TextbeeSmsSender(properties, mapper, rest).send(
            new SmsMessage("+15555550123", "Order BB-1"));

        assertThat(result.success()).isTrue();
        assertThat(result.providerMessageId()).isEqualTo("sms-42");
        server.verify();
    }

    @Test
    void fcmClassifiesInvalidTokens() {
        assertThat(FcmPushSender.invalidToken("{\"error\":{\"status\":\"UNREGISTERED\"}}")).isTrue();
        assertThat(FcmPushSender.invalidToken("{\"error\":{\"status\":\"INVALID_ARGUMENT\"}}")).isTrue();
        assertThat(FcmPushSender.invalidToken("{\"error\":{\"status\":\"INTERNAL\"}}")).isFalse();
    }

    @Test
    void localSendersNeverNeedConfiguration() {
        NotificationProperties empty = new NotificationProperties(false, null, null, null, null);
        assertThat(new LocalEmailSender().send(new EmailMessage("a@b.c", "s", "h", "t")).success()).isTrue();
        assertThat(new LocalSmsSender().send(new SmsMessage("+15555550123", "body")).success()).isTrue();
        assertThat(new LocalPushSender().send(new PushMessage("token", "title", "body")).success()).isTrue();
        assertThat(empty.mailgunConfigured()).isFalse();
    }
}
