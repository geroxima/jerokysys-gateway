package com.jerokysys.apigateway.security;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.http.HttpCookie;
import org.springframework.http.HttpHeaders;
import org.springframework.http.server.reactive.ServerHttpRequest;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ServerWebExchange;
import org.springframework.web.server.WebFilter;
import org.springframework.web.server.WebFilterChain;

import reactor.core.publisher.Mono;

/**
 * Permite autenticar peticiones que vienen del navegador con la cookie
 * httpOnly {@code jerokysys_token} (en lugar de una cabecera Bearer).
 *
 * <p>El frontend guarda el JWT en una cookie httpOnly y no puede enviarlo como
 * cabecera; este filtro copia la cookie a {@code Authorization: Bearer <token>}
 * antes de que el resource server valide la peticion. Si ya hay cabecera
 * {@code Authorization}, la deja intacta.
 */
@Component
@Order(Ordered.HIGHEST_PRECEDENCE)
public class CookieTokenFilter implements WebFilter {

    private final String cookieName;

    public CookieTokenFilter(
            @Value("${app.auth.cookie:jerokysys_token}") String cookieName) {
        this.cookieName = cookieName;
    }

    @Override
    public Mono<Void> filter(ServerWebExchange exchange, WebFilterChain chain) {
        ServerHttpRequest request = exchange.getRequest();

        if (request.getHeaders().getFirst(HttpHeaders.AUTHORIZATION) != null) {
            return chain.filter(exchange);
        }

        HttpCookie cookie = request.getCookies().getFirst(cookieName);
        if (cookie == null || cookie.getValue() == null || cookie.getValue().isBlank()) {
            return chain.filter(exchange);
        }

        ServerHttpRequest mutated = request.mutate()
                .header(HttpHeaders.AUTHORIZATION, "Bearer " + cookie.getValue())
                .build();

        return chain.filter(exchange.mutate().request(mutated).build());
    }
}
