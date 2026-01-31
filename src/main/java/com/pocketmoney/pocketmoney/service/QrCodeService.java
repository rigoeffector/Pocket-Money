package com.pocketmoney.pocketmoney.service;

import com.google.zxing.BarcodeFormat;
import com.google.zxing.EncodeHintType;
import com.google.zxing.WriterException;
import com.google.zxing.common.BitMatrix;
import com.google.zxing.qrcode.QRCodeWriter;
import com.google.zxing.qrcode.decoder.ErrorCorrectionLevel;
import com.pocketmoney.pocketmoney.dto.GenerateQrCodeResponse;
import com.pocketmoney.pocketmoney.entity.PaymentCategory;
import com.pocketmoney.pocketmoney.entity.Receiver;
import com.pocketmoney.pocketmoney.repository.PaymentCategoryRepository;
import com.pocketmoney.pocketmoney.repository.ReceiverRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Service;

import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDPage;
import org.apache.pdfbox.pdmodel.PDPageContentStream;
import org.apache.pdfbox.pdmodel.common.PDRectangle;
import org.apache.pdfbox.pdmodel.font.PDType1Font;
import org.apache.pdfbox.pdmodel.graphics.image.PDImageXObject;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.util.Base64;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

@Service
public class QrCodeService {

    private static final Logger logger = LoggerFactory.getLogger(QrCodeService.class);

    private final ReceiverRepository receiverRepository;
    private final PaymentCategoryRepository paymentCategoryRepository;

    @Value("${app.base-url:https://api.pochi.info}")
    private String baseUrl;

    public QrCodeService(ReceiverRepository receiverRepository, PaymentCategoryRepository paymentCategoryRepository) {
        this.receiverRepository = receiverRepository;
        this.paymentCategoryRepository = paymentCategoryRepository;
    }

    /**
     * Generate QR code for merchant payment
     * The QR code encodes a JSON payload with receiverId and paymentCategoryId (always "QR Code" category)
     * When scanned, the mobile app will prompt for phone number and amount, then process payment
     * 
     * IMPORTANT: QR codes are always generated for the MAIN merchant, even if called by a submerchant.
     * This ensures all payments go to the main merchant's account.
     * 
     * For ADMIN users: receiverId is required in the request body.
     * For RECEIVER users: receiverId is optional (defaults to authenticated merchant's main merchant).
     */
    public GenerateQrCodeResponse generateQrCode(UUID receiverId) {
        // Get current authenticated user from security context
        Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
        if (authentication == null || !authentication.isAuthenticated()) {
            throw new RuntimeException("User not authenticated");
        }

        // Check if user is ADMIN
        boolean isAdmin = authentication.getAuthorities().stream()
                .anyMatch(a -> a.getAuthority().equals("ROLE_ADMIN"));

        Receiver mainMerchant;
        
        if (isAdmin) {
            // ADMIN users must provide a receiverId
            if (receiverId == null) {
                throw new RuntimeException("receiverId is required when generating QR code as ADMIN. Please provide the receiverId of the merchant in the request body.");
            }
            
            // Find the receiver and determine its main merchant
            Receiver providedReceiver = receiverRepository.findById(receiverId)
                    .orElseThrow(() -> new RuntimeException("Receiver not found with ID: " + receiverId));
            
            // Determine the main merchant (if submerchant, get parent; otherwise use receiver itself)
            mainMerchant = providedReceiver.getParentReceiver() != null 
                    ? providedReceiver.getParentReceiver() 
                    : providedReceiver;
        } else {
            // RECEIVER users - find merchant by username
            String currentUsername = authentication.getName();
            Receiver merchant = receiverRepository.findByUsername(currentUsername)
                    .orElseThrow(() -> new RuntimeException("Merchant not found with username: " + currentUsername));

            // Determine the main merchant (if submerchant, get parent; otherwise use merchant itself)
            mainMerchant = merchant.getParentReceiver() != null ? merchant.getParentReceiver() : merchant;
            
            // If a receiverId is provided, verify it belongs to the same merchant hierarchy
            if (receiverId != null) {
                Receiver providedReceiver = receiverRepository.findById(receiverId)
                        .orElseThrow(() -> new RuntimeException("Receiver not found with ID: " + receiverId));
                
                // Verify the provided receiver is either the main merchant or one of its submerchants
                Receiver providedMainMerchant = providedReceiver.getParentReceiver() != null 
                        ? providedReceiver.getParentReceiver() 
                        : providedReceiver;
                
                if (!providedMainMerchant.getId().equals(mainMerchant.getId())) {
                    throw new RuntimeException("Cannot generate QR code for a different merchant. QR codes must be generated for the main merchant.");
                }
            }
        }
        
        // Always use main merchant's receiverId for QR code generation
        UUID targetReceiverId = mainMerchant.getId();
        
        // Verify main merchant receiver exists and is active
        Receiver targetReceiver = receiverRepository.findById(targetReceiverId)
                .orElseThrow(() -> new RuntimeException("Main merchant receiver not found with ID: " + targetReceiverId));

        // Always use "QR Code" category
        PaymentCategory paymentCategory = paymentCategoryRepository.findByName("QR Code")
                .orElseThrow(() -> new RuntimeException("QR Code payment category not found. Please ensure the category exists in the database."));

        UUID paymentCategoryId = paymentCategory.getId();
        
        // Create JSON payload for QR code
        // Format: {"receiverId":"uuid","paymentCategoryId":"uuid","type":"merchant_payment"}
        String qrCodeUrl = String.format(
            "{\"receiverId\":\"%s\",\"paymentCategoryId\":\"%s\",\"type\":\"merchant_payment\"}",
            targetReceiverId.toString(),
            paymentCategoryId.toString()
        );

        // Generate QR code image
        String qrCodeBase64 = generateQrCodeImage(qrCodeUrl, 300, 300);

        // Create response
        GenerateQrCodeResponse response = new GenerateQrCodeResponse();
        response.setReceiverId(targetReceiverId);
        response.setReceiverName(targetReceiver.getCompanyName());
        response.setPaymentCategoryId(paymentCategoryId);
        response.setPaymentCategoryName(paymentCategory.getName());
        response.setQrCodeData(qrCodeBase64);
        response.setQrCodeUrl(qrCodeUrl);

        logger.info("Generated QR code for merchant: {} (receiverId: {}, categoryId: {})", 
            targetReceiver.getCompanyName(), targetReceiverId, paymentCategoryId);

        return response;
    }

