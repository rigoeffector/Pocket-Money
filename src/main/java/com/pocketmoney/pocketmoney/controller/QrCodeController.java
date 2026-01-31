package com.pocketmoney.pocketmoney.controller;

import com.pocketmoney.pocketmoney.dto.ApiResponse;
import com.pocketmoney.pocketmoney.dto.GenerateQrCodeRequest;
import com.pocketmoney.pocketmoney.dto.GenerateQrCodeResponse;
import com.pocketmoney.pocketmoney.service.QrCodeService;
import jakarta.validation.Valid;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.UUID;

@RestController
@RequestMapping("/api/qrcode")
public class QrCodeController {

    private static final Logger logger = LoggerFactory.getLogger(QrCodeController.class);

    private final QrCodeService qrCodeService;

    public QrCodeController(QrCodeService qrCodeService) {
        this.qrCodeService = qrCodeService;
    }

    /**
     * Generate QR code for merchant payment
     * POST /api/qrcode/generate
     * 
     * Request body:
     * {
     *   "receiverId": "uuid" 
     * }
     * 
     * Note: 
     * - The payment category is always "QR Code" - no need to specify it.
     * - For ADMIN users: receiverId is REQUIRED (must specify which merchant to generate QR for)
     * - For RECEIVER users: receiverId is OPTIONAL (defaults to authenticated merchant's main merchant)
     * - QR codes are always generated for the MAIN merchant, even if a submerchant ID is provided
     * 
     * Response:
     * {
     *   "success": true,
     *   "message": "QR code generated successfully",
     *   "data": {
     *     "receiverId": "uuid",
     *     "receiverName": "Company Name",
     *     "paymentCategoryId": "uuid",
     *     "paymentCategoryName": "QR Code",
     *     "qrCodeData": "data:image/png;base64,...",
     *     "qrCodeUrl": "{\"receiverId\":\"...\",\"paymentCategoryId\":\"...\",\"type\":\"merchant_payment\"}"
     *   }
     * }
     */
    @PostMapping("/generate")
    public ResponseEntity<ApiResponse<GenerateQrCodeResponse>> generateQrCode(
            @RequestBody(required = false) GenerateQrCodeRequest request) {
        try {
            UUID receiverId = request != null ? request.getReceiverId() : null;
            GenerateQrCodeResponse response = qrCodeService.generateQrCode(receiverId);
            return ResponseEntity.ok(ApiResponse.success("QR code generated successfully", response));
        } catch (RuntimeException e) {
            logger.error("Error generating QR code: {}", e.getMessage(), e);
            return ResponseEntity.status(HttpStatus.BAD_REQUEST)
                    .body(ApiResponse.error(e.getMessage()));
        }
    }

    /**
     * Get QR code as PNG image
     * GET /api/qrcode/image
     * 
     * Query parameters:
     * - receiverId (optional for RECEIVER, required for ADMIN)
     * 
     * Returns: PNG image of the QR code
     */
    @GetMapping("/image")
    public ResponseEntity<byte[]> getQrCodeImage(
            @RequestParam(required = false) UUID receiverId) {
        try {
            byte[] imageBytes = qrCodeService.generateQrCodeImageBytes(receiverId);
            
            HttpHeaders headers = new HttpHeaders();
            headers.setContentType(MediaType.IMAGE_PNG);
            headers.setContentDispositionFormData("inline", 
                "qr-code_" + LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyyMMdd_HHmmss")) + ".png");
            headers.setContentLength(imageBytes.length);
            
            return ResponseEntity.ok()
                    .headers(headers)
                    .body(imageBytes);
        } catch (RuntimeException e) {
            logger.error("Error generating QR code image: {}", e.getMessage(), e);
            return ResponseEntity.status(HttpStatus.BAD_REQUEST).build();
        }
    }

    /**
     * Get QR code as PDF
     * GET /api/qrcode/pdf
     * 
     * Query parameters:
     * - receiverId (optional for RECEIVER, required for ADMIN)
     * 
     * Returns: PDF document containing the QR code
     */
    @GetMapping("/pdf")
    public ResponseEntity<byte[]> getQrCodePdf(
            @RequestParam(required = false) UUID receiverId) {
        try {
            byte[] pdfBytes = qrCodeService.generateQrCodePdfBytes(receiverId);
            
            HttpHeaders headers = new HttpHeaders();
            headers.setContentType(MediaType.APPLICATION_PDF);
            headers.setContentDispositionFormData("attachment", 
                "qr-code_" + LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyyMMdd_HHmmss")) + ".pdf");
            headers.setContentLength(pdfBytes.length);
            
            return ResponseEntity.ok()
                    .headers(headers)
                    .body(pdfBytes);
        } catch (RuntimeException e) {
            logger.error("Error generating QR code PDF: {}", e.getMessage(), e);
            return ResponseEntity.status(HttpStatus.BAD_REQUEST).build();
        }
    }
}
