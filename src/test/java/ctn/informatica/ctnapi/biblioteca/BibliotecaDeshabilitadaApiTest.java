package ctn.informatica.ctnapi.biblioteca;

import static org.mockito.Mockito.verifyNoInteractions;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

@SpringBootTest
@AutoConfigureMockMvc
@TestPropertySource(properties = "biblioteca.api-key=")
class BibliotecaDeshabilitadaApiTest {

    @Autowired
    private MockMvc mockMvc;

    @MockitoBean
    private BibliotecaDao dao;

    @Test
    void sinClaveConfiguradaResponde503() throws Exception {
        mockMvc.perform(get("/api/biblioteca/v1/alumnos").param("ci", "123").header("X-API-Key", "cualquiera"))
                .andExpect(status().isServiceUnavailable())
                .andExpect(jsonPath("$.ok").value(false))
                .andExpect(jsonPath("$.error").value("La API de biblioteca no está habilitada"));
        verifyNoInteractions(dao);
    }
}
