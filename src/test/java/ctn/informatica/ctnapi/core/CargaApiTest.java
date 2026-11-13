package ctn.informatica.ctnapi.core;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.RequestPostProcessor;

@SpringBootTest
@AutoConfigureMockMvc
@TestPropertySource(properties = {
        "ctnapi.core-api-key=clave-core-test",
        "ctnapi.carga-api-key=clave-carga-test",
        "ctnapi.core-write-enabled=false"})
class CargaApiTest {

    @Autowired
    private MockMvc mockMvc;

    @MockitoBean
    private CoreDao dao;

    private static RequestPostProcessor desde(String ip) {
        return request -> {
            request.setRemoteAddr(ip);
            return request;
        };
    }

    @Test
    void conLaClaveDeCargaLeeElNucleo() throws Exception {
        when(dao.especialidades()).thenReturn(List.of(Map.of("id", 5, "nombre", "Informática")));
        mockMvc.perform(get("/api/carga/v1/especialidades").header("X-Service-Key", "clave-carga-test").with(desde("10.0.0.1")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.datos[0].nombre").value("Informática"));
    }

    @Test
    void lasClavesNoSeIntercambian() throws Exception {
        mockMvc.perform(get("/api/carga/v1/especialidades").header("X-Service-Key", "clave-core-test").with(desde("10.0.0.2")))
                .andExpect(status().isUnauthorized());
        mockMvc.perform(get("/api/core/v1/especialidades").header("X-Service-Key", "clave-carga-test"))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void bloqueaLaIpTrasVariosIntentosFallidos() throws Exception {
        for (int i = 0; i < 5; i++) {
            mockMvc.perform(get("/api/carga/v1/especialidades").header("X-Service-Key", "mala").with(desde("10.0.0.3")))
                    .andExpect(status().isUnauthorized());
        }
        mockMvc.perform(get("/api/carga/v1/especialidades").header("X-Service-Key", "clave-carga-test").with(desde("10.0.0.3")))
                .andExpect(status().isTooManyRequests())
                .andExpect(header().exists("Retry-After"));
        // Otra IP no queda afectada.
        mockMvc.perform(get("/api/carga/v1/especialidades").header("X-Service-Key", "clave-carga-test").with(desde("10.0.0.4")))
                .andExpect(status().isOk());
    }

    @Test
    void laEscrituraSigueSujetaACoreWriteEnabled() throws Exception {
        mockMvc.perform(post("/api/carga/v1/alumnos").header("X-Service-Key", "clave-carga-test").with(desde("10.0.0.5"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"nombre\":\"Ana\",\"apellido\":\"Prueba\",\"cursoId\":1}"))
                .andExpect(status().isServiceUnavailable());
        verify(dao, never()).crearAlumno(any());
    }
}
