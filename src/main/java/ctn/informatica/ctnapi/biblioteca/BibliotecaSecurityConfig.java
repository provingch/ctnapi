package ctn.informatica.ctnapi.biblioteca;

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

/**
 * Cadena de seguridad propia para {@code /api/biblioteca/**}: clave fija en {@code X-API-Key}
 * ({@code biblioteca.api-key}), distinta de las demás APIs. Sin clave configurada responde 503.
 * Es de solo lectura: cualquier método que no sea GET se rechaza.
 */
@Configuration
public class BibliotecaSecurityConfig {

    @Bean
    @Order(4)
    public SecurityFilterChain bibliotecaFilterChain(HttpSecurity http,
            @Value("${biblioteca.api-key:}") String apiKey) throws Exception {
        http
            .securityMatcher("/api/biblioteca/**")
            .csrf(csrf -> csrf.disable())
            .sessionManagement(sm -> sm.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
            .authorizeHttpRequests(auth -> auth.anyRequest().hasRole("APP_BIBLIOTECA"))
            // Instancia propia, no @Component: así no se registra también como filtro global.
            .addFilterBefore(new ApiKeyFilter(apiKey), UsernamePasswordAuthenticationFilter.class);
        return http.build();
    }

    static class ApiKeyFilter extends OncePerRequestFilter {

        private final byte[] expected;

        ApiKeyFilter(String apiKey) {
            this.expected = apiKey == null || apiKey.isBlank() ? null : sha256(apiKey);
        }

        @Override
        protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
                throws ServletException, IOException {
            if (expected == null) {
                write(response, 503, "La API de biblioteca no está habilitada");
                return;
            }
            String provided = request.getHeader("X-API-Key");
            if (provided == null || !MessageDigest.isEqual(sha256(provided), expected)) {
                write(response, 401, "Clave de la app inválida o ausente");
                return;
            }
            if (!"GET".equals(request.getMethod())) {
                response.setHeader("Allow", "GET");
                write(response, 405, "La API de biblioteca es de solo lectura");
                return;
            }
            SecurityContextHolder.getContext().setAuthentication(new UsernamePasswordAuthenticationToken(
                    "app-biblioteca", null, List.of(new SimpleGrantedAuthority("ROLE_APP_BIBLIOTECA"))));
            chain.doFilter(request, response);
        }

        private static void write(HttpServletResponse response, int status, String mensaje) throws IOException {
            response.setStatus(status);
            response.setContentType("application/json;charset=UTF-8");
            response.getWriter().write("{\"ok\":false,\"error\":\"" + mensaje + "\"}");
        }

        private static byte[] sha256(String value) {
            try {
                return MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8));
            } catch (java.security.NoSuchAlgorithmException ex) {
                throw new IllegalStateException(ex);
            }
        }
    }
}
