package com.example.dbadmin.desktop;

import org.junit.jupiter.api.Test;
import org.springframework.context.ConfigurableApplicationContext;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

class DesktopLifecycleServiceTest {
    @Test
    void validatesControlTokenWithoutLeakingPartialMatches() {
        DesktopRuntimeProperties properties = new DesktopRuntimeProperties("expected-token", 42);
        DesktopLifecycleService service = new DesktopLifecycleService(properties, mock(ConfigurableApplicationContext.class), _pid -> true);

        assertThat(service.authorized("expected-token")).isTrue();
        assertThat(service.authorized("expected")).isFalse();
        assertThat(service.authorized(null)).isFalse();
    }
}
