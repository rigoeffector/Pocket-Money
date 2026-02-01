package com.pocketmoney.pocketmoney.service;

import com.pocketmoney.pocketmoney.dto.DailyReportProductSummary;
import com.pocketmoney.pocketmoney.entity.PaymentCategory;
import com.pocketmoney.pocketmoney.entity.Receiver;
import com.pocketmoney.pocketmoney.entity.Transaction;
import com.pocketmoney.pocketmoney.entity.TransactionStatus;
import com.pocketmoney.pocketmoney.entity.TransactionType;
import com.pocketmoney.pocketmoney.repository.PaymentCategoryRepository;
import com.pocketmoney.pocketmoney.repository.ReceiverRepository;
import com.pocketmoney.pocketmoney.repository.TransactionRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.stream.Collectors;

@Service
public class ReportService {

    private static final Logger logger = LoggerFactory.getLogger(ReportService.class);

    private final TransactionRepository transactionRepository;
    private final PaymentCategoryRepository paymentCategoryRepository;
    private final ReceiverRepository receiverRepository;
    private final PdfExportService pdfExportService;

    public ReportService(TransactionRepository transactionRepository,
                        PaymentCategoryRepository paymentCategoryRepository,
                        ReceiverRepository receiverRepository,
                        PdfExportService pdfExportService) {
        this.transactionRepository = transactionRepository;
        this.paymentCategoryRepository = paymentCategoryRepository;
        this.receiverRepository = receiverRepository;
        this.pdfExportService = pdfExportService;
    }

    /**
     * Generate daily report PDF for GASOLINE and DIESEL transactions
     * @param date The date for the report (defaults to today if null)
     * @param receiverId Optional receiver ID (if not provided, uses authenticated merchant)
     * @return PDF bytes
     */
    @Transactional(readOnly = true)
    public byte[] generateDailyReportPdf(LocalDate date, UUID receiverId) {
        // Use today's date if not provided
        if (date == null) {
            date = LocalDate.now();
        }

        // Get the merchant (main merchant if submerchant is authenticated)
        Receiver merchant = getMerchantForReport(receiverId);
        
        // Get main merchant (always use main merchant for reports)
        Receiver mainMerchant = merchant.getParentReceiver() != null ? merchant.getParentReceiver() : merchant;

        // Get GASOLINE and DIESEL categories
        Optional<PaymentCategory> gasolineCategory = paymentCategoryRepository.findByName("GASOLINE");
        Optional<PaymentCategory> dieselCategory = paymentCategoryRepository.findByName("DIESEL");

        if (gasolineCategory.isEmpty() && dieselCategory.isEmpty()) {
            throw new RuntimeException("GASOLINE and DIESEL categories not found in database");
        }

        // Calculate date range (start and end of the day)
        LocalDateTime startOfDay = date.atStartOfDay();
        LocalDateTime endOfDay = date.atTime(LocalTime.MAX);

        logger.info("Generating daily report for merchant: {}, date: {} ({} to {})", 
            mainMerchant.getCompanyName(), date, startOfDay, endOfDay);

        // Query transactions for the date and merchant
        List<Transaction> allTransactions = transactionRepository.findByReceiverOrderByCreatedAtDescWithUser(mainMerchant);
        
        // Filter by date, status, type, and categories
        List<Transaction> filteredTransactions = allTransactions.stream()
            .filter(t -> {
                // Filter by date
                if (t.getCreatedAt().isBefore(startOfDay) || t.getCreatedAt().isAfter(endOfDay)) {
                    return false;
                }
                // Filter by status (only SUCCESS)
                if (t.getStatus() != TransactionStatus.SUCCESS) {
                    return false;
                }
                // Filter by transaction type (only PAYMENT)
                if (t.getTransactionType() != TransactionType.PAYMENT) {
                    return false;
                }
                // Filter by category (GASOLINE or DIESEL)
                if (t.getPaymentCategory() == null) {
                    return false;
                }
                String categoryName = t.getPaymentCategory().getName();
                return "GASOLINE".equals(categoryName) || "DIESEL".equals(categoryName);
            })
            .collect(Collectors.toList());

        logger.info("Found {} GASOLINE/DIESEL transactions for date {}", filteredTransactions.size(), date);

        // Group by product and calculate totals
        List<DailyReportProductSummary> productSummaries = new ArrayList<>();

        // GASOLINE summary
        if (gasolineCategory.isPresent()) {
            DailyReportProductSummary gasolineSummary = calculateProductSummary(
                filteredTransactions, gasolineCategory.get().getId(), "GASOLINE");
            productSummaries.add(gasolineSummary);
        }

        // DIESEL summary
        if (dieselCategory.isPresent()) {
            DailyReportProductSummary dieselSummary = calculateProductSummary(
                filteredTransactions, dieselCategory.get().getId(), "DIESEL");
            productSummaries.add(dieselSummary);
        }

        // Generate PDF
        return pdfExportService.generateDailyReportPdf(mainMerchant, productSummaries, date);
    }

