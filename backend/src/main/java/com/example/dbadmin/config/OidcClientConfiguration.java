package com.example.dbadmin.config;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.oauth2.client.registration.ClientRegistration;
import org.springframework.security.oauth2.client.registration.ClientRegistrationRepository;
import org.springframework.security.oauth2.client.registration.ClientRegistrations;
import org.springframework.security.oauth2.client.registration.InMemoryClientRegistrationRepository;

@Configuration
@ConditionalOnProperty(prefix = "app.auth", name = "mode", havingValue = "OIDC")
public class OidcClientConfiguration {
    @Bean
    ClientRegistrationRepository oidcClientRegistrationRepository(AppProperties properties) {
        AppProperties.Oidc oidc = properties.getAuth().getOidc();
        require(oidc.getIssuerUri(), "APP_AUTH_OIDC_ISSUER_URI");
        require(oidc.getClientId(), "APP_AUTH_OIDC_CLIENT_ID");
        ClientRegistration registration = ClientRegistrations.fromIssuerLocation(oidc.getIssuerUri().trim())
                .registrationId("mydatadev")
                .clientId(oidc.getClientId().trim())
                .clientSecret(oidc.getClientSecret())
                .scope(oidc.getScopes())
                .clientName("MyDataDev SSO")
                .build();
        return new InMemoryClientRegistrationRepository(registration);
    }

    private static void require(String value, String environmentName) {
        if (value == null || value.isBlank()) throw new IllegalStateException("OIDC 模式必须配置 " + environmentName);
    }
}
