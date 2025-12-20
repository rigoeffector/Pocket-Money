package com.pocketmoney.pocketmoney.config;

import com.pocketmoney.pocketmoney.entity.Auth;
import com.pocketmoney.pocketmoney.entity.Role;
import com.pocketmoney.pocketmoney.repository.AuthRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.CommandLineRunner;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Component;

@Component
public class DataInitializer implements CommandLineRunner {

    private static final Logger logger = LoggerFactory.getLogger(DataInitializer.class);
    
    private final AuthRepository authRepository;
    private final PasswordEncoder passwordEncoder;

    // Default admin credentials
    private static final String DEFAULT_ADMIN_USERNAME = "admin";
    private static final String DEFAULT_ADMIN_EMAIL = "admin@pocketmoney.com";
    private static final String DEFAULT_ADMIN_PASSWORD = "admin123";

    public DataInitializer(AuthRepository authRepository, PasswordEncoder passwordEncoder) {
        this.authRepository = authRepository;
        this.passwordEncoder = passwordEncoder;
    }

    @Override
    public void run(String... args) throws Exception {
        // Check if admin user exists
        boolean adminExists = !authRepository.findByRole(Role.ADMIN).isEmpty();

        if (!adminExists) {
            logger.info("No admin user found. Creating default admin user...");
            
            // Check if username or email already exists (shouldn't, but safety check)
            if (authRepository.existsByUsername(DEFAULT_ADMIN_USERNAME)) {
                logger.warn("Username '{}' already exists. Skipping admin creation.", DEFAULT_ADMIN_USERNAME);
                return;
            }
            
            if (authRepository.existsByEmail(DEFAULT_ADMIN_EMAIL)) {
                logger.warn("Email '{}' already exists. Skipping admin creation.", DEFAULT_ADMIN_EMAIL);
                return;
            }

            // Create default admin user
            Auth admin = new Auth();
            admin.setUsername(DEFAULT_ADMIN_USERNAME);
            admin.setEmail(DEFAULT_ADMIN_EMAIL);
            admin.setPassword(passwordEncoder.encode(DEFAULT_ADMIN_PASSWORD));
            admin.setRole(Role.ADMIN);

            authRepository.save(admin);
            
            logger.info("Default admin user created successfully!");
            logger.info("Username: {}", DEFAULT_ADMIN_USERNAME);
            logger.info("Email: {}", DEFAULT_ADMIN_EMAIL);
            logger.info("Password: {}", DEFAULT_ADMIN_PASSWORD);
            logger.warn("Please change the default admin password after first login!");
        } else {
            logger.info("Admin user already exists. Skipping admin creation.");
        }
    }
}

