# EFASHE Complete Flow Guide

## Overview
This guide shows the complete flow for purchasing EFASHE services (Airtime, RRA, TV, MTN) using MoPay payment integration.

---

## Complete Flow (Step by Step)

### **Step 1: Validate Customer Account** ✅

**Purpose:** Validate the customer account and get a transaction ID (`trxId`) for the purchase.

#### Option A: Airtime-Specific Endpoint
```bash
POST /api/efashe/airtime/validate
Content-Type: application/json

{
  "phoneNumber": "0781234567"
}
```

#### Option B: Generic Endpoint (for all service types)
```bash
POST /api/efashe/{serviceType}/validate
Content-Type: application/json

{
  "customerAccountNumber": "0781234567"
}
```

**Example for AIRTIME:**
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
      "pdtStatusId": "active",
      "verticalId": "airtime",
      "customerAccountName": "",
      "svcProviderName": "MTN Rwanda",
      "vendUnitId": "flexible",
      "vendMin": 100.0,
      "vendMax": 250000.0,
      "trxResult": "direct_topup",
      "availTrxBalance": 2589411.0,
      "deliveryMethods": [
        {
          "id": "direct_topup",
          "name": "Direct Topup"
        }
      ]
    }
  }
}
```

**Important:** Save the `trxId` from the response! You'll need it in Step 2 (or it will be fetched automatically).

---

### **Step 2: Initiate MoPay Payment** 💳

**Purpose:** Start the payment process. This ONLY initiates payment - it does NOT execute EFASHE automatically.

#### Option A: Airtime-Specific Endpoint
```bash
POST /api/efashe/airtime/execute
Content-Type: application/json

{
  "customerAccountNumber": "0781234567",
  "amount": 500,
  "customerPhoneNumber": "250781234567",
  "trxId": "82bb9062-ba34-41e7-9f47-49ce38821019",
  "deliveryMethodId": "direct_topup"
}
```

#### Option B: Generic Endpoint (for all service types)
```bash
POST /api/efashe/{serviceType}/execute
Content-Type: application/json

{
  "customerAccountNumber": "0781234567",
  "amount": 500,
  "customerPhoneNumber": "250781234567",
  "trxId": "82bb9062-ba34-41e7-9f47-49ce38821019",
  "deliveryMethodId": "direct_topup"
}
```

**Example for AIRTIME:**
```bash
POST /api/efashe/AIRTIME/execute
Content-Type: application/json

{
  "customerAccountNumber": "0781234567",
  "amount": 500,
  "customerPhoneNumber": "250781234567",
  "trxId": "82bb9062-ba34-41e7-9f47-49ce38821019",
  "deliveryMethodId": "direct_topup"
}
```

**Request Body Fields:**
- `customerAccountNumber` (required): Phone number, meter number, etc.
- `amount` (required): Transaction amount in RWF (Integer)
- `customerPhoneNumber` (required): Customer's phone for MoPay payment (12 digits: 250XXXXXXXXX)
- `trxId` (optional): From Step 1. If not provided, system calls validate automatically.
- `deliveryMethodId` (optional): `"direct_topup"`, `"print"`, `"email"`, or `"sms"` (defaults to `"direct_topup"`)
- `deliverTo` (optional): Required if deliveryMethodId is `"email"` or `"sms"`
- `callBack` (optional): Callback URL (usually not needed)

**Response:**
```json
{
  "success": true,
  "message": "MoPay payment initiated successfully",
  "data": {
    "mopayTransactionId": "CMN1766597686",
    "efasheTransactionId": "EFASHE1737890123456ABC123",
    "status": "PENDING",
    "message": "MoPay payment initiated. Please check transaction status using /api/efashe/AIRTIME/check-status/CMN1766597686",
    "transactionAmount": 500,
    "customerCashback": 10.00
  }
}
```

**Important:** Save the `mopayTransactionId`! You'll need it in Step 3.

**What happens at this step:**
1. System validates service (if `trxId` not provided)
2. Calculates commission distribution (cashback, Besoft share)
3. Initiates MoPay payment with commission transfers
4. Stores request for later execution
5. Returns transaction IDs

**Note:** Customer needs to approve the MoPay payment on their phone at this point.

---

### **Step 3: Check Payment Status** 🔄

**Purpose:** Check if MoPay payment was successful. When status is SUCCESS, EFASHE execution and WhatsApp notification happen automatically.

#### Option A: Airtime-Specific Endpoint
```bash
GET /api/efashe/airtime/check-status/CMN1766597686
```

#### Option B: Generic Endpoint (for all service types)
```bash
GET /api/efashe/{serviceType}/check-status/{mopayTransactionId}
```

**Example for AIRTIME:**
```bash
GET /api/efashe/AIRTIME/check-status/CMN1766597686
```

**No request body required** - all parameters are in the URL path.

**Response Scenarios:**

#### **Scenario A: Payment Still PENDING** ⏳
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
**Action:** Keep polling this endpoint until you get SUCCESS or FAILED.

#### **Scenario B: Payment SUCCESS** ✅
```json
{
  "success": true,
  "message": "Transaction status checked",
  "data": {
    "success": true,
    "data": {
      "trxId": "82bb9062-ba34-41e7-9f47-49ce38821019",
      "status": "SUCCESS",
      "message": "Transaction completed successfully"
    },
    "mopayTransactionId": "CMN1766597686",
    "paymentStatus": "SUCCESS",
    "customerCashback": 10.00
  }
}
```
**What happened automatically:**
1. ✅ EFASHE service was executed (`/vend/execute`)
2. ✅ WhatsApp notification was sent to customer
3. ✅ Transaction completed successfully

#### **Scenario C: Payment FAILED** ❌
```json
{
  "success": false,
  "message": "MoPay payment failed or was cancelled. Status: FAILED. EFASHE airtime purchase will NOT be executed."
}
```
**Action:** EFASHE execution did NOT happen. Customer payment was not successful.

---

## Complete Flow Examples

### Example 1: Airtime Purchase (Simple Flow)

```bash
# Step 1: Validate
POST /api/efashe/airtime/validate
{
  "phoneNumber": "0781234567"
}

