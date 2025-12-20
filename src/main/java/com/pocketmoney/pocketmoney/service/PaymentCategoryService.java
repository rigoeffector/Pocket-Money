package com.pocketmoney.pocketmoney.service;

import com.pocketmoney.pocketmoney.dto.CreatePaymentCategoryRequest;
import com.pocketmoney.pocketmoney.dto.PaymentCategoryResponse;
import com.pocketmoney.pocketmoney.dto.UpdatePaymentCategoryRequest;
import com.pocketmoney.pocketmoney.entity.PaymentCategory;
import com.pocketmoney.pocketmoney.repository.PaymentCategoryRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.UUID;
import java.util.stream.Collectors;

@Service
@Transactional
public class PaymentCategoryService {

    private final PaymentCategoryRepository paymentCategoryRepository;

    public PaymentCategoryService(PaymentCategoryRepository paymentCategoryRepository) {
        this.paymentCategoryRepository = paymentCategoryRepository;
    }

    public PaymentCategoryResponse createPaymentCategory(CreatePaymentCategoryRequest request) {
        if (paymentCategoryRepository.existsByName(request.getName())) {
            throw new RuntimeException("Payment category with name '" + request.getName() + "' already exists");
        }

        PaymentCategory paymentCategory = new PaymentCategory();
        paymentCategory.setName(request.getName());
        paymentCategory.setDescription(request.getDescription());
        paymentCategory.setIsActive(true);

        PaymentCategory savedPaymentCategory = paymentCategoryRepository.save(paymentCategory);
        return mapToResponse(savedPaymentCategory);
    }

    @Transactional(readOnly = true)
    public PaymentCategoryResponse getPaymentCategoryById(UUID id) {
        PaymentCategory paymentCategory = paymentCategoryRepository.findById(id)
                .orElseThrow(() -> new RuntimeException("Payment category not found with id: " + id));
        return mapToResponse(paymentCategory);
    }

    @Transactional(readOnly = true)
    public List<PaymentCategoryResponse> getAllPaymentCategories() {
        return paymentCategoryRepository.findAll()
                .stream()
                .map(this::mapToResponse)
                .collect(Collectors.toList());
    }

    @Transactional(readOnly = true)
    public List<PaymentCategoryResponse> getActivePaymentCategories() {
        return paymentCategoryRepository.findByIsActiveTrue()
                .stream()
                .map(this::mapToResponse)
                .collect(Collectors.toList());
    }

    public PaymentCategoryResponse updatePaymentCategory(UUID id, UpdatePaymentCategoryRequest request) {
        PaymentCategory paymentCategory = paymentCategoryRepository.findById(id)
                .orElseThrow(() -> new RuntimeException("Payment category not found with id: " + id));

        // Check if name is being changed and if it conflicts with another category
        if (!paymentCategory.getName().equals(request.getName())) {
            if (paymentCategoryRepository.existsByName(request.getName())) {
                throw new RuntimeException("Payment category with name '" + request.getName() + "' already exists");
            }
        }

        paymentCategory.setName(request.getName());
        if (request.getDescription() != null) {
            paymentCategory.setDescription(request.getDescription());
        }
        if (request.getIsActive() != null) {
            paymentCategory.setIsActive(request.getIsActive());
        }

        PaymentCategory updatedPaymentCategory = paymentCategoryRepository.save(paymentCategory);
        return mapToResponse(updatedPaymentCategory);
    }

    public void deletePaymentCategory(UUID id) {
        if (!paymentCategoryRepository.existsById(id)) {
            throw new RuntimeException("Payment category not found with id: " + id);
        }
        paymentCategoryRepository.deleteById(id);
    }

    private PaymentCategoryResponse mapToResponse(PaymentCategory paymentCategory) {
        PaymentCategoryResponse response = new PaymentCategoryResponse();
        response.setId(paymentCategory.getId());
        response.setName(paymentCategory.getName());
        response.setDescription(paymentCategory.getDescription());
        response.setIsActive(paymentCategory.getIsActive());
        response.setCreatedAt(paymentCategory.getCreatedAt());
        response.setUpdatedAt(paymentCategory.getUpdatedAt());
        return response;
    }
}

