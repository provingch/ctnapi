package ctn.informatica.ctnapi.core;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.List;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.annotation.Order;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.authentication.UsernamePasswordAuthenticationFilter;
import org.springframework.web.filter.OncePerRequestFilter;

@Configuration
public class CoreSecurityConfig {

    private static final long MAX_REQUEST_BYTES = 1024 * 1024;

    @Bean
    @Order(1)
    public SecurityFilterChain coreFilterChain(HttpSecurity http,
            @Value("${ctnapi.core-api-key:}") String apiKey,
            @Value("${ctnapi.core-write-enabled:false}") boolean writeEnabled) throws Exception {
        http
            .securityMatcher("/api/core/**")
            .csrf(csrf -> csrf.disable())
            .sessionManagement(sm -> sm.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
            .authorizeHttpRequests(auth -> auth.anyRequest().hasRole("SERVICE_CORE"))
            .addFilterBefore(new ServiceKeyFilter(apiKey, writeEnabled), UsernamePasswordAuthenticationFilter.class);
        return http.build();
    }

    static class ServiceKeyFilter extends OncePerRequestFilter {

        private final byte[] expected;
        private final boolean writeEnabled;

        ServiceKeyFilter(String apiKey, boolean writeEnabled) {
            expected = apiKey == null || apiKey.isBlank() ? null : sha256(apiKey);
            this.writeEnabled = writeEnabled;
        }

        @Override
        protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
                throws ServletException, IOException {
            if (expected == null) {
                write(response, 503, "La API de datos compartidos no está habilitada");
                return;
            }
            String provided = request.getHeader("X-Service-Key");
            if (provided == null || !MessageDigest.isEqual(sha256(provided), expected)) {
                write(response, 401, "Credencial de servicio inválida o ausente");
                return;
            }
            if (!writeEnabled && List.of("POST", "PUT", "PATCH", "DELETE").contains(request.getMethod())) {
                write(response, 503, "La escritura del Core está deshabilitada mientras SCA sea la fuente de datos");
                return;
            }
            if (request.getContentLengthLong() > MAX_REQUEST_BYTES) {
                write(response, 413, "El cuerpo de la solicitud supera el tamaño máximo permitido");
                return;
            }
            SecurityContextHolder.getContext().setAuthentication(new UsernamePasswordAuthenticationToken(
                    "servicio-colegio", null, List.of(new SimpleGrantedAuthority("ROLE_SERVICE_CORE"))));
            chain.doFilter(request, response);
        }

        private static void write(HttpServletResponse response, int status, String message) throws IOException {
            response.setStatus(status);
            response.setContentType("application/json;charset=UTF-8");
            response.getWriter().write("{\"ok\":false,\"error\":\"" + message + "\"}");
        }

    }

    private static byte[] sha256(String value) {
        try {
            return MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8));
        } catch (java.security.NoSuchAlgorithmException ex) {
            throw new IllegalStateException(ex);
        }
    }
}
