package com.pocketmoney.pocketmoney.service;

import com.pocketmoney.pocketmoney.dto.PaymentResponse;
import com.pocketmoney.pocketmoney.dto.ReceiverTransactionsResponse;
import com.pocketmoney.pocketmoney.entity.EfasheServiceType;
import com.pocketmoney.pocketmoney.entity.EfasheTransaction;
import com.pocketmoney.pocketmoney.entity.Receiver;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDPage;
import org.apache.pdfbox.pdmodel.PDPageContentStream;
import org.apache.pdfbox.pdmodel.common.PDRectangle;
import org.apache.pdfbox.pdmodel.font.PDType1Font;
import org.apache.pdfbox.pdmodel.graphics.image.PDImageXObject;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.io.ClassPathResource;
import org.springframework.stereotype.Service;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.List;

@Service
public class PdfExportService {

    private static final Logger logger = LoggerFactory.getLogger(PdfExportService.class);
    private static final DateTimeFormatter DATE_TIME_FORMATTER = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");
    private static final float MARGIN = 40;
    private static final float LINE_HEIGHT = 12;
    private static final float TABLE_ROW_HEIGHT = 16;
    // A4 Landscape dimensions (swapped width/height)
    private static final float PAGE_WIDTH = PDRectangle.A4.getHeight(); // 841.89
    private static final float PAGE_HEIGHT = PDRectangle.A4.getWidth(); // 595.28