    /**
     * Generate QR code image from text and return as Base64 encoded string
     */
    private String generateQrCodeImage(String text, int width, int height) {
        try {
            BufferedImage image = generateQrCodeBufferedImage(text, width, height);
            
            // Convert BufferedImage to Base64
            ByteArrayOutputStream baos = new ByteArrayOutputStream();
            ImageIO.write(image, "PNG", baos);
            byte[] imageBytes = baos.toByteArray();
            return "data:image/png;base64," + Base64.getEncoder().encodeToString(imageBytes);

        } catch (IOException e) {
            logger.error("Error generating QR code: {}", e.getMessage(), e);
            throw new RuntimeException("Failed to generate QR code: " + e.getMessage());
        }
    }

    /**
     * Generate QR code as PNG image bytes
     */
    public byte[] generateQrCodeImageBytes(UUID receiverId) {
        GenerateQrCodeResponse response = generateQrCode(receiverId);
        String qrCodeUrl = response.getQrCodeUrl();
        
        try {
            BufferedImage image = generateQrCodeBufferedImage(qrCodeUrl, 500, 500);
            ByteArrayOutputStream baos = new ByteArrayOutputStream();
            ImageIO.write(image, "PNG", baos);
            return baos.toByteArray();
        } catch (IOException e) {
            logger.error("Error generating QR code image bytes: {}", e.getMessage(), e);
            throw new RuntimeException("Failed to generate QR code image: " + e.getMessage());
        }
    }

