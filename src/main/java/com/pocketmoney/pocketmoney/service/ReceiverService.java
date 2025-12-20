package com.pocketmoney.pocketmoney.service;

import com.pocketmoney.pocketmoney.dto.CreateReceiverRequest;
import com.pocketmoney.pocketmoney.dto.ReceiverResponse;
import com.pocketmoney.pocketmoney.dto.ReceiverWalletResponse;
import com.pocketmoney.pocketmoney.dto.UpdateReceiverRequest;
import com.pocketmoney.pocketmoney.entity.Receiver;
import com.pocketmoney.pocketmoney.entity.ReceiverStatus;
import com.pocketmoney.pocketmoney.repository.ReceiverRepository;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.UUID;
import java.util.stream.Collectors;

@Service
@Transactional
public class ReceiverService {

    private final ReceiverRepository receiverRepository;
    private final PasswordEncoder passwordEncoder;

    public ReceiverService(ReceiverRepository receiverRepository, PasswordEncoder passwordEncoder) {
        this.receiverRepository = receiverRepository;
        this.passwordEncoder = passwordEncoder;
    }

    public ReceiverResponse createReceiver(CreateReceiverRequest request) {
        // Check if username already exists
        if (receiverRepository.existsByUsername(request.getUsername())) {
            throw new RuntimeException("Username already exists: " + request.getUsername());
        }

        // Check if phone number already exists
        if (receiverRepository.existsByReceiverPhone(request.getReceiverPhone())) {
            throw new RuntimeException("Receiver phone number already exists: " + request.getReceiverPhone());
        }

        Receiver receiver = new Receiver();
        receiver.setCompanyName(request.getCompanyName());
        receiver.setManagerName(request.getManagerName());
        receiver.setUsername(request.getUsername());
        receiver.setPassword(passwordEncoder.encode(request.getPassword())); // Hash the password
        receiver.setReceiverPhone(request.getReceiverPhone());
        receiver.setAccountNumber(request.getAccountNumber());
        receiver.setStatus(request.getStatus() != null ? request.getStatus() : ReceiverStatus.NOT_ACTIVE);
        receiver.setEmail(request.getEmail());
        receiver.setAddress(request.getAddress());
        receiver.setDescription(request.getDescription());

        Receiver savedReceiver = receiverRepository.save(receiver);
        return mapToResponse(savedReceiver);
    }

    @Transactional(readOnly = true)
    public ReceiverResponse getReceiverById(UUID id) {
        Receiver receiver = receiverRepository.findById(id)
                .orElseThrow(() -> new RuntimeException("Receiver not found with id: " + id));
        return mapToResponse(receiver);
    }

    @Transactional(readOnly = true)
    public ReceiverResponse getReceiverByPhone(String phone) {
        Receiver receiver = receiverRepository.findByReceiverPhone(phone)
                .orElseThrow(() -> new RuntimeException("Receiver not found with phone: " + phone));
        return mapToResponse(receiver);
    }

    @Transactional(readOnly = true)
    public List<ReceiverResponse> getAllReceivers() {
        return receiverRepository.findAll()
                .stream()
                .map(this::mapToResponse)
                .collect(Collectors.toList());
    }

    @Transactional(readOnly = true)
    public List<ReceiverResponse> getActiveReceivers() {
        return receiverRepository.findByStatus(ReceiverStatus.ACTIVE)
                .stream()
                .map(this::mapToResponse)
                .collect(Collectors.toList());
    }

    @Transactional(readOnly = true)
    public List<ReceiverResponse> getReceiversByStatus(ReceiverStatus status) {
        return receiverRepository.findByStatus(status)
                .stream()
                .map(this::mapToResponse)
                .collect(Collectors.toList());
    }

    public ReceiverResponse updateReceiver(UUID id, UpdateReceiverRequest request) {
        Receiver receiver = receiverRepository.findById(id)
                .orElseThrow(() -> new RuntimeException("Receiver not found with id: " + id));

        // Check if username is being changed and if it conflicts
        if (request.getUsername() != null && !receiver.getUsername().equals(request.getUsername())) {
            if (receiverRepository.existsByUsername(request.getUsername())) {
                throw new RuntimeException("Username already exists: " + request.getUsername());
            }
            receiver.setUsername(request.getUsername());
        }

        // Check if phone is being changed and if it conflicts
        if (request.getReceiverPhone() != null && !receiver.getReceiverPhone().equals(request.getReceiverPhone())) {
            if (receiverRepository.existsByReceiverPhone(request.getReceiverPhone())) {
                throw new RuntimeException("Receiver phone number already exists: " + request.getReceiverPhone());
            }
            receiver.setReceiverPhone(request.getReceiverPhone());
        }

        // Update password if provided
        if (request.getPassword() != null && !request.getPassword().isEmpty()) {
            receiver.setPassword(passwordEncoder.encode(request.getPassword()));
        }

        // Update other fields
        if (request.getCompanyName() != null) {
            receiver.setCompanyName(request.getCompanyName());
        }
        if (request.getManagerName() != null) {
            receiver.setManagerName(request.getManagerName());
        }
        if (request.getAccountNumber() != null) {
            receiver.setAccountNumber(request.getAccountNumber());
        }
        if (request.getStatus() != null) {
            receiver.setStatus(request.getStatus());
        }
        if (request.getEmail() != null) {
            receiver.setEmail(request.getEmail());
        }
        if (request.getAddress() != null) {
            receiver.setAddress(request.getAddress());
        }
        if (request.getDescription() != null) {
            receiver.setDescription(request.getDescription());
        }

        Receiver updatedReceiver = receiverRepository.save(receiver);
        return mapToResponse(updatedReceiver);
    }

    public void deleteReceiver(UUID id) {
        if (!receiverRepository.existsById(id)) {
            throw new RuntimeException("Receiver not found with id: " + id);
        }
        receiverRepository.deleteById(id);
    }

    @Transactional(readOnly = true)
    public ReceiverWalletResponse getReceiverWallet(UUID id) {
        Receiver receiver = receiverRepository.findById(id)
                .orElseThrow(() -> new RuntimeException("Receiver not found with id: " + id));

        ReceiverWalletResponse response = new ReceiverWalletResponse();
        response.setReceiverId(receiver.getId());
        response.setCompanyName(receiver.getCompanyName());
        response.setReceiverPhone(receiver.getReceiverPhone());
        response.setWalletBalance(receiver.getWalletBalance());
        response.setTotalReceived(receiver.getTotalReceived());
        response.setLastTransactionDate(receiver.getLastTransactionDate());
        return response;
    }

    private ReceiverResponse mapToResponse(Receiver receiver) {
        ReceiverResponse response = new ReceiverResponse();
        response.setId(receiver.getId());
        response.setCompanyName(receiver.getCompanyName());
        response.setManagerName(receiver.getManagerName());
        response.setUsername(receiver.getUsername());
        response.setReceiverPhone(receiver.getReceiverPhone());
        response.setAccountNumber(receiver.getAccountNumber());
        response.setStatus(receiver.getStatus());
        response.setEmail(receiver.getEmail());
        response.setAddress(receiver.getAddress());
        response.setDescription(receiver.getDescription());
        response.setWalletBalance(receiver.getWalletBalance());
        response.setTotalReceived(receiver.getTotalReceived());
        response.setLastTransactionDate(receiver.getLastTransactionDate());
        response.setCreatedAt(receiver.getCreatedAt());
        response.setUpdatedAt(receiver.getUpdatedAt());
        return response;
    }
}