    public byte[] generateTransactionHistoryPdf(Receiver receiver, ReceiverTransactionsResponse response, 
                                                 LocalDateTime fromDate, LocalDateTime toDate) {
        try (PDDocument document = new PDDocument();
             ByteArrayOutputStream baos = new ByteArrayOutputStream()) {
            
            // Create landscape page (A4 rotated)
            PDPage page = new PDPage(new PDRectangle(PAGE_WIDTH, PAGE_HEIGHT));
            document.addPage(page);
            PDPageContentStream contentStream = new PDPageContentStream(document, page);
            
            try {
                float yPosition = PAGE_HEIGHT - MARGIN;
                
                // Title - reduced font size
                contentStream.beginText();
                contentStream.setFont(PDType1Font.HELVETICA_BOLD, 16);
                contentStream.newLineAtOffset(MARGIN, yPosition);
                contentStream.showText("Payment Transactions Report");
                contentStream.endText();
                yPosition -= 25;
                
                // Receiver Information - reduced font sizes
                contentStream.beginText();
                contentStream.setFont(PDType1Font.HELVETICA_BOLD, 11);
                contentStream.newLineAtOffset(MARGIN, yPosition);
                contentStream.showText("Receiver: " + receiver.getCompanyName());
                contentStream.endText();
                yPosition -= LINE_HEIGHT;
                
                if (receiver.getManagerName() != null) {
                    contentStream.beginText();
                    contentStream.setFont(PDType1Font.HELVETICA, 10);
                    contentStream.newLineAtOffset(MARGIN, yPosition);
                    contentStream.showText("Manager: " + receiver.getManagerName());
                    contentStream.endText();
                    yPosition -= LINE_HEIGHT;
                }
                
                // Date Range - Show prominently - reduced font sizes
                if (fromDate != null || toDate != null) {
                    contentStream.beginText();
                    contentStream.setFont(PDType1Font.HELVETICA_BOLD, 10);
                    contentStream.newLineAtOffset(MARGIN, yPosition);
                    contentStream.showText("Report Period:");
                    contentStream.endText();
                    yPosition -= LINE_HEIGHT;
                    
                    StringBuilder dateRange = new StringBuilder();
                    if (fromDate != null) {
                        dateRange.append("From: ").append(formatDate(fromDate));
                        if (fromDate.toLocalTime() != null && !fromDate.toLocalTime().equals(java.time.LocalTime.MIDNIGHT)) {
                            dateRange.append(" ").append(fromDate.format(DateTimeFormatter.ofPattern("HH:mm:ss")));
                        }
                    }
                    if (toDate != null) {
                        if (fromDate != null) {
                            dateRange.append("  |  ");
                        }
                        dateRange.append("To: ").append(formatDate(toDate));
                        if (toDate.toLocalTime() != null && !toDate.toLocalTime().equals(java.time.LocalTime.MIDNIGHT)) {
                            dateRange.append(" ").append(toDate.format(DateTimeFormatter.ofPattern("HH:mm:ss")));
                        }
                    }
                    contentStream.beginText();
                    contentStream.setFont(PDType1Font.HELVETICA, 9);
                    contentStream.newLineAtOffset(MARGIN, yPosition);
                    contentStream.showText(dateRange.toString());
                    contentStream.endText();
                    yPosition -= LINE_HEIGHT * 1.5f;
                } else {
                    // Show report generation date if no date range specified
                    contentStream.beginText();
                    contentStream.setFont(PDType1Font.HELVETICA, 9);
                    contentStream.newLineAtOffset(MARGIN, yPosition);
                    contentStream.showText("Report Date: " + formatDate(LocalDateTime.now()));
                    contentStream.endText();
                    yPosition -= LINE_HEIGHT * 1.5f;
                }
                
                // Statistics - reduced font sizes
                if (response.getStatistics() != null) {
                    ReceiverTransactionsResponse.TransactionStatistics stats = response.getStatistics();
                    
                    contentStream.beginText();
                    contentStream.setFont(PDType1Font.HELVETICA_BOLD, 11);
                    contentStream.newLineAtOffset(MARGIN, yPosition);
                    contentStream.showText("Summary Statistics");
                    contentStream.endText();
                    yPosition -= LINE_HEIGHT * 1.2f;
                    
                    // Statistics table with proper spacing
                    String[] statsLabels = {
                        "Total Transactions:",
                        "Total Revenue:",
                        "Successful Transactions:",
                        "Failed Transactions:",
                        "Unique Customers:"
                    };
                    String[] statsValues = {
                        formatNumberWithCommas(stats.getTotalTransactions()),
                        formatCurrency(stats.getTotalRevenue()),
                        formatNumberWithCommas(stats.getSuccessfulTransactions()),
                        formatNumberWithCommas(stats.getFailedTransactions()),
                        formatNumberWithCommas(stats.getTotalCustomers())
                    };
                    
                    float statsTableWidth = 300;
                    float statsTableX = MARGIN;
                    float statsRowHeight = 14; // Reduced row height
                    
                    // Draw statistics table background
                    contentStream.setNonStrokingColor(0.95f, 0.95f, 0.95f);
                    contentStream.addRect(statsTableX, yPosition - (statsLabels.length * statsRowHeight), 
                                        statsTableWidth, statsLabels.length * statsRowHeight);
                    contentStream.fill();
                    contentStream.setNonStrokingColor(0, 0, 0);
                    
                    // Draw statistics rows - reduced font sizes
                    float statsY = yPosition;
                    for (int i = 0; i < statsLabels.length; i++) {
                        // Label
                        contentStream.beginText();
                        contentStream.setFont(PDType1Font.HELVETICA_BOLD, 8);
                        contentStream.newLineAtOffset(statsTableX + 5, statsY - (statsRowHeight / 2) - 3);
                        contentStream.showText(statsLabels[i]);
                        contentStream.endText();
                        
                        // Value
                        contentStream.beginText();
                        contentStream.setFont(PDType1Font.HELVETICA, 8);
                        contentStream.newLineAtOffset(statsTableX + 180, statsY - (statsRowHeight / 2) - 3);
                        contentStream.showText(statsValues[i]);
                        contentStream.endText();
                        
                        // Draw row border
                        if (i < statsLabels.length - 1) {
                            contentStream.setStrokingColor(0.8f, 0.8f, 0.8f);
                            contentStream.setLineWidth(0.5f);
                            contentStream.moveTo(statsTableX, statsY - statsRowHeight);
                            contentStream.lineTo(statsTableX + statsTableWidth, statsY - statsRowHeight);
                            contentStream.stroke();
                        }
                        
                        statsY -= statsRowHeight;
                    }
                    
                    // Draw table border
                    contentStream.setStrokingColor(0, 0, 0);
                    contentStream.setLineWidth(1f);
                    float statsTableTop = yPosition;
                    float statsTableBottom = statsY;
                    contentStream.addRect(statsTableX, statsTableBottom, statsTableWidth, 
                                        statsTableTop - statsTableBottom);
                    contentStream.stroke();
                    
                    yPosition = statsY - LINE_HEIGHT;
                }
                
                // Transactions Table - reduced font sizes
                contentStream.beginText();
                contentStream.setFont(PDType1Font.HELVETICA_BOLD, 11);
                contentStream.newLineAtOffset(MARGIN, yPosition);
                contentStream.showText("Transactions");
                contentStream.endText();
                yPosition -= LINE_HEIGHT * 1.2f;
                
                List<PaymentResponse> transactions = response.getTransactions();
                if (transactions == null || transactions.isEmpty()) {
                    contentStream.beginText();
                    contentStream.setFont(PDType1Font.HELVETICA_OBLIQUE, 10);
                    contentStream.newLineAtOffset(MARGIN, yPosition);
                    contentStream.showText("No transactions found for the selected period.");
                    contentStream.endText();
                } else {
                    // Table configuration for landscape - optimized column widths with merchant name and payment type columns
                    float[] columnWidths = {95, 110, 90, 75, 75, 60, 65, 85, 115};
                    String[] headers = {"Date/Time", "Customer Names", "Merchant", "Payment Type", "Amount", "Bonus", "Status", "Payment Info", "Transaction ID"};
                    float tableStartX = MARGIN;
                    float cellPadding = 3;
                    float rowHeight = TABLE_ROW_HEIGHT; // Use constant (16)
                    float minYPosition = MARGIN + 40; // Minimum Y position before new page
                    
                    // Calculate total table width
                    float totalTableWidth = 0;
                    for (float width : columnWidths) {
                        totalTableWidth += width;
                    }
                    
                    // Draw table header
                    float headerY = yPosition;
                    drawTableHeader(contentStream, tableStartX, headerY, columnWidths, headers, rowHeight, cellPadding);
                    yPosition -= rowHeight;
                    
                    // Draw header border
                    contentStream.setStrokingColor(0, 0, 0);
                    contentStream.setLineWidth(1.5f);
                    contentStream.moveTo(tableStartX, headerY);
                    contentStream.lineTo(tableStartX + totalTableWidth, headerY);
                    contentStream.stroke();
                    contentStream.moveTo(tableStartX, headerY - rowHeight);
                    contentStream.lineTo(tableStartX + totalTableWidth, headerY - rowHeight);
                    contentStream.stroke();
                    
                    // Transaction rows
                    for (PaymentResponse transaction : transactions) {
                        // Check if we need a new page (landscape)
                        if (yPosition < minYPosition) {
                            contentStream.close();
                            page = new PDPage(new PDRectangle(PAGE_WIDTH, PAGE_HEIGHT));
                            document.addPage(page);
                            contentStream = new PDPageContentStream(document, page);
                            yPosition = PAGE_HEIGHT - MARGIN;
                            
                            // Redraw headers on new page
                            headerY = yPosition;
                            drawTableHeader(contentStream, tableStartX, headerY, columnWidths, headers, rowHeight, cellPadding);
                            yPosition -= rowHeight;
                            
                            // Draw header border
                            contentStream.setStrokingColor(0, 0, 0);
                            contentStream.setLineWidth(1.5f);
                            contentStream.moveTo(tableStartX, headerY);
                            contentStream.lineTo(tableStartX + totalTableWidth, headerY);
                            contentStream.stroke();
                            contentStream.moveTo(tableStartX, headerY - rowHeight);
                            contentStream.lineTo(tableStartX + totalTableWidth, headerY - rowHeight);
                            contentStream.stroke();
                        }
                        
                        // Prepare row data - increased truncation limits for landscape
                        String[] rowData = {
                            truncateText(formatDateTime(transaction.getCreatedAt()), 18),
                            truncateText(getCustomerName(transaction), 22),
                            truncateText(getMerchantName(transaction), 22),
                            truncateText(getPaymentType(transaction), 18),
                            truncateText(formatCurrency(transaction.getAmount()), 18),
                            truncateText(formatCurrency(transaction.getUserBonusAmount()), 18),
                            truncateText(transaction.getStatus() != null ? transaction.getStatus().toString() : "N/A", 15),
                            truncateText(getPaymentInfo(transaction), 20),
                            truncateText(transaction.getMopayTransactionId() != null ? transaction.getMopayTransactionId() : "N/A", 25)
                        };
                        
                        // Draw row with borders
                        drawTableRow(contentStream, tableStartX, yPosition, columnWidths, rowData, rowHeight, cellPadding);
                        
                        // Draw row bottom border
                        contentStream.setStrokingColor(0.7f, 0.7f, 0.7f);
                        contentStream.setLineWidth(0.5f);
                        contentStream.moveTo(tableStartX, yPosition - rowHeight);
                        contentStream.lineTo(tableStartX + totalTableWidth, yPosition - rowHeight);
                        contentStream.stroke();
                        
                        yPosition -= rowHeight;
                    }
                    
                    // Draw table left and right borders
                    contentStream.setStrokingColor(0, 0, 0);
                    contentStream.setLineWidth(1.5f);
                    float tableTop = headerY;
                    float tableBottom = yPosition + rowHeight;
                    contentStream.moveTo(tableStartX, tableTop);
                    contentStream.lineTo(tableStartX, tableBottom);
                    contentStream.stroke();
                    contentStream.moveTo(tableStartX + totalTableWidth, tableTop);
                    contentStream.lineTo(tableStartX + totalTableWidth, tableBottom);
                    contentStream.stroke();
                }
                
                // Footer - reduced font size
                contentStream.beginText();
                contentStream.setFont(PDType1Font.HELVETICA, 8);
                contentStream.newLineAtOffset(MARGIN, MARGIN);
                LocalDateTime now = LocalDateTime.now();
                contentStream.showText("Report Generated: " + formatDate(now) + " at " + 
                    now.format(DateTimeFormatter.ofPattern("HH:mm:ss")));
                contentStream.endText();
            } finally {
                if (contentStream != null) {
                    contentStream.close();
                }
            }
            
            document.save(baos);
            return baos.toByteArray();
        } catch (Exception e) {
            logger.error("Error generating PDF: ", e);
            throw new RuntimeException("Failed to generate PDF: " + e.getMessage(), e);
        }
    }

