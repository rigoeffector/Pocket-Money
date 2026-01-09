package com.pocketmoney.pocketmoney.security;

import java.io.IOException;
import java.util.List;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.web.authentication.WebAuthenticationDetailsSource;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import com.pocketmoney.pocketmoney.util.JwtUtil;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

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
                
                logger.info("Token validated successfully - Subject: {}, Type: {}, Request: {}", subject, tokenType, request.getRequestURI());
                
                String authority;
                if ("AUTH".equals(tokenType)) {
                    // AUTH tokens have a role claim
                    try {
                        String role = jwtUtil.getRoleFromToken(token).name();
                        authority = "ROLE_" + role;
                    } catch (Exception e) {
                        logger.error("Error getting role from AUTH token for request {}: {}", request.getRequestURI(), e.getMessage(), e);
                        filterChain.doFilter(request, response);
                        return;
                    }
                } else if ("USER".equals(tokenType)) {
                    authority = "ROLE_USER";
                } else if ("RECEIVER".equals(tokenType)) {
                    authority = "ROLE_RECEIVER";
                } else {
                    // Unknown token type
                    logger.error("Unknown token type: {} for request: {}", tokenType, request.getRequestURI());
                    filterChain.doFilter(request, response);
                    return;
                }

                logger.info("Token processed - Type: {}, Authority: {}, Subject: {}, Request: {}", tokenType, authority, subject, request.getRequestURI());

                // Create authentication token
                List<SimpleGrantedAuthority> authorities = List.of(new SimpleGrantedAuthority(authority));
                UsernamePasswordAuthenticationToken authentication = 
                    new UsernamePasswordAuthenticationToken(subject, null, authorities);
                authentication.setDetails(new WebAuthenticationDetailsSource().buildDetails(request));

                // Set authentication in security context
                SecurityContextHolder.getContext().setAuthentication(authentication);
                logger.info("Authentication set in security context - User: {}, Authority: {}, Request: {}", subject, authority, request.getRequestURI());
            } else {
                logger.warn("Token validation failed for request: {}", request.getRequestURI());
                // Don't set authentication - let Spring Security handle unauthorized requests
            }
        } catch (Exception e) {
            // Token is invalid, log the error but continue without authentication
            logger.error("Error processing JWT token for request {}: {}", request.getRequestURI(), e.getMessage(), e);
            logger.error("Exception details: ", e);
            // Don't set authentication - let Spring Security handle unauthorized requests
        }

        filterChain.doFilter(request, response);
    }
}

