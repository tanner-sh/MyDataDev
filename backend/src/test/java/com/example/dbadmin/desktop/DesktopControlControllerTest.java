package com.example.dbadmin.desktop;

import org.junit.jupiter.api.Test;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

class DesktopControlControllerTest {
    @Test
    void rejectsInvalidTokenAndSchedulesAuthorizedShutdown() throws Exception {
        DesktopLifecycleService lifecycle = mock(DesktopLifecycleService.class);
        when(lifecycle.authorized("valid-token")).thenReturn(true);
        MockMvc mvc = MockMvcBuilders.standaloneSetup(new DesktopControlController(lifecycle)).build();

        mvc.perform(post("/internal/desktop/shutdown"))
                .andExpect(status().isForbidden());

        mvc.perform(post("/internal/desktop/shutdown")
                .header(DesktopControlController.CONTROL_HEADER, "valid-token"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.ok").value(true));

        verify(lifecycle).requestShutdown();
    }
}
