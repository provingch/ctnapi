package ctn.informatica.ctnapi.core;

import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

@SpringBootTest
@AutoConfigureMockMvc
@TestPropertySource(properties = "ctnapi.core-api-key=clave-core-readonly-test")
class CoreReadOnlyApiTest {

    @Autowired
    private MockMvc mockMvc;

    @MockitoBean
    private CoreDao dao;

    @Test
    void bloqueaEscriturasMientrasScaEsLaFuenteDeDatos() throws Exception {
        mockMvc.perform(post("/api/core/v1/perfiles")
                        .header("X-Service-Key", "clave-core-readonly-test")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"nombre":"Ana","apellido":"Docente","ci":1234567,"nivel":1,"especialidadId":2}
                                """))
                .andExpect(status().isServiceUnavailable())
                .andExpect(jsonPath("$.ok").value(false));
        verify(dao, never()).crearPerfil(org.mockito.ArgumentMatchers.any());
    }
}
