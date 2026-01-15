# EFASHE API Request Bodies

## Table of Contents
1. [Validate Endpoints](#validate-endpoints)
2. [Execute Endpoints](#execute-endpoints)
3. [Check Status Endpoints](#check-status-endpoints)

---

## Validate Endpoints

### 1. Generic Validate (All Service Types)
**Endpoint:** `POST /api/efashe/{serviceType}/validate`

**Path Parameters:**
- `serviceType`: `AIRTIME`, `RRA`, `TV`, or `MTN`

**Request Body:**
```json
{
  "customerAccountNumber": "0781234567"
}
```

**Examples for each service type:**

#### AIRTIME:
```bash
POST /api/efashe/AIRTIME/validate
Content-Type: application/json

{
  "customerAccountNumber": "0781234567"
}
```

#### RRA:
```bash
POST /api/efashe/RRA/validate
Content-Type: application/json

{
  "customerAccountNumber": "TIN123456789"
}
```

#### TV:
```bash
POST /api/efashe/TV/validate
Content-Type: application/json

{
  "customerAccountNumber": "DEC123456789"
}
```

#### MTN:
```bash
POST /api/efashe/MTN/validate
Content-Type: application/json

{
  "customerAccountNumber": "0781234567"
}
```

---

### 2. Airtime-Specific Validate (Backward Compatibility)
**Endpoint:** `POST /api/efashe/airtime/validate`

**Request Body:**
```json
{
  "phoneNumber": "0781234567"
}
```

**Full Example:**
```bash
POST /api/efashe/airtime/validate
Content-Type: application/json

{
  "phoneNumber": "0781234567"
}
```

---

## Execute Endpoints

### 1. Generic Execute (All Service Types)
**Endpoint:** `POST /api/efashe/{serviceType}/execute`

**Path Parameters:**
- `serviceType`: `AIRTIME`, `RRA`, `TV`, or `MTN`

**Request Body:**
```json
{
  "customerAccountNumber": "0781234567",
  "amount": 1000,
  "customerPhoneNumber": "0781234567",
  "trxId": "optional-trx-id-from-validate",
  "deliveryMethodId": "direct_topup",
  "deliverTo": "optional-delivery-address",
  "callBack": "optional-callback-url"
}
```

**Field Descriptions:**
- `customerAccountNumber` (required): Phone number for airtime, meter number for electricity, decoder number for TV, etc.
- `amount` (required): Transaction amount in RWF (Integer)
- `customerPhoneNumber` (required): Customer's phone number for MoPay payment collection (must be 12 digits: 250XXXXXXXXX)
- `trxId` (optional): Transaction ID from validate endpoint. If not provided, system will automatically call validate first.
- `deliveryMethodId` (optional): `"print"`, `"email"`, `"sms"`, or `"direct_topup"` (defaults to `"direct_topup"`)
- `deliverTo` (optional): Required if `deliveryMethodId` is `"email"` or `"sms"`. Ignored for `"direct_topup"` or `"print"`.
- `callBack` (optional): Callback URL for async processing (usually not needed)

**Examples for each service type:**

#### AIRTIME:
```bash
POST /api/efashe/AIRTIME/execute
Content-Type: application/json

{
  "customerAccountNumber": "0781234567",
  "amount": 500,
  "customerPhoneNumber": "250781234567",
  "deliveryMethodId": "direct_topup"
}
```

#### RRA (Tax Payment):
```bash
POST /api/efashe/RRA/execute
Content-Type: application/json

{
  "customerAccountNumber": "TIN123456789",
  "amount": 10000,
  "customerPhoneNumber": "250781234567",
  "deliveryMethodId": "email",
  "deliverTo": "customer@example.com"
}
```

#### TV Subscription:
```bash
POST /api/efashe/TV/execute
Content-Type: application/json

{
  "customerAccountNumber": "DEC123456789",
  "amount": 2000,
  "customerPhoneNumber": "250781234567",
  "deliveryMethodId": "sms",
  "deliverTo": "250781234567"
}
```

#### MTN Service:
```bash
POST /api/efashe/MTN/execute
Content-Type: application/json

{
  "customerAccountNumber": "0781234567",
  "amount": 1000,
  "customerPhoneNumber": "250781234567",
  "trxId": "82bb9062-ba34-41e7-9f47-49ce38821019",
  "deliveryMethodId": "direct_topup"
}
```

**Complete Example with All Optional Fields:**
```bash
POST /api/efashe/AIRTIME/execute
Content-Type: application/json

{
  "customerAccountNumber": "0781234567",
  "amount": 500,
  "customerPhoneNumber": "250781234567",
  "trxId": "82bb9062-ba34-41e7-9f47-49ce38821019",
  "deliveryMethodId": "sms",
  "deliverTo": "250781234567",
  "callBack": "https://yourapp.com/callback"
}
```

**Response (Success):**
```json
{
  "success": true,
  "message": "MoPay payment initiated successfully",
  "data": {
    "mopayTransactionId": "CMN1766597686",
    "efasheTransactionId": "EFASHE1737890123456ABC123",
    "status": "PENDING",
    "message": "MoPay payment initiated. Please check transaction status using /api/efashe/airtime/check-status/CMN1766597686",
    "transactionAmount": 500,
    "customerCashback": 10.00
  }
}
```

---

### 2. Airtime-Specific Execute (Backward Compatibility)
**Endpoint:** `POST /api/efashe/airtime/execute`

**Request Body:**
```json
{
  "customerAccountNumber": "0781234567",
  "amount": 500,
  "customerPhoneNumber": "250781234567",
  "trxId": "optional-trx-id",
  "deliveryMethodId": "direct_topup",
  "deliverTo": "optional",
  "callBack": "optional"
}
```

**Full Example:**
```bash
POST /api/efashe/airtime/execute
Content-Type: application/json

{
  "customerAccountNumber": "0781234567",
  "amount": 500,
  "customerPhoneNumber": "250781234567"
}
```

---

## Check Status Endpoints

### 1. Generic Check Status (All Service Types)
**Endpoint:** `GET /api/efashe/{serviceType}/check-status/{mopayTransactionId}`

**Path Parameters:**
- `serviceType`: `AIRTIME`, `RRA`, `TV`, or `MTN`
- `mopayTransactionId`: MoPay transaction ID from execute response

**No Request Body** - All parameters are in the URL path.

**Examples:**

#### AIRTIME:
```bash
GET /api/efashe/AIRTIME/check-status/CMN1766597686
```

#### RRA:
```bash
GET /api/efashe/RRA/check-status/CMN1766597686
```

#### TV:
```bash
GET /api/efashe/TV/check-status/CMN1766597686
```

#### MTN:
```bash
GET /api/efashe/MTN/check-status/CMN1766597686
```

**Response Scenarios:**

**1. Payment Still PENDING:**
```json
{
  "success": true,
  "message": "Transaction status checked",
  "data": {
    "mopayTransactionId": "CMN1766597686",
    "status": "PENDING",
    "message": "Payment is still pending. Please check again later.",
    "statusCode": 200,
    "success": false
  }
}
```

**2. Payment SUCCESS (EFASHE executed automatically):**
```json
{
  "success": true,
  "message": "Transaction status checked",
  "data": {
    "success": true,
    "data": {
      "trxId": "82bb9062-ba34-41e7-9f47-49ce38821019",
      "status": "SUCCESS"
    },
    "mopayTransactionId": "CMN1766597686",
    "paymentStatus": "SUCCESS",
    "customerCashback": 10.00
  }
}
```

**3. Payment FAILED:**
```json
{
  "success": false,
  "message": "MoPay payment failed or was cancelled. Status: FAILED. EFASHE airtime purchase will NOT be executed."
}
```

---

### 2. Airtime-Specific Check Status (Backward Compatibility)
**Endpoint:** `GET /api/efashe/airtime/check-status/{mopayTransactionId}`

**Path Parameters:**
- `mopayTransactionId`: MoPay transaction ID from execute response

**Example:**
```bash
GET /api/efashe/airtime/check-status/CMN1766597686
```

---

## Complete Flow Example

### Step 1: Validate
```bash
POST /api/efashe/AIRTIME/validate
Content-Type: application/json

{
  "customerAccountNumber": "0781234567"
}
```

**Response:**
```json
{
  "success": true,
  "message": "Airtime validation successful",
  "data": {
    "data": {
      "trxId": "82bb9062-ba34-41e7-9f47-49ce38821019",
      "pdtName": "MTN Rwanda Airtime",
      ...
    }
  }
}
```

### Step 2: Execute (Initiate Payment)
```bash
POST /api/efashe/AIRTIME/execute
Content-Type: application/json

{
  "customerAccountNumber": "0781234567",
  "amount": 500,
  "customerPhoneNumber": "250781234567",
  "trxId": "82bb9062-ba34-41e7-9f47-49ce38821019"
}
```

**Response:**
```json
{
  "success": true,
  "message": "MoPay payment initiated successfully",
  "data": {
    "mopayTransactionId": "CMN1766597686",
    "efasheTransactionId": "EFASHE1737890123456ABC123",
    "status": "PENDING"
  }
}
```

### Step 3: Check Status (Poll until SUCCESS)
```bash
GET /api/efashe/AIRTIME/check-status/CMN1766597686
```

**Keep polling until you get SUCCESS or FAILED status.**
**When SUCCESS, EFASHE execution and WhatsApp notification happen automatically.**

---

## Notes

1. **Phone Number Format**: Always use 12-digit format with country code (250XXXXXXXXX) for `customerPhoneNumber`.

2. **Transaction IDs**: 
   - EFASHE transaction IDs start with "EFASHE"
   - MoPay transaction IDs start with "CMN"

3. **Delivery Methods**:
   - `"direct_topup"`: Direct top-up (default, no deliverTo needed)
   - `"print"`: Print receipt (no deliverTo needed)
   - `"email"`: Email receipt (deliverTo must be email address)
   - `"sms"`: SMS receipt (deliverTo must be phone number)

4. **Commission Distribution**: The system automatically calculates and distributes:
   - Customer cashback (sent to customer)
   - Besoft share (sent to configured cashback phone)
   - Full amount (sent to configured full amount phone)

5. **Cashback Settings**: Ensure EFASHE settings are configured for each service type via `/api/efashe/settings/{serviceType}`.

