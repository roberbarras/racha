package io.github.roberbarras.racha.shared.adapter.web;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletRequestWrapper;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import org.springframework.web.filter.OncePerRequestFilter;

/**
 * Makes {@link HttpServletRequest#getRemoteAddr()} the client's address when the request comes through a trusted
 * proxy ({@link TrustedProxies}), so throttling, logs and sessions see real clients, not the proxy.
 */
public final class ClientAddressFilter extends OncePerRequestFilter {
    private final TrustedProxies proxies;

    public ClientAddressFilter(TrustedProxies proxies) {
        this.proxies = proxies;
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        var client = proxies.clientOf(request.getRemoteAddr(), request.getHeader("CF-Connecting-IP"), request.getHeader("X-Forwarded-For"));
        if (client.equals(request.getRemoteAddr())) {
            chain.doFilter(request, response);
            return;
        }
        chain.doFilter(new HttpServletRequestWrapper(request) {
            @Override
            public String getRemoteAddr() {
                return client;
            }

            @Override
            public String getRemoteHost() {
                return client;
            }
        }, response);
    }
}