# Response: { "data": { "data": { "trxId": "abc123..." } } }

# Step 2: Initiate Payment
POST /api/efashe/airtime/execute
{
  "customerAccountNumber": "0781234567",
  "amount": 500,
  "customerPhoneNumber": "250781234567",
  "trxId": "abc123..."
}

# Response: { "data": { "mopayTransactionId": "CMN123456" } }

# Step 3: Check Status (poll until SUCCESS)
GET /api/efashe/airtime/check-status/CMN123456

# Keep calling Step 3 until SUCCESS or FAILED
# When SUCCESS → EFASHE executed automatically + WhatsApp sent
```

### Example 2: Airtime Purchase (Without trxId - Auto-Validate)

```bash
# Step 1: Skip validation (trxId will be fetched automatically)

# Step 2: Initiate Payment (trxId not provided)
POST /api/efashe/AIRTIME/execute
{
  "customerAccountNumber": "0781234567",
  "amount": 500,
  "customerPhoneNumber": "250781234567"
  // trxId not provided - system will call validate automatically
}

# Response: { "data": { "mopayTransactionId": "CMN123456" } }

# Step 3: Check Status
GET /api/efashe/AIRTIME/check-status/CMN123456

# Keep polling until SUCCESS
```

### Example 3: RRA Tax Payment

```bash
# Step 1: Validate
POST /api/efashe/RRA/validate
{
  "customerAccountNumber": "TIN123456789"
}

# Step 2: Initiate Payment
POST /api/efashe/RRA/execute
{
  "customerAccountNumber": "TIN123456789",
  "amount": 10000,
  "customerPhoneNumber": "250781234567",
  "trxId": "rra-trx-id-123",
  "deliveryMethodId": "email",
  "deliverTo": "customer@example.com"
}

# Step 3: Check Status
GET /api/efashe/RRA/check-status/CMN789012
```

---

## Important Notes

### 🔑 Key Points:

1. **Step 1 (Validate) is OPTIONAL** if you provide `trxId` in Step 2
   - If you don't provide `trxId`, the system will call validate automatically

2. **Step 2 ONLY initiates payment** - it does NOT execute EFASHE
   - Customer must approve payment on their phone
   - Save the `mopayTransactionId` for Step 3

3. **Step 3 must be polled** until status is SUCCESS or FAILED
   - Keep calling the check-status endpoint every few seconds
   - When SUCCESS → EFASHE execution and WhatsApp notification happen automatically
   - When FAILED → EFASHE execution does NOT happen

4. **Commission Distribution is Automatic:**
   - Customer cashback → sent to customer phone
   - Besoft share → sent to configured cashback phone
   - Full amount → sent to configured full amount phone
   - Percentages are configured per service type via `/api/efashe/settings`

5. **Phone Number Format:**
   - Use 12 digits with country code: `250781234567`
   - System will normalize automatically if needed

6. **Transaction IDs:**
   - EFASHE transaction IDs start with `"EFASHE"`
   - MoPay transaction IDs start with `"CMN"`

---

## Quick Reference

| Step | Endpoint | Purpose | Returns |
|------|----------|---------|---------|
| 1 | `POST /api/efashe/{serviceType}/validate` | Validate account | `trxId` |
| 2 | `POST /api/efashe/{serviceType}/execute` | Initiate payment | `mopayTransactionId` |
| 3 | `GET /api/efashe/{serviceType}/check-status/{id}` | Check status | Status (PENDING/SUCCESS/FAILED) |

---

## Troubleshooting

### Error: "customerAccountNumber is required"
- **Fix:** Make sure you're using the correct field name in the request body
- For airtime validate: use `"phoneNumber"`
- For execute: use `"customerAccountNumber"`

### Error: "Payment confirmation timeout"
- **Fix:** Customer needs to approve payment on their phone. Keep checking status until they approve.

### Error: "MoPay payment failed"
- **Fix:** Payment was cancelled or insufficient funds. EFASHE execution will NOT happen.

### Error: "customerAccountNumber: none is not an allowed value"
- **Fix:** Ensure the field is not null, empty, or "none". Check your request body.

