package com.bethanysbakery.api.auth.session;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;

import com.bethanysbakery.api.auth.device.AdminDeviceService;
import com.bethanysbakery.api.domain.auth.AdminUserRepository;
import com.bethanysbakery.api.domain.auth.CustomerRepository;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.servlet.FilterChain;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

class RequestAuthenticationFilterTest {

    private final ObjectMapper mapper = new ObjectMapper();

    @ParameterizedTest
    @ValueSource(strings = {
        "/admin/v1;x/app-info", "/admin/v1;x/orders", "/admin/v1;x/devices/pair"
    })
    void pathParametersAreForbiddenBeforeController(String path) throws Exception {
        MockHttpServletRequest request = new MockHttpServletRequest("GET", path);
        MockHttpServletResponse response = new MockHttpServletResponse();
        FilterChain chain = mock(FilterChain.class);
        RequestAuthenticationFilter filter = new RequestAuthenticationFilter(
            mock(JwtService.class), mock(AdminDeviceService.class), mock(CsrfService.class),
            mock(CustomerRepository.class), mock(AdminUserRepository.class), mock(CookieService.class), mapper
        );

        filter.doFilter(request, response, chain);

        assertThat(response.getStatus()).isEqualTo(403);
        JsonNode body = mapper.readTree(response.getContentAsString());
        assertThat(body.get("code").asText()).isEqualTo("FORBIDDEN");
        verifyNoInteractions(chain);
    }
}
