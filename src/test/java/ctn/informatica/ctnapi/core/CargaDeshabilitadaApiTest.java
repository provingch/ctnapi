package ctn.informatica.ctnapi.core;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
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
@TestPropertySource(properties = {"ctnapi.core-api-key=clave-core-test", "ctnapi.carga-api-key="})
class CargaDeshabilitadaApiTest {

    @Autowired
    private MockMvc mockMvc;

    @MockitoBean
    private CoreDao dao;

    @Test
    void sinClaveDeCargaResponde503AunqueSeUseLaDeServicio() throws Exception {
        mockMvc.perform(get("/api/carga/v1/especialidades").header("X-Service-Key", "clave-core-test"))
                .andExpect(status().isServiceUnavailable());
    }
}
