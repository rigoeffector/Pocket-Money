package com.pocketmoney.pocketmoney.config;

import com.pocketmoney.pocketmoney.entity.PaymentCategory;
import com.pocketmoney.pocketmoney.repository.PaymentCategoryRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.CommandLineRunner;
import org.springframework.stereotype.Component;

import java.util.Arrays;
import java.util.List;

@Component
public class PaymentCategoryDataInitializer implements CommandLineRunner {

    private static final Logger logger = LoggerFactory.getLogger(PaymentCategoryDataInitializer.class);
    
    private final PaymentCategoryRepository paymentCategoryRepository;

    public PaymentCategoryDataInitializer(PaymentCategoryRepository paymentCategoryRepository) {
        this.paymentCategoryRepository = paymentCategoryRepository;
    }

    @Override
    public void run(String... args) throws Exception {
        List<String> defaultCategories = Arrays.asList("Fuel", "Cantine", "EFASHE", "Other");

        for (String categoryName : defaultCategories) {
            if (!paymentCategoryRepository.existsByName(categoryName)) {
                logger.info("Creating default payment category: {}", categoryName);
                
                PaymentCategory paymentCategory = new PaymentCategory();
                paymentCategory.setName(categoryName);
                paymentCategory.setDescription("Default payment category: " + categoryName);
                paymentCategory.setIsActive(true);

                paymentCategoryRepository.save(paymentCategory);
                logger.info("Payment category '{}' created successfully", categoryName);
            }
        }

        logger.info("Payment category initialization completed");
    }
}

