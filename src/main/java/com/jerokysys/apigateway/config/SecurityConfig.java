package com.jerokysys.apigateway.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpMethod;
import org.springframework.security.config.web.server.ServerHttpSecurity;
import org.springframework.security.oauth2.server.resource.authentication.ReactiveJwtAuthenticationConverter;
import org.springframework.security.web.server.SecurityWebFilterChain;

import com.jerokysys.apigateway.security.SecurityErrors;

/**
 * Seguridad en el API Gateway: primera capa de defensa.
 *
 * <p><b>Autenticacion</b>: toda peticion a una ruta protegida debe traer un JWT
 * valido; sin el (o vencido) responde 401 y el request nunca llega al
 * microservicio. <b>Autorizacion</b>: el rol del token se contrasta con la ruta
 * y el metodo; si no alcanza responde 403.
 *
 * <p>Los microservicios repiten esta validacion por su cuenta (segunda capa).
 */
@Configuration
public class SecurityConfig {

    /** Rutas del microservicio academico, tal como las ve el cliente. */
    private static final String[] ACADEMIC = {
            "/api/v1/classes/**", "/api/v1/disciplines/**", "/api/v1/enrollments/**",
            "/api/v1/exams/**", "/api/v1/exam-results/**"
    };

    /** Rutas del microservicio de facturacion. */
    private static final String[] INVOICES = {
            "/api/v1/invoices/**", "/api/v1/invoice-details/**"
    };

    @Bean
    public SecurityWebFilterChain securityWebFilterChain(ServerHttpSecurity http,
                                                         ReactiveJwtAuthenticationConverter jwtConverter) {
        return http
                .csrf(ServerHttpSecurity.CsrfSpec::disable)
                .httpBasic(ServerHttpSecurity.HttpBasicSpec::disable)
                .formLogin(ServerHttpSecurity.FormLoginSpec::disable)
                .authorizeExchange(exchanges -> exchanges
                        // ---------------------------- publicos ----------------------------
                        .pathMatchers(HttpMethod.POST, "/api/v1/login", "/api/v1/users").permitAll()
                        .pathMatchers("/docs/**", "/swagger-ui/**", "/swagger-ui.html",
                                "/webjars/**", "/v3/api-docs/**").permitAll()

                        // ------------------- usuarios: solo ADMIN --------------------------
                        .pathMatchers("/api/v1/users/**").hasRole("ADMIN")

                        // ------------- academic: lectura para los tres roles ---------------
                        .pathMatchers(HttpMethod.GET, ACADEMIC)
                                .hasAnyRole("ADMIN", "PROFESSOR", "SECRETARY")

                        // --------- academic: escritura solo ADMIN y PROFESSOR ---------------
                        .pathMatchers(HttpMethod.POST, ACADEMIC).hasAnyRole("ADMIN", "PROFESSOR")
                        .pathMatchers(HttpMethod.PUT, ACADEMIC).hasAnyRole("ADMIN", "PROFESSOR")
                        .pathMatchers(HttpMethod.DELETE, ACADEMIC).hasAnyRole("ADMIN", "PROFESSOR")

                        // ------- invoices: ADMIN y SECRETARY; PROFESSOR solo afuera ---------
                        .pathMatchers(HttpMethod.GET, INVOICES).hasAnyRole("ADMIN", "SECRETARY")
                        .pathMatchers(HttpMethod.POST, INVOICES).hasAnyRole("ADMIN", "SECRETARY")
                        .pathMatchers(HttpMethod.PUT, INVOICES).hasAnyRole("ADMIN", "SECRETARY")
                        .pathMatchers(HttpMethod.DELETE, INVOICES).hasAnyRole("ADMIN", "SECRETARY")

                        // ---------------------- todo lo demas ------------------------------
                        .anyExchange().authenticated())

                // Valida firma y vigencia del token (clave y algoritmo compartidos con auth).
                .oauth2ResourceServer(resourceServer -> resourceServer
                        .jwt(jwt -> jwt.jwtAuthenticationConverter(jwtConverter))
                        .authenticationEntryPoint(SecurityErrors::unauthorized)
                        .accessDeniedHandler(SecurityErrors::forbidden))

                // 401 sin token / 403 sin rol, siempre en JSON y sin trazas.
                .exceptionHandling(exceptions -> exceptions
                        .authenticationEntryPoint(SecurityErrors::unauthorized)
                        .accessDeniedHandler(SecurityErrors::forbidden))
                .build();
    }
}