    private String formatCurrency(BigDecimal amount) {
        if (amount == null) {
            return "0.00";
        }
        return formatNumberWithCommas(amount);
    }
    
    private String formatNumberWithCommas(BigDecimal number) {
        if (number == null) {
            return "0.00";
        }
        // Format with 2 decimal places and thousand separators
        java.text.DecimalFormat formatter = new java.text.DecimalFormat("#,##0.00");
        return formatter.format(number);
    }
    
    private String formatNumberWithCommas(long number) {
        java.text.DecimalFormat formatter = new java.text.DecimalFormat("#,##0");
        return formatter.format(number);
    }

    private String formatDateTime(LocalDateTime dateTime) {
        if (dateTime == null) {
            return "N/A";
        }
        return dateTime.format(DATE_TIME_FORMATTER);
    }
    
    private String formatDate(LocalDateTime dateTime) {
        if (dateTime == null) {
            return "N/A";
        }
        return dateTime.format(DateTimeFormatter.ofPattern("yyyy-MM-dd"));
    }

    private String getCustomerName(PaymentResponse transaction) {
        if (transaction.getUser() != null && transaction.getUser().getFullNames() != null) {
            return transaction.getUser().getFullNames();
        }
        if (transaction.getPayerPhone() != null) {
            return transaction.getPayerPhone();
        }
        return "Guest";
    }
    
