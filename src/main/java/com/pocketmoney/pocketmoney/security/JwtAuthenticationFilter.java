package com.pocketmoney.pocketmoney.security;

import com.pocketmoney.pocketmoney.util.JwtUtil;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.web.authentication.WebAuthenticationDetailsSource;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.util.List;

@Component
public class JwtAuthenticationFilter extends OncePerRequestFilter {

    private static final Logger logger = LoggerFactory.getLogger(JwtAuthenticationFilter.class);
    private final JwtUtil jwtUtil;

    public JwtAuthenticationFilter(JwtUtil jwtUtil) {
        this.jwtUtil = jwtUtil;
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain filterChain)
            throws ServletException, IOException {
        
        String authHeader = request.getHeader("Authorization");
        
        if (authHeader == null || authHeader.isEmpty()) {
            filterChain.doFilter(request, response);
            return;
        }

        // Use the Authorization header value directly as the token (no prefix)
        String token = authHeader.trim();

        try {
            if (jwtUtil.validateToken(token)) {
                String subject = jwtUtil.getUsernameFromToken(token);
                String tokenType = jwtUtil.getTypeFromToken(token);
                
                String authority;
                if ("AUTH".equals(tokenType)) {
                    // AUTH tokens have a role claim
                    try {
                        String role = jwtUtil.getRoleFromToken(token).name();
                        authority = "ROLE_" + role;
                    } catch (Exception e) {
                        logger.debug("Error getting role from AUTH token: {}", e.getMessage());
                        filterChain.doFilter(request, response);
                        return;
                    }
                } else if ("USER".equals(tokenType)) {
                    authority = "ROLE_USER";
                } else if ("RECEIVER".equals(tokenType)) {
                    authority = "ROLE_RECEIVER";
                } else {
                    // Unknown token type
                    logger.debug("Unknown token type: {}", tokenType);
                    filterChain.doFilter(request, response);
                    return;
                }

                logger.debug("Token validated - Type: {}, Authority: {}, Subject: {}", tokenType, authority, subject);

                // Create authentication token
                List<SimpleGrantedAuthority> authorities = List.of(new SimpleGrantedAuthority(authority));
                UsernamePasswordAuthenticationToken authentication = 
                    new UsernamePasswordAuthenticationToken(subject, null, authorities);
                authentication.setDetails(new WebAuthenticationDetailsSource().buildDetails(request));

                // Set authentication in security context
                SecurityContextHolder.getContext().setAuthentication(authentication);
            } else {
                logger.debug("Token validation failed for request: {}", request.getRequestURI());
            }
        } catch (Exception e) {
            // Token is invalid, continue without authentication
            logger.debug("Invalid JWT token for request {}: {}", request.getRequestURI(), e.getMessage());
        }

        filterChain.doFilter(request, response);
    }
}

