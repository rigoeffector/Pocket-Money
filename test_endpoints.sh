#!/bin/bash

# Pocket Money API Endpoint Testing Script
# Usage: ./test_endpoints.sh [server_url]
# Example: ./test_endpoints.sh http://164.92.89.74:8383

SERVER_URL="${1:-http://164.92.89.74:8383}"

echo "=========================================="
echo "Testing Pocket Money API Endpoints"
echo "Server: $SERVER_URL"
echo "=========================================="
echo ""

# Colors
GREEN='\033[0;32m'
RED='\033[0;31m'
YELLOW='\033[1;33m'
BLUE='\033[0;34m'
NC='\033[0m' # No Color

# Test function
test_endpoint() {
    local method=$1
    local endpoint=$2
    local description=$3
    local data=$4
    local token=$5
    
    echo -e "${BLUE}Testing:${NC} $description"
    echo "  ${YELLOW}$method${NC} $endpoint"
    
    if [ -n "$token" ]; then
        if [ "$method" = "GET" ]; then
            response=$(curl -s -w "\n%{http_code}" -X GET "$SERVER_URL$endpoint" \
                -H "Authorization: $token" \
                -H "Content-Type: application/json" 2>&1)
        elif [ "$method" = "POST" ]; then
            response=$(curl -s -w "\n%{http_code}" -X POST "$SERVER_URL$endpoint" \
                -H "Authorization: $token" \
                -H "Content-Type: application/json" \
                -d "$data" 2>&1)
        elif [ "$method" = "PUT" ]; then
            response=$(curl -s -w "\n%{http_code}" -X PUT "$SERVER_URL$endpoint" \
                -H "Authorization: $token" \
                -H "Content-Type: application/json" \
                -d "$data" 2>&1)
        fi
    else
        if [ "$method" = "GET" ]; then
            response=$(curl -s -w "\n%{http_code}" -X GET "$SERVER_URL$endpoint" \
                -H "Content-Type: application/json" 2>&1)
        elif [ "$method" = "POST" ]; then
            response=$(curl -s -w "\n%{http_code}" -X POST "$SERVER_URL$endpoint" \
                -H "Content-Type: application/json" \
                -d "$data" 2>&1)
        fi
    fi
    
    http_code=$(echo "$response" | tail -n1)
    body=$(echo "$response" | sed '$d')
    
    if [ "$http_code" -ge 200 ] && [ "$http_code" -lt 300 ]; then
        echo -e "  ${GREEN}✓ Success${NC} (HTTP $http_code)"
        echo "  Response: $(echo "$body" | head -c 200)..."
    elif [ "$http_code" -eq 401 ] || [ "$http_code" -eq 403 ]; then
        echo -e "  ${YELLOW}⚠ Auth Required${NC} (HTTP $http_code) - This is expected for protected endpoints"
    else
        echo -e "  ${RED}✗ Failed${NC} (HTTP $http_code)"
        echo "  Response: $(echo "$body" | head -c 200)..."
    fi
    echo ""
}

echo "=========================================="
echo "1. Testing Server Health"
echo "=========================================="

# Test if server is reachable
echo -e "${BLUE}Testing server connectivity...${NC}"
if curl -s -f -o /dev/null "$SERVER_URL/actuator/health" 2>/dev/null || curl -s -f -o /dev/null "$SERVER_URL/" 2>/dev/null; then
    echo -e "${GREEN}✓ Server is reachable${NC}"
else
    echo -e "${RED}✗ Server is not reachable${NC}"
    echo "Please check:"
    echo "  1. Server is running: ssh root@164.92.89.74 'ps aux | grep java'"
    echo "  2. Firewall allows port 8383"
    echo "  3. Application logs: ssh root@164.92.89.74 'tail -f /opt/apps/output.log'"
    exit 1
fi
echo ""

echo "=========================================="
echo "2. Testing Public Endpoints (No Auth Required)"
echo "=========================================="

# Public user signup
test_endpoint "POST" "/api/public/users/signup" \
    "Public User Signup" \
    '{"fullNames":"Test User","phoneNumber":"250788888888","email":"test@example.com","password":"Test123456"}'