    /**
     * Generate QR code as PDF bytes
     */
    public byte[] generateQrCodePdfBytes(UUID receiverId) {
        GenerateQrCodeResponse response = generateQrCode(receiverId);
        String qrCodeUrl = response.getQrCodeUrl();
        
        try (PDDocument document = new PDDocument();
             ByteArrayOutputStream baos = new ByteArrayOutputStream()) {
            
            // Create A4 page
            PDPage page = new PDPage(PDRectangle.A4);
            document.addPage(page);
            
            // Generate QR code image
            BufferedImage qrImage = generateQrCodeBufferedImage(qrCodeUrl, 400, 400);
            ByteArrayOutputStream imageBaos = new ByteArrayOutputStream();
            ImageIO.write(qrImage, "PNG", imageBaos);
            byte[] imageBytes = imageBaos.toByteArray();
            
            // Create PDImageXObject from byte array
            PDImageXObject pdImage = PDImageXObject.createFromByteArray(document, imageBytes, "qr-code");
            
            PDPageContentStream contentStream = new PDPageContentStream(document, page);
            
            try {
                float pageWidth = page.getMediaBox().getWidth();
                float pageHeight = page.getMediaBox().getHeight();
                float margin = 50;
                
                // Calculate position to center the QR code
                float imageWidth = 400;
                float imageHeight = 400;
                float xPosition = (pageWidth - imageWidth) / 2;
                float yPosition = (pageHeight - imageHeight) / 2 + 100; // Slightly higher to leave room for text
                
                // Add title
                contentStream.beginText();
                contentStream.setFont(PDType1Font.HELVETICA_BOLD, 20);
                float titleWidth = PDType1Font.HELVETICA_BOLD.getStringWidth("QR Code Payment") / 1000f * 20;
                contentStream.newLineAtOffset((pageWidth - titleWidth) / 2, pageHeight - margin - 30);
                contentStream.showText("QR Code Payment");
                contentStream.endText();
                
                // Add merchant name
                contentStream.beginText();
                contentStream.setFont(PDType1Font.HELVETICA, 14);
                String merchantName = response.getReceiverName();
                float merchantNameWidth = PDType1Font.HELVETICA.getStringWidth(merchantName) / 1000f * 14;
                contentStream.newLineAtOffset((pageWidth - merchantNameWidth) / 2, pageHeight - margin - 50);
                contentStream.showText(merchantName);
                contentStream.endText();
                
                // Draw QR code image
                contentStream.drawImage(pdImage, xPosition, yPosition - imageHeight, imageWidth, imageHeight);
                
                // Add instructions
                contentStream.beginText();
                contentStream.setFont(PDType1Font.HELVETICA, 12);
                String instruction = "Scan this QR code to make a payment";
                float instructionWidth = PDType1Font.HELVETICA.getStringWidth(instruction) / 1000f * 12;
                contentStream.newLineAtOffset((pageWidth - instructionWidth) / 2, yPosition - imageHeight - 30);
                contentStream.showText(instruction);
                contentStream.endText();
                
                // Add generated date
                contentStream.beginText();
                contentStream.setFont(PDType1Font.HELVETICA, 10);
                String dateText = "Generated: " + java.time.LocalDateTime.now().format(
                    java.time.format.DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss"));
                float dateWidth = PDType1Font.HELVETICA.getStringWidth(dateText) / 1000f * 10;
                contentStream.newLineAtOffset((pageWidth - dateWidth) / 2, margin);
                contentStream.showText(dateText);
                contentStream.endText();
                
            } finally {
                contentStream.close();
            }
            
            document.save(baos);
            return baos.toByteArray();
            
        } catch (IOException e) {
            logger.error("Error generating QR code PDF: {}", e.getMessage(), e);
            throw new RuntimeException("Failed to generate QR code PDF: " + e.getMessage());
        }
    }

    /**
     * Generate QR code as BufferedImage
     */
    private BufferedImage generateQrCodeBufferedImage(String text, int width, int height) {
        try {
            Map<EncodeHintType, Object> hints = new HashMap<>();
            hints.put(EncodeHintType.ERROR_CORRECTION, ErrorCorrectionLevel.H);
            hints.put(EncodeHintType.CHARACTER_SET, "UTF-8");
            hints.put(EncodeHintType.MARGIN, 1);

            QRCodeWriter qrCodeWriter = new QRCodeWriter();
            BitMatrix bitMatrix = qrCodeWriter.encode(text, BarcodeFormat.QR_CODE, width, height, hints);

            // Convert BitMatrix to BufferedImage
            BufferedImage image = new BufferedImage(width, height, BufferedImage.TYPE_INT_RGB);
            for (int x = 0; x < width; x++) {
                for (int y = 0; y < height; y++) {
                    image.setRGB(x, y, bitMatrix.get(x, y) ? 0xFF000000 : 0xFFFFFFFF);
                }
            }
            
            return image;

        } catch (WriterException e) {
            logger.error("Error generating QR code image: {}", e.getMessage(), e);
            throw new RuntimeException("Failed to generate QR code: " + e.getMessage());
        }
    }
}