    private String getMerchantName(PaymentResponse transaction) {
        if (transaction.getReceiverCompanyName() != null) {
            return transaction.getReceiverCompanyName();
        }
        return "N/A";
    }
    
    private String getPaymentType(PaymentResponse transaction) {
        if (transaction.getPaymentCategory() != null && transaction.getPaymentCategory().getName() != null) {
            return transaction.getPaymentCategory().getName();
        }
        return "Others";
    }
    
    private String getPaymentInfo(PaymentResponse transaction) {
        if (transaction.getPaymentMethod() == null) {
            return "N/A";
        }
        
        if ("MOMO".equals(transaction.getPaymentMethod())) {
            // If paid with phone (MOMO), show phone number
            if (transaction.getPayerPhone() != null && !transaction.getPayerPhone().trim().isEmpty()) {
                return "Phone: " + transaction.getPayerPhone();
            }
            return "MOMO";
        } else if ("NFC_CARD".equals(transaction.getPaymentMethod())) {
            // If paid with NFC Card, show card number
            if (transaction.getUser() != null && transaction.getUser().getNfcCardId() != null) {
                return "Card: " + transaction.getUser().getNfcCardId();
            }
            return "NFC_CARD";
        }
        
        return transaction.getPaymentMethod();
    }
    
    private void drawTableHeader(PDPageContentStream contentStream, float startX, float startY, 
                                 float[] columnWidths, String[] headers, float rowHeight, float cellPadding) 
                                 throws Exception {
        // Draw header background
        float totalWidth = 0;
        for (float width : columnWidths) {
            totalWidth += width;
        }
        contentStream.setNonStrokingColor(0.85f, 0.85f, 0.85f);
        contentStream.addRect(startX, startY - rowHeight, totalWidth, rowHeight);
        contentStream.fill();
        contentStream.setNonStrokingColor(0, 0, 0);
        
        // Draw header text - reduced font size for landscape
        float currentX = startX + cellPadding;
        float textY = startY - (rowHeight / 2) - 3; // Center vertically
        
        for (int i = 0; i < headers.length && i < columnWidths.length; i++) {
            contentStream.beginText();
            contentStream.setFont(PDType1Font.HELVETICA_BOLD, 7);
            contentStream.newLineAtOffset(currentX, textY);
            contentStream.showText(headers[i]);
            contentStream.endText();
            
            // Draw vertical border between columns
            if (i < headers.length - 1) {
                contentStream.setStrokingColor(0.5f, 0.5f, 0.5f);
                contentStream.setLineWidth(0.5f);
                contentStream.moveTo(currentX + columnWidths[i] - cellPadding, startY);
                contentStream.lineTo(currentX + columnWidths[i] - cellPadding, startY - rowHeight);
                contentStream.stroke();
            }
            
            currentX += columnWidths[i];
        }
    }
    
    private void drawTableRow(PDPageContentStream contentStream, float startX, float startY, 
                              float[] columnWidths, String[] rowData, float rowHeight, float cellPadding) 
                              throws Exception {
        float currentX = startX + cellPadding;
        float textY = startY - (rowHeight / 2) - 3; // Center vertically
        
        for (int i = 0; i < rowData.length && i < columnWidths.length; i++) {
            contentStream.beginText();
            contentStream.setFont(PDType1Font.HELVETICA, 7); // Reduced font size for landscape
            contentStream.newLineAtOffset(currentX, textY);
            String text = rowData[i] != null ? rowData[i] : "";
            contentStream.showText(text);
            contentStream.endText();
            
            // Draw vertical border between columns
            if (i < rowData.length - 1) {
                contentStream.setStrokingColor(0.5f, 0.5f, 0.5f);
                contentStream.setLineWidth(0.5f);
                contentStream.moveTo(currentX + columnWidths[i] - cellPadding, startY);
                contentStream.lineTo(currentX + columnWidths[i] - cellPadding, startY - rowHeight);
                contentStream.stroke();
            }
            
            currentX += columnWidths[i];
        }
    }
    
