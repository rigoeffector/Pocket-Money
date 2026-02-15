# Receiver Country and Country Code Endpoints

This document outlines all endpoints that accept and return `country` and `countryCode` fields for receivers.

## Endpoints That Accept Country and CountryCode

### 1. Create Receiver
- **Endpoint**: `POST /api/receivers`
- **Request Body**: `CreateReceiverRequest`
- **Fields**: 
  - `country` (String, optional)
  - `countryCode` (String, optional)
- **Example Request**:
```json
{
  "companyName": "Example Company",
  "managerName": "John Doe",
  "username": "johndoe",
  "password": "password123",
  "receiverPhone": "250788123456",
  "status": "ACTIVE",
  "email": "john@example.com",
  "address": "123 Main St",
  "country": "Rwanda",
  "countryCode": "RW",
  "description": "Example receiver"
}
```

### 2. Update Receiver
- **Endpoint**: `PUT /api/receivers/{id}`
- **Request Body**: `UpdateReceiverRequest`
- **Fields**: 
  - `country` (String, optional)
  - `countryCode` (String, optional)
- **Example Request**:
```json
{
  "companyName": "Updated Company",
  "country": "Uganda",
  "countryCode": "UG"
}
```

### 3. Create Submerchant
- **Endpoint**: `POST /api/receivers/{id}/submerchants`
- **Request Body**: `CreateReceiverRequest` (same as Create Receiver)
- **Fields**: 
  - `country` (String, optional)
  - `countryCode` (String, optional)

## Endpoints That Return Country and CountryCode

All endpoints that return receiver information now include `country` and `countryCode` in the response:

### 1. Get Receiver by ID
- **Endpoint**: `GET /api/receivers/{id}`
- **Response**: `ReceiverResponse`
- **Fields**: 
  - `country` (String, nullable)
  - `countryCode` (String, nullable)

### 2. Get Receiver by Phone
- **Endpoint**: `GET /api/receivers/phone/{phone}`
- **Response**: `ReceiverResponse`
- **Fields**: 
  - `country` (String, nullable)
  - `countryCode` (String, nullable)

### 3. Get All Receivers
- **Endpoint**: `GET /api/receivers`
- **Response**: `List<ReceiverResponse>`
- **Fields**: Each receiver includes `country` and `countryCode`

### 4. Get Active Receivers
- **Endpoint**: `GET /api/receivers/active`
- **Response**: `List<ReceiverResponse>`
- **Fields**: Each receiver includes `country` and `countryCode`

### 5. Get Receivers by Status
- **Endpoint**: `GET /api/receivers/status/{status}`
- **Response**: `List<ReceiverResponse>`
- **Fields**: Each receiver includes `country` and `countryCode`

### 6. Update Receiver
- **Endpoint**: `PUT /api/receivers/{id}`
- **Response**: `ReceiverResponse`
- **Fields**: 
  - `country` (String, nullable)
  - `countryCode` (String, nullable)

### 7. Suspend Receiver
- **Endpoint**: `PUT /api/receivers/{id}/suspend`
- **Response**: `ReceiverResponse`
- **Fields**: 
  - `country` (String, nullable)
  - `countryCode` (String, nullable)

### 8. Activate Receiver
- **Endpoint**: `PUT /api/receivers/{id}/activate`
- **Response**: `ReceiverResponse`
- **Fields**: 
  - `country` (String, nullable)
  - `countryCode` (String, nullable)

### 9. Get Submerchants
- **Endpoint**: `GET /api/receivers/{id}/submerchants`
- **Response**: `List<ReceiverResponse>`
- **Fields**: Each receiver includes `country` and `countryCode`

### 10. Get All Main Merchants
- **Endpoint**: `GET /api/receivers/main-merchants`
- **Response**: `List<ReceiverResponse>`
- **Fields**: Each receiver includes `country` and `countryCode`

### 11. Receiver Login
- **Endpoint**: `POST /api/public/receivers/login`
- **Response**: `ReceiverLoginResponse`
- **Fields**: 
  - `country` (String, nullable)
  - `countryCode` (String, nullable)

### 12. Get Receiver Dashboard
- **Endpoint**: `GET /api/receivers/{id}/dashboard`
- **Response**: `ReceiverDashboardResponse`
- **Fields**: 
  - `country` (String, nullable)
  - `countryCode` (String, nullable)

## Response DTOs

### ReceiverResponse
All fields from the receiver entity, including:
- `country` (String, nullable)
- `countryCode` (String, nullable)

### ReceiverLoginResponse
Includes receiver information after login:
- `country` (String, nullable)
- `countryCode` (String, nullable)

### ReceiverDashboardResponse
Includes receiver dashboard information:
- `country` (String, nullable)
- `countryCode` (String, nullable)

## Database Migration

To add these columns to your database, run:
```bash
psql -U postgres -d pocketmoney_db -f add_receiver_country_columns.sql
```

## Notes

- Both `country` and `countryCode` are **optional** fields (nullable)
- Existing receivers will have `null` values for these fields until updated
- The fields can be set during creation or updated later
- Country code should follow ISO 3166-1 alpha-2 format (e.g., "RW", "UG", "KE")