# Public receiver signup
test_endpoint "POST" "/api/public/receivers/signup" \
    "Public Receiver Signup" \
    '{"companyName":"Test Company","receiverPhone":"250788888889","email":"receiver@example.com","password":"Test123456","location":"Kigali"}'

# User login
echo -e "${BLUE}Testing:${NC} Public User Login"
echo "  ${YELLOW}POST${NC} /api/public/users/login"
USER_TOKEN=$(curl -s -X POST "$SERVER_URL/api/public/users/login" \
    -H "Content-Type: application/json" \
    -d '{"phoneNumber":"250788888888","password":"Test123456"}' | grep -o '"token":"[^"]*' | cut -d'"' -f4)
if [ -n "$USER_TOKEN" ]; then
    echo -e "  ${GREEN}✓ Login successful${NC}"
    echo "  Token: ${USER_TOKEN:0:50}..."
else
    echo -e "  ${RED}✗ Login failed${NC}"
    USER_TOKEN=""
fi
echo ""

# Receiver login
echo -e "${BLUE}Testing:${NC} Public Receiver Login"
echo "  ${YELLOW}POST${NC} /api/public/receivers/login"
RECEIVER_TOKEN=$(curl -s -X POST "$SERVER_URL/api/public/receivers/login" \
    -H "Content-Type: application/json" \
    -d '{"phoneNumber":"250788888889","password":"Test123456"}' | grep -o '"token":"[^"]*' | cut -d'"' -f4)
if [ -n "$RECEIVER_TOKEN" ]; then
    echo -e "  ${GREEN}✓ Login successful${NC}"
    echo "  Token: ${RECEIVER_TOKEN:0:50}..."
else
    echo -e "  ${RED}✗ Login failed${NC}"
    RECEIVER_TOKEN=""
fi
echo ""

echo "=========================================="
echo "3. Testing Protected Endpoints (Auth Required)"
echo "=========================================="

if [ -n "$USER_TOKEN" ]; then
    echo -e "${GREEN}Using USER token for testing...${NC}"
    echo ""
    
    # Get user's NFC card
    test_endpoint "GET" "/api/users/me/nfc-card" \
        "Get My NFC Card" "" "$USER_TOKEN"
    
    # Get user info
    test_endpoint "GET" "/api/users/me" \
        "Get My User Info" "" "$USER_TOKEN"
    
    # Get transactions
    test_endpoint "GET" "/api/payments/transactions" \
        "Get My Transactions" "" "$USER_TOKEN"
    
    # Get card details (if NFC card ID available)
    # test_endpoint "GET" "/api/payments/cards/{nfcCardId}" \
    #     "Get Card Details" "" "$USER_TOKEN"
fi

if [ -n "$RECEIVER_TOKEN" ]; then
    echo -e "${GREEN}Using RECEIVER token for testing...${NC}"
    echo ""
    
    # Get receiver wallet
    # Note: You'll need the receiver ID for this endpoint
    # test_endpoint "GET" "/api/receivers/{id}/wallet" \
    #     "Get Receiver Wallet" "" "$RECEIVER_TOKEN"
    
    # Get receiver transactions
    # test_endpoint "GET" "/api/payments/transactions/receiver/{receiverId}" \
    #     "Get Receiver Transactions" "" "$RECEIVER_TOKEN"
fi

echo "=========================================="
echo "Testing Complete!"
echo "=========================================="
echo ""
echo "Useful commands:"
echo "  View application logs: ssh root@164.92.89.74 'tail -f /opt/apps/output.log'"
echo "  Check if app is running: ssh root@164.92.89.74 'ps aux | grep java'"
echo "  Check application port: ssh root@164.92.89.74 'netstat -tlnp | grep 8383'"
echo "  Restart application: ssh root@164.92.89.74 'cd /opt/apps && kill \$(cat app.pid) && nohup java -Xmx6g -Xms2g -jar -Dspring.profiles.active=prod pocketmoney-0.0.1-SNAPSHOT.jar > output.log 2>&1 & echo \$! > app.pid'"
echo ""

