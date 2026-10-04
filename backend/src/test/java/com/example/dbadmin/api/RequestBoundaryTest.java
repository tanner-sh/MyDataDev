package com.example.dbadmin.api;
import org.junit.jupiter.api.Test;
import org.springframework.web.bind.annotation.*;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;
class RequestBoundaryTest {
    record Body(int number) {}
    @RestController static class Endpoint {
        @PostMapping(value="/boundary", consumes="application/json") Body post(@RequestBody Body body) { return body; }
        @GetMapping("/boundary") Body get(@RequestParam int number) { return new Body(number); }
        @DeleteMapping(value="/boundary", consumes="application/json") Body delete(@RequestBody Body body) { return body; }
    }
    @Test void rejectsMalformedRequestsWithoutTurningThemIntoServerFailures() throws Exception {
        var mvc = MockMvcBuilders.standaloneSetup(new Endpoint()).setControllerAdvice(new ApiExceptionHandler()).build();
        for (String body : new String[]{"{", "", "{\"number\":\"wrong\"}"}) {
            mvc.perform(post("/boundary").contentType("application/json").content(body)).andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value("BAD_REQUEST"));
        }
        mvc.perform(get("/boundary")).andExpect(status().isBadRequest());
        mvc.perform(get("/boundary").param("number", "wrong")).andExpect(status().isBadRequest());
        mvc.perform(post("/boundary").contentType("text/plain").content("{}")).andExpect(status().isUnsupportedMediaType());
        mvc.perform(delete("/boundary").contentType("text/plain").content("{}")).andExpect(status().isUnsupportedMediaType());
        mvc.perform(delete("/boundary").contentType("application/json").content("{")).andExpect(status().isBadRequest());
        mvc.perform(delete("/boundary").contentType("application/json").content("{\"number\":7}")).andExpect(status().isOk());
        mvc.perform(put("/boundary").contentType("application/json").content("{}")).andExpect(status().isMethodNotAllowed()).andExpect(header().string("Allow", org.hamcrest.Matchers.containsString("POST")));
        mvc.perform(post("/boundary").contentType("application/json").content("{\"number\":7}")).andExpect(status().isOk()).andExpect(jsonPath("$.number").value(7));
    }
}