    /**
     * Calculate summary for a specific product
     */
    private DailyReportProductSummary calculateProductSummary(List<Transaction> transactions, UUID categoryId, String productName) {
        List<Transaction> productTransactions = transactions.stream()
            .filter(t -> t.getPaymentCategory() != null && t.getPaymentCategory().getId().equals(categoryId))
            .collect(Collectors.toList());

        long transactionCount = productTransactions.size();
        BigDecimal totalAmount = productTransactions.stream()
            .map(Transaction::getAmount)
            .reduce(BigDecimal.ZERO, BigDecimal::add);

        // Calculate volume - try to extract from message or calculate
        // For now, we'll set volume to 0.00 if not available in message
        // TODO: Add volume field to Transaction entity or extract from message
        BigDecimal totalVolume = BigDecimal.ZERO;
        for (Transaction t : productTransactions) {
            // Try to extract volume from message if it contains volume information
            // Example: "35.00 L" or "35 Ltr" or similar
            if (t.getMessage() != null) {
                String message = t.getMessage().toLowerCase();
                // Simple extraction - look for numbers followed by "l" or "litre" or "liter"
                // This is a basic implementation - can be improved
                java.util.regex.Pattern pattern = java.util.regex.Pattern.compile("(\\d+\\.?\\d*)\\s*(?:l|litre|liter|lt|litres|liters)");
                java.util.regex.Matcher matcher = pattern.matcher(message);
                if (matcher.find()) {
                    try {
                        BigDecimal volume = new BigDecimal(matcher.group(1));
                        totalVolume = totalVolume.add(volume);
                    } catch (NumberFormatException e) {
                        // Ignore if parsing fails
                    }
                }
            }
        }

        return new DailyReportProductSummary(productName, transactionCount, totalVolume, totalAmount);
    }

    /**
     * Get the merchant for the report (main merchant if submerchant is authenticated)
     */
    private Receiver getMerchantForReport(UUID receiverId) {
        Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
        
        if (authentication == null || !authentication.isAuthenticated()) {
            throw new RuntimeException("User not authenticated");
        }

        // Check if user is ADMIN
        boolean isAdmin = authentication.getAuthorities().stream()
            .map(GrantedAuthority::getAuthority)
            .anyMatch(auth -> auth.toString().equals("ROLE_ADMIN"));

        if (isAdmin) {
            // ADMIN must provide receiverId
            if (receiverId == null) {
                throw new RuntimeException("ADMIN users must provide receiverId");
            }
            return receiverRepository.findById(receiverId)
                .orElseThrow(() -> new RuntimeException("Receiver not found with ID: " + receiverId));
        }

        // RECEIVER users
        String username = authentication.getName();
        Receiver merchant = receiverRepository.findByUsername(username)
            .orElseThrow(() -> new RuntimeException("Merchant not found with username: " + username));

        // If receiverId is provided, verify it belongs to the same merchant hierarchy
        if (receiverId != null) {
            Receiver providedReceiver = receiverRepository.findById(receiverId)
                .orElseThrow(() -> new RuntimeException("Receiver not found with ID: " + receiverId));
            
            // Get main merchants
            Receiver merchantMain = merchant.getParentReceiver() != null ? merchant.getParentReceiver() : merchant;
            Receiver providedMain = providedReceiver.getParentReceiver() != null 
                ? providedReceiver.getParentReceiver() 
                : providedReceiver;
            
            if (!merchantMain.getId().equals(providedMain.getId())) {
                throw new RuntimeException("Cannot generate report for a different merchant");
            }
            
            return providedReceiver;
        }

        return merchant;
    }
}