    private String truncateText(String text, int maxLength) {
        if (text == null) {
            return "";
        }
        if (text.length() <= maxLength) {
            return text;
        }
        return text.substring(0, maxLength - 3) + "...";
    }
    
    /**
     * Generate PDF receipt for EFASHE transactions (RRA, TV, and ELECTRICITY)
     * Format similar to invoice with company information, transaction details, and payment summary
     */
    public byte[] generateEfasheReceiptPdf(EfasheTransaction transaction) {
        try (PDDocument document = new PDDocument();
             ByteArrayOutputStream baos = new ByteArrayOutputStream()) {
            
            // Create A4 portrait page
            PDPage page = new PDPage(PDRectangle.A4);
            document.addPage(page);
            PDPageContentStream contentStream = new PDPageContentStream(document, page);
            
            try {
                float pageWidth = PDRectangle.A4.getWidth();
                float pageHeight = PDRectangle.A4.getHeight();
                float margin = 50;
                float currentY = pageHeight - margin;
                
                // Load and draw logo image (Top Right)
                float logoY = pageHeight - margin; // Track logo Y position for stamp placement
                try {
                    ClassPathResource logoResource = new ClassPathResource("images/LOGO.jpeg");
                    if (logoResource.exists()) {
                        InputStream logoInputStream = logoResource.getInputStream();
                        PDImageXObject logoImage = PDImageXObject.createFromByteArray(document, logoInputStream.readAllBytes(), "LOGO");
                        logoInputStream.close();
                        
                        // Logo dimensions - scale to fit (max width 150, maintain aspect ratio)
                        float logoWidth = 150;
                        float logoHeight = (logoImage.getHeight() / logoImage.getWidth()) * logoWidth;
                        
                        // Position logo at top right
                        float logoX = pageWidth - margin - logoWidth;
                        logoY = pageHeight - margin - logoHeight;
                        
                        contentStream.drawImage(logoImage, logoX, logoY, logoWidth, logoHeight);
                        logger.info("Logo image added to PDF receipt - Size: {}x{}, Position: ({}, {})", logoWidth, logoHeight, logoX, logoY);
                        
                        // Load and draw stamp image (Below Logo, Top Right)
                        try {
                            ClassPathResource stampResource = new ClassPathResource("images/stamp.jpeg");
                            if (stampResource.exists()) {
                                InputStream stampInputStream = stampResource.getInputStream();
                                PDImageXObject stampImage = PDImageXObject.createFromByteArray(document, stampInputStream.readAllBytes(), "STAMP");
                                stampInputStream.close();
                                
                                // Stamp dimensions - scale to fit (max width 100, maintain aspect ratio)
                                float stampWidth = 100;
                                float stampHeight = (stampImage.getHeight() / stampImage.getWidth()) * stampWidth;
                                
                                // Position stamp below logo (same X alignment, with small gap)
                                float stampX = logoX + (logoWidth - stampWidth) / 2; // Center stamp under logo
                                float stampY = logoY - stampHeight - 5; // Position below logo with 5pt gap
                                
                                contentStream.drawImage(stampImage, stampX, stampY, stampWidth, stampHeight);
                                logger.info("Stamp image added to PDF receipt - Size: {}x{}, Position: ({}, {})", stampWidth, stampHeight, stampX, stampY);
                            } else {
                                logger.warn("Stamp file not found at images/stamp.jpeg, skipping stamp");
                            }
                        } catch (Exception e) {
                            logger.error("Error loading stamp image: ", e);
                            // Continue without stamp if it fails to load
                        }
                        
                        // Adjust currentY to account for logo height
                        currentY = logoY - 10; // Add some space below logo
                    } else {
                        logger.warn("Logo file not found at images/LOGO.jpeg, using text fallback");
                        // Fallback to text if logo not found
                        contentStream.beginText();
                        contentStream.setFont(PDType1Font.HELVETICA_BOLD, 16);
                        float companyX = pageWidth - margin - 150;
                        contentStream.newLineAtOffset(companyX, currentY);
                        contentStream.showText("BEPAY");
                        contentStream.endText();
                        
                        currentY -= 8;
                        contentStream.beginText();
                        contentStream.setFont(PDType1Font.HELVETICA, 8);
                        contentStream.newLineAtOffset(companyX, currentY);
                        contentStream.showText("only if you want the best");
                        contentStream.endText();
                    }
                } catch (Exception e) {
                    logger.error("Error loading logo image: ", e);
                    // Fallback to text if logo fails to load
                    contentStream.beginText();
                    contentStream.setFont(PDType1Font.HELVETICA_BOLD, 16);
                    float companyX = pageWidth - margin - 150;
                    contentStream.newLineAtOffset(companyX, currentY);
                    contentStream.showText("BEPAY");
                    contentStream.endText();
                    
                    currentY -= 8;
                    contentStream.beginText();
                    contentStream.setFont(PDType1Font.HELVETICA, 8);
                    contentStream.newLineAtOffset(companyX, currentY);
                    contentStream.showText("only if you want the best");
                    contentStream.endText();
                }
                
                // Invoice Header (Top Left)
                currentY = pageHeight - margin;
                contentStream.beginText();
                contentStream.setFont(PDType1Font.HELVETICA_BOLD, 24);
                contentStream.newLineAtOffset(margin, currentY);
                contentStream.showText("Invoice");
                contentStream.endText();
                
                currentY -= 20;
                String invoiceNumber = generateInvoiceNumber(transaction);
                contentStream.beginText();
                contentStream.setFont(PDType1Font.HELVETICA, 11);
                contentStream.newLineAtOffset(margin, currentY);
                contentStream.showText("#" + invoiceNumber);
                contentStream.endText();
                
                currentY -= 40;
                
                // Dates
                DateTimeFormatter dateFormatter = DateTimeFormatter.ofPattern("d MMMM, yyyy");
                String invoiceDate = transaction.getCreatedAt().format(dateFormatter);
                String dueDate = transaction.getCreatedAt().plusDays(1).format(dateFormatter);
                
                contentStream.beginText();
                contentStream.setFont(PDType1Font.HELVETICA, 10);
                contentStream.newLineAtOffset(margin, currentY);
                contentStream.showText("Invoice Date: " + invoiceDate);
                contentStream.endText();
                
                currentY -= 15;
                contentStream.beginText();
                contentStream.setFont(PDType1Font.HELVETICA, 10);
                contentStream.newLineAtOffset(margin, currentY);
                contentStream.showText("Due Date: " + dueDate);
                contentStream.endText();
                
                currentY -= 30;
                
                // Billed To Section
                contentStream.beginText();
                contentStream.setFont(PDType1Font.HELVETICA_BOLD, 12);
                contentStream.newLineAtOffset(margin, currentY);
                contentStream.showText("Billed To");
                contentStream.endText();
                
                currentY -= 18;
                
                // Extract TIN from customer account number for RRA (format: TIN or account number)
                String tin = transaction.getCustomerAccountNumber();
                
                contentStream.beginText();
                contentStream.setFont(PDType1Font.HELVETICA, 10);
                contentStream.newLineAtOffset(margin, currentY);
                contentStream.showText("Biller Number: " + tin);
                contentStream.endText();
                
                currentY -= 14;
                
                // Address
                contentStream.beginText();
                contentStream.setFont(PDType1Font.HELVETICA, 10);
                contentStream.newLineAtOffset(margin, currentY);
                contentStream.showText("Address: Kigali, Rwanda");
                contentStream.endText();
                
                currentY -= 14;
                
                // Phone
                String phone = formatPhoneNumber(transaction.getCustomerPhone());
                contentStream.beginText();
                contentStream.setFont(PDType1Font.HELVETICA, 10);
                contentStream.newLineAtOffset(margin, currentY);
                contentStream.showText("Telephone: " + phone);
                contentStream.endText();
                
                currentY -= 14;
                
                // Email
                contentStream.beginText();
                contentStream.setFont(PDType1Font.HELVETICA, 10);
                contentStream.newLineAtOffset(margin, currentY);
                contentStream.showText("Email: bepayapp@gmail.com");
                contentStream.endText();
                
                currentY -= 14;
                
                // Website
                contentStream.beginText();
                contentStream.setFont(PDType1Font.HELVETICA, 10);
                contentStream.newLineAtOffset(margin, currentY);
                contentStream.showText("Website: www.pochi.info");
                contentStream.endText();
                
                currentY -= 14;
                
                // Invoice No (Internal)
                contentStream.beginText();
                contentStream.setFont(PDType1Font.HELVETICA, 10);
                contentStream.newLineAtOffset(margin, currentY);
                contentStream.showText("Invoice No: BEP-INV-" + transaction.getCreatedAt().format(DateTimeFormatter.ofPattern("yyyy-MM-")) + 
                    String.format("%06d", Math.abs(transaction.getTransactionId().hashCode() % 1000000)));
                contentStream.endText();
                
                currentY -= 14;
                
                // Date for internal invoice
                contentStream.beginText();
                contentStream.setFont(PDType1Font.HELVETICA, 10);
                contentStream.newLineAtOffset(margin, currentY);
                contentStream.showText("Date: " + transaction.getCreatedAt().format(DateTimeFormatter.ofPattern("d MMM yyyy")));
                contentStream.endText();
                
                currentY -= 14;
                
                // Payment Method
                contentStream.beginText();
                contentStream.setFont(PDType1Font.HELVETICA, 10);
                contentStream.newLineAtOffset(margin, currentY);
                contentStream.showText("Payment Method: Mobile Money");
                contentStream.endText();
                
                currentY -= 14;
                
                // Transaction ID
                String transactionId = transaction.getMopayTransactionId() != null 
                    ? transaction.getMopayTransactionId() 
                    : transaction.getTransactionId();
                contentStream.beginText();
                contentStream.setFont(PDType1Font.HELVETICA, 10);
                contentStream.newLineAtOffset(margin, currentY);
                contentStream.showText("Transaction ID: " + transactionId);
                contentStream.endText();
                
                currentY -= 14;
                
                // Customer Name
                if (transaction.getCustomerAccountName() != null && !transaction.getCustomerAccountName().trim().isEmpty()) {
                    contentStream.beginText();
                    contentStream.setFont(PDType1Font.HELVETICA, 10);
                    contentStream.newLineAtOffset(margin, currentY);
                    contentStream.showText("Customer: " + transaction.getCustomerAccountName());
                    contentStream.endText();
                    currentY -= 14;
                }
                
                // Phone (customer phone)
                contentStream.beginText();
                contentStream.setFont(PDType1Font.HELVETICA, 10);
                contentStream.newLineAtOffset(margin, currentY);
                contentStream.showText("Phone: " + phone);
                contentStream.endText();
                
                currentY -= 35;
                
                // Service Details Section
                contentStream.beginText();
                contentStream.setFont(PDType1Font.HELVETICA_BOLD, 12);
                contentStream.newLineAtOffset(margin, currentY);
                contentStream.showText("Service Details");
                contentStream.endText();
                
                currentY -= 20;
                
                // Service Type
                String serviceName = getServiceDisplayName(transaction.getServiceType());
                contentStream.beginText();
                contentStream.setFont(PDType1Font.HELVETICA, 10);
                contentStream.newLineAtOffset(margin, currentY);
                contentStream.showText("Service: " + serviceName);
                contentStream.endText();
                
                currentY -= 14;
                
                // Account/Meter Number Label
                String accountLabel;
                if (transaction.getServiceType() == EfasheServiceType.RRA) {
                    accountLabel = "Biller Number";
                } else if (transaction.getServiceType() == EfasheServiceType.ELECTRICITY) {
                    accountLabel = "Meter Number";
                } else {
                    accountLabel = "Account/Decoder Number";
                }
                contentStream.beginText();
                contentStream.setFont(PDType1Font.HELVETICA, 10);
                contentStream.newLineAtOffset(margin, currentY);
                contentStream.showText(accountLabel + ": " + transaction.getCustomerAccountNumber());
                contentStream.endText();
                
                currentY -= 14;
                
                // For ELECTRICITY, extract and display token and KWH if available
                if (transaction.getServiceType() == EfasheServiceType.ELECTRICITY) {
                    String message = transaction.getMessage();
                    if (message != null && !message.trim().isEmpty()) {
                        // Extract token from message (format: "Token: {token} | KWH: {kwh}")
                        String token = null;
                        String kwh = null;
                        
                        if (message.contains("Token:") || message.contains("token:")) {
                            String[] tokenParts = message.split("(?i)Token:");
                            if (tokenParts.length > 1) {
                                String tokenRaw = tokenParts[1].trim();
                                if (tokenRaw.contains(" | ")) {
                                    token = tokenRaw.split(" \\| ")[0].trim();
                                } else if (tokenRaw.contains("KWH:") || tokenRaw.contains("kwh:")) {
                                    token = tokenRaw.split("(?i)KWH:")[0].trim();
                                } else {
                                    token = tokenRaw.replaceAll("\\|", "").trim();
                                }
                                // Format token with dashes every 4 digits
                                token = formatTokenWithDashes(token);
                            }
                        }
                        
                        if (message.contains("KWH:") || message.contains("kwh:")) {
                            String[] kwhParts = message.split("(?i)KWH:");
                            if (kwhParts.length > 1) {
                                String kwhRaw = kwhParts[1].trim();
                                if (kwhRaw.contains(" | ")) {
                                    kwhRaw = kwhRaw.split(" \\| ")[0].trim();
                                }
                                // Format KWH to 1 decimal place
                                try {
                                    double kwhValue = Double.parseDouble(kwhRaw);
                                    kwh = String.format("%.1f", kwhValue);
                                } catch (NumberFormatException e) {
                                    kwh = kwhRaw; // Use as-is if parsing fails
                                }
                            }
                        }
                        
                        // Display Token if available
                        if (token != null && !token.isEmpty()) {
                            contentStream.beginText();
                            contentStream.setFont(PDType1Font.HELVETICA_BOLD, 10);
                            contentStream.newLineAtOffset(margin, currentY);
                            contentStream.showText("Token: " + token);
                            contentStream.endText();
                            currentY -= 14;
                        }
                        
                        // Display KWH if available
                        if (kwh != null && !kwh.isEmpty()) {
                            contentStream.beginText();
                            contentStream.setFont(PDType1Font.HELVETICA, 10);
                            contentStream.newLineAtOffset(margin, currentY);
                            contentStream.showText("KWH: " + kwh);
                            contentStream.endText();
                            currentY -= 14;
                        }
                    }
                }
                
                // Amount Paid
                String amountStr = formatCurrency(transaction.getAmount() != null ? transaction.getAmount() : BigDecimal.ZERO);
                contentStream.beginText();
                contentStream.setFont(PDType1Font.HELVETICA, 10);
                contentStream.newLineAtOffset(margin, currentY);
                contentStream.showText("Amount Paid: " + amountStr);
                contentStream.endText();
                
                currentY -= 35;
                
                // Payment Summary
                contentStream.beginText();
                contentStream.setFont(PDType1Font.HELVETICA_BOLD, 12);
                contentStream.newLineAtOffset(margin, currentY);
                contentStream.showText("Payment Summary");
                contentStream.endText();
                
                currentY -= 20;
                
                // Total Paid
                contentStream.beginText();
                contentStream.setFont(PDType1Font.HELVETICA_BOLD, 11);
                contentStream.newLineAtOffset(margin, currentY);
                contentStream.showText("TOTAL PAID: " + amountStr);
                contentStream.endText();
                
                currentY -= 14;
                
                // Tax Status
                contentStream.beginText();
                contentStream.setFont(PDType1Font.HELVETICA, 10);
                contentStream.newLineAtOffset(margin, currentY);
                contentStream.showText("TAX STATUS: VAT EXEMPT");
                contentStream.endText();
                
                currentY -= 50;
                
                // Footer
                contentStream.beginText();
                contentStream.setFont(PDType1Font.HELVETICA, 10);
                contentStream.newLineAtOffset(margin, currentY);
                contentStream.showText("Thank you for using BEPAY POCHI Solutions");
                contentStream.endText();
                
                currentY -= 12;
                
                contentStream.beginText();
                contentStream.setFont(PDType1Font.HELVETICA, 9);
                contentStream.newLineAtOffset(margin, currentY);
                contentStream.showText("www.pochi.info | Support: +250 794 230 137 | only WhatsApp: +250 788 319 169");
                contentStream.endText();
                
            } finally {
                if (contentStream != null) {
                    contentStream.close();
                }
            }
            
            document.save(baos);
            return baos.toByteArray();
        } catch (Exception e) {
            logger.error("Error generating EFASHE receipt PDF: ", e);
            throw new RuntimeException("Failed to generate receipt PDF: " + e.getMessage(), e);
        }
    }
    
