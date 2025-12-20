package com.pocketmoney.pocketmoney.controller;

import com.pocketmoney.pocketmoney.dto.ApiResponse;
import com.pocketmoney.pocketmoney.dto.CreateReceiverRequest;
import com.pocketmoney.pocketmoney.dto.CreateUserRequest;
import com.pocketmoney.pocketmoney.dto.ReceiverResponse;
import com.pocketmoney.pocketmoney.dto.UserResponse;
import com.pocketmoney.pocketmoney.service.ReceiverService;
import com.pocketmoney.pocketmoney.service.UserService;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/public")
public class PublicController {

    private final UserService userService;
    private final ReceiverService receiverService;

    public PublicController(UserService userService, ReceiverService receiverService) {
        this.userService = userService;
        this.receiverService = receiverService;
    }

    @PostMapping("/users/signup")
    public ResponseEntity<ApiResponse<UserResponse>> signUpUser(@Valid @RequestBody CreateUserRequest request) {
        try {
            UserResponse response = userService.createUser(request);
            return ResponseEntity.status(HttpStatus.CREATED)
                    .body(ApiResponse.success("User signed up successfully", response));
        } catch (RuntimeException e) {
            return ResponseEntity.status(HttpStatus.BAD_REQUEST)
                    .body(ApiResponse.error(e.getMessage()));
        }
    }

    @PostMapping("/receivers/signup")
    public ResponseEntity<ApiResponse<ReceiverResponse>> signUpReceiver(@Valid @RequestBody CreateReceiverRequest request) {
        try {
            ReceiverResponse response = receiverService.createReceiver(request);
            return ResponseEntity.status(HttpStatus.CREATED)
                    .body(ApiResponse.success("Receiver signed up successfully", response));
        } catch (RuntimeException e) {
            return ResponseEntity.status(HttpStatus.BAD_REQUEST)
                    .body(ApiResponse.error(e.getMessage()));
        }
    }
}

