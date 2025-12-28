package com.pocketmoney.pocketmoney.util;

import com.pocketmoney.pocketmoney.entity.Role;
import io.jsonwebtoken.Claims;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.security.Keys;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import javax.crypto.SecretKey;
import java.nio.charset.StandardCharsets;
import java.util.Date;
import java.util.UUID;

@Component
public class JwtUtil {

    @Value("${jwt.secret}")
    private String secretKey;

    @Value("${jwt.expiration}")
    private Long expiration;

    private SecretKey getSigningKey() {
        return Keys.hmacShaKeyFor(secretKey.getBytes(StandardCharsets.UTF_8));
    }

    public String generateToken(String username, Role role) {
        Date now = new Date();
        Date expiryDate = new Date(now.getTime() + expiration);

        return Jwts.builder()
                .subject(username)
                .claim("role", role.name())
                .claim("type", "AUTH")
                .issuedAt(now)
                .expiration(expiryDate)
                .signWith(getSigningKey())
                .compact();
    }

    public String generateTokenWithViewAs(String username, Role role, UUID viewAsReceiverId) {
        Date now = new Date();
        Date expiryDate = new Date(now.getTime() + expiration);

        return Jwts.builder()
                .subject(username)
                .claim("role", role.name())
                .claim("type", "AUTH")
                .claim("viewAsReceiverId", viewAsReceiverId.toString())
                .issuedAt(now)
                .expiration(expiryDate)
                .signWith(getSigningKey())
                .compact();
    }

    public String generateReceiverToken(String username) {
        Date now = new Date();
        Date expiryDate = new Date(now.getTime() + expiration);

        return Jwts.builder()
                .subject(username)
                .claim("role", Role.RECEIVER.name())
                .claim("type", "RECEIVER")
                .issuedAt(now)
                .expiration(expiryDate)
                .signWith(getSigningKey())
                .compact();
    }

    /**
     * Generates a RECEIVER token with a viewAsReceiverId claim.
     * The token subject (username) remains the main merchant's username.
     * The viewAsReceiverId claim indicates which submerchant is being viewed.
     * 
     * @param username The main merchant's username (used as token subject)
     * @param viewAsReceiverId The submerchant/receiver ID being viewed (added as claim)
     * @return JWT token with main merchant's username and viewAsReceiverId claim
     */
    public String generateReceiverTokenWithViewAs(String username, UUID viewAsReceiverId) {
        Date now = new Date();
        Date expiryDate = new Date(now.getTime() + expiration);

        // Token subject is the main merchant's username - this keeps the token as main merchant's token
        // viewAsReceiverId is just a claim to indicate which submerchant data to show
        return Jwts.builder()
                .subject(username) // Main merchant's username (NOT the submerchant's)
                .claim("role", Role.RECEIVER.name())
                .claim("type", "RECEIVER")
                .claim("viewAsReceiverId", viewAsReceiverId.toString()) // Submerchant ID for viewing context
                .issuedAt(now)
                .expiration(expiryDate)
                .signWith(getSigningKey())
                .compact();
    }

    public String generateUserToken(String phoneNumber) {
        Date now = new Date();
        Date expiryDate = new Date(now.getTime() + expiration);

        return Jwts.builder()
                .subject(phoneNumber)
                .claim("type", "USER")
                .issuedAt(now)
                .expiration(expiryDate)
                .signWith(getSigningKey())
                .compact();
    }

    public String getUsernameFromToken(String token) {
        Claims claims = Jwts.parser()
                .verifyWith(getSigningKey())
                .build()
                .parseSignedClaims(token)
                .getPayload();
        return claims.getSubject();
    }

    public Role getRoleFromToken(String token) {
        Claims claims = Jwts.parser()
                .verifyWith(getSigningKey())
                .build()
                .parseSignedClaims(token)
                .getPayload();
        return Role.valueOf(claims.get("role", String.class));
    }

    public String getTypeFromToken(String token) {
        Claims claims = Jwts.parser()
                .verifyWith(getSigningKey())
                .build()
                .parseSignedClaims(token)
                .getPayload();
        return claims.get("type", String.class);
    }

    public UUID getViewAsReceiverIdFromToken(String token) {
        try {
            Claims claims = Jwts.parser()
                    .verifyWith(getSigningKey())
                    .build()
                    .parseSignedClaims(token)
                    .getPayload();
            String viewAsReceiverIdStr = claims.get("viewAsReceiverId", String.class);
            if (viewAsReceiverIdStr != null && !viewAsReceiverIdStr.isEmpty()) {
                return UUID.fromString(viewAsReceiverIdStr);
            }
            return null;
        } catch (Exception e) {
            return null;
        }
    }

    public boolean validateToken(String token) {
        try {
            Jwts.parser()
                    .verifyWith(getSigningKey())
                    .build()
                    .parseSignedClaims(token);
            return true;
        } catch (Exception e) {
            return false;
        }
    }
}

