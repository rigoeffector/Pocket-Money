package com.pocketmoney.pocketmoney.service;

import com.pocketmoney.pocketmoney.dto.PaymentResponse;
import com.pocketmoney.pocketmoney.dto.ReceiverTransactionsResponse;
import com.pocketmoney.pocketmoney.entity.Receiver;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDPage;
import org.apache.pdfbox.pdmodel.PDPageContentStream;
import org.apache.pdfbox.pdmodel.common.PDRectangle;
import org.apache.pdfbox.pdmodel.font.PDType1Font;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.io.ByteArrayOutputStream;
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
                    // Table configuration for landscape - optimized column widths with merchant name column
                    float[] columnWidths = {100, 120, 100, 75, 65, 70, 90, 120};
                    String[] headers = {"Date/Time", "Customer Names", "Merchant", "Amount", "Bonus", "Status", "Payment Info", "Transaction ID"};
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
                            truncateText(formatDateTime(transaction.getCreatedAt()), 20),
                            truncateText(getCustomerName(transaction), 25),
                            truncateText(getMerchantName(transaction), 25),
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
}