    private String generateInvoiceNumber(EfasheTransaction transaction) {
        // Generate invoice number from transaction ID hash
        int hash = Math.abs(transaction.getTransactionId().hashCode());
        String datePart = transaction.getCreatedAt().format(DateTimeFormatter.ofPattern("yyyyMMdd"));
        return datePart + "-" + String.format("%06d", hash % 1000000);
    }
    
    private String formatPhoneNumber(String phone) {
        if (phone == null || phone.isEmpty()) {
            return "";
        }
        // Format: +250 788 319 169
        String digits = phone.replaceAll("[^0-9]", "");
        if (digits.length() >= 9) {
            if (digits.length() == 12 && digits.startsWith("250")) {
                return "+" + digits.substring(0, 3) + " " + digits.substring(3, 6) + " " + digits.substring(6, 9) + " " + digits.substring(9);
            } else if (digits.length() == 9) {
                return "+250 " + digits.substring(0, 3) + " " + digits.substring(3, 6) + " " + digits.substring(6);
            }
        }
        return phone;
    }
    
    private String getServiceDisplayName(EfasheServiceType serviceType) {
        if (serviceType == null) {
            return "Payment Service";
        }
        switch (serviceType) {
            case RRA:
                return "RRA Tax Payment";
            case TV:
                return "TV Subscription Payment";
            case ELECTRICITY:
                return "Electricity Token Purchase";
            case AIRTIME:
                return "Airtime Purchase";
            case MTN:
                return "MTN Data/Voice Bundle";
            default:
                return serviceType.toString();
        }
    }
    
    /**
     * Format token number by grouping digits into groups of 4 with dashes
     * Example: "1234567890123456" -> "1234-5678-9012-3456"
     * @param token The raw token string (may contain non-digit characters)
     * @return Formatted token with dashes every 4 digits, or original string if formatting fails
     */
    private String formatTokenWithDashes(String token) {
        if (token == null || token.trim().isEmpty()) {
            return token;
        }
        
        // Remove all non-digit characters to get only digits
        String digitsOnly = token.replaceAll("[^0-9]", "");
        
        if (digitsOnly.isEmpty()) {
            // If no digits found, return original token
            return token;
        }
        
        // Group digits into groups of 4 with dashes
        StringBuilder formatted = new StringBuilder();
        for (int i = 0; i < digitsOnly.length(); i++) {
            if (i > 0 && i % 4 == 0) {
                formatted.append("-");
            }
            formatted.append(digitsOnly.charAt(i));
        }
        
        return formatted.toString();
    }
}
