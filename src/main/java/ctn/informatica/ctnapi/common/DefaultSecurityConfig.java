package ctn.informatica.ctnapi.common;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.web.SecurityFilterChain;

/**
 * Cadena por defecto (la de menor prioridad): solo {@code /health} es público; todo lo que no
 * pertenezca a una API con su propia cadena ({@code /api/anotaciones}, {@code /api/enfermeria}) se rechaza.
 */
@Configuration
public class DefaultSecurityConfig {

    @Bean
    @Order(Ordered.LOWEST_PRECEDENCE)
    public SecurityFilterChain defaultFilterChain(HttpSecurity http) throws Exception {
        http
            .csrf(csrf -> csrf.disable())
            .sessionManagement(sm -> sm.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
            .authorizeHttpRequests(auth -> auth
                .requestMatchers("/health").permitAll()
                .anyRequest().denyAll());
        return http.build();
    }
}
