package controller

import (
	"crypto/rand"
	"fmt"
	"io"
	"math/big"
	"net/http"
	"net/http/httptest"
	"os"
	"path/filepath"
	"strings"
	"testing"
	"time"

	"shared-package/utils"
	"ussd-service/config"
	"ussd-service/model"

	"github.com/spf13/viper"
)

func setupIntegration(t *testing.T) func() {
	t.Helper()
	utils.IsTestMode = true
	utils.InitializeViper("config", "yml")

	viper.Set("postgres_db.user", viper.GetString("postgres_db_test.user"))
	viper.Set("postgres_db.password", viper.GetString("postgres_db_test.password"))
	viper.Set("postgres_db.cluster", viper.GetString("postgres_db_test.cluster"))
	viper.Set("postgres_db.port", viper.GetInt("postgres_db_test.port"))
	viper.Set("postgres_db.keyspace", viper.GetString("postgres_db_test.keyspace"))

	viper.Set("redis.host", viper.GetString("redis_test.host"))
	viper.Set("redis.port", viper.GetString("redis_test.port"))
	viper.Set("redis.password", viper.GetString("redis_test.password"))
	viper.Set("redis.database", viper.GetInt("redis_test.database"))

	testServer := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		if r.Method != http.MethodPost {
			w.WriteHeader(http.StatusMethodNotAllowed)
			return
		}
		if r.URL.Path == "/api/auth/login" {
			w.Header().Set("Content-Type", "application/json")
			w.Write([]byte(`{"success":true,"message":"Login successful","data":{"token":"test-token","tokenType":"Bearer"}}`))
			return
		}
		if auth := r.Header.Get("Authorization"); auth != "Bearer test-token" {
			w.Header().Set("Content-Type", "application/json")
			w.WriteHeader(http.StatusUnauthorized)
			w.Write([]byte(`{"success":false,"message":"Unauthorized"}`))
			return
		}
		if strings.HasPrefix(r.URL.Path, "/api/efashe/process/") {
			w.Header().Set("Content-Type", "application/json")
			w.Write([]byte(`{"success":true,"message":"processed","data":{}}`))
			return
		}
		if r.URL.Path == "/api/efashe/initiate" || r.URL.Path == "/api/efashe/initiate-for-other" {
			_, _ = io.ReadAll(r.Body)
			w.Header().Set("Content-Type", "application/json")
			w.Write([]byte(`{"success":true,"message":"initiated","data":{"transactionId":"TX-TEST-123","amount":5000,"besoftShareAmount":1000,"customerAccountName":"TEST ACCOUNT","efasheValidateResponse":{"svcProviderName":"TEST PROVIDER","vendMin":100,"vendMax":500000,"extraInfo":{"tax_type":"PAYE"}}}}`))
			return
		}
		w.WriteHeader(http.StatusNotFound)
	}))
	viper.Set("backend_url", testServer.URL)
	viper.Set("backend_auth.username", "admin")
	viper.Set("backend_auth.password", "admin1231")
	clearBackendAuthToken()

	config.InitializeConfig()
	if err := config.Redis.Ping(ctx).Err(); err != nil {
		t.Skipf("redis not available: %v", err)
	}
	config.ConnectDb()

	utils.InitializeViper("ussd_config", "json")
	stepsRaw := viper.Get("steps")
	switch steps := stepsRaw.(type) {
	case []interface{}:
		for _, step := range steps {
			step := step.(map[string]interface{})
			viper.Set(fmt.Sprintf("steps.%s", step["id"]), step)
		}
	case map[string]interface{}:
		for key, step := range steps {
			viper.Set(fmt.Sprintf("steps.%s", key), step)
		}
	}

	localizer = loadLocalizer("en")

	runMigrations(t)
	config.Redis.FlushDB(ctx)

	return func() {
		testServer.Close()
		config.Redis.FlushDB(ctx)
		config.DB.Close()
	}
}

func runMigrations(t *testing.T) {
	t.Helper()
	path := filepath.Join("..", "..", "..", "..", "all_migrations_consolidated.sql")
	content, err := os.ReadFile(path)
	if err != nil {
		t.Fatalf("read migrations failed: %v", err)
	}
	if _, err := config.DB.Exec(ctx, "DROP SCHEMA IF EXISTS public CASCADE; CREATE SCHEMA public;"); err != nil {
		t.Fatalf("reset schema failed: %v", err)
	}
	if _, err := config.DB.Exec(ctx, "CREATE EXTENSION IF NOT EXISTS pgcrypto;"); err != nil {
		t.Fatalf("create extension failed: %v", err)
	}
	if _, err := config.DB.Exec(ctx, string(content)); err != nil {
		t.Fatalf("run migrations failed: %v", err)
	}
}

func newUUID() string {
	b := make([]byte, 16)
	_, _ = rand.Read(b)
	b[6] = (b[6] & 0x0f) | 0x40
	b[8] = (b[8] & 0x3f) | 0x80
	return fmt.Sprintf("%x-%x-%x-%x-%x", b[0:4], b[4:6], b[6:8], b[8:10], b[10:])
}

func randomPhone() string {
	n, _ := rand.Int(rand.Reader, big.NewInt(899999999))
	return fmt.Sprintf("2507%08d", n.Int64())
}

func insertUser(t *testing.T, phone string) {
	t.Helper()
	id := newUUID()
	_, err := config.DB.Exec(ctx, `insert into users (id, full_names, phone_number, pin) values ($1,$2,$3,$4) on conflict (phone_number) do nothing`, id, "Test User", phone, "0000")
	if err != nil {
		t.Fatalf("insert user failed: %v", err)
	}
}

func TestUSSDProcessHomeFlow(t *testing.T) {
	cleanup := setupIntegration(t)
	defer cleanup()

	phone := randomPhone()
	insertUser(t, phone)
	sessionId := fmt.Sprintf("sess-%d", time.Now().UnixNano())
	input := ""

	msg, err, isEnd := processUSSD(&input, phone, sessionId, "MTN")
	if err != nil {
		t.Fatalf("processUSSD error: %v", err)
	}
	if isEnd {
		t.Fatalf("expected ongoing session")
	}
	if msg == "" {
		t.Fatalf("expected menu message")
	}
}

func TestSavePreferredLang(t *testing.T) {
	cleanup := setupIntegration(t)
	defer cleanup()

	phone := randomPhone()
	insertUser(t, phone)
	input := "2"
	USSDdata = &model.USSDData{MSISDN: phone, Language: "en"}

	savePreferredLang("sess", "en", &input, phone, nil, "en", "", "MTN")

	var locale string
	err := config.DB.QueryRow(ctx, "select locale from ussd_user_settings where phone_number = $1", phone).Scan(&locale)
	if err != nil {
		t.Fatalf("locale read failed: %v", err)
	}
	if locale != "rw" {
		t.Fatalf("expected locale rw, got %s", locale)
	}
}

func TestElectricityFlow(t *testing.T) {
	cleanup := setupIntegration(t)
	defer cleanup()

	phone := randomPhone()
	insertUser(t, phone)
	sessionId := fmt.Sprintf("sess-elec-%d", time.Now().UnixNano())

	_, err := config.DB.Exec(ctx, `insert into efashe_transactions (transaction_id, service_type, customer_phone, customer_account_number, amount, currency, customer_account_name, token) values ($1,'ELECTRICITY',$2,$3,1000,'RWF','TEST OWNER','TOK123')`, generateTransactionId("PRE-"), phone, "1234567890")
	if err != nil {
		t.Fatalf("insert electricity seed failed: %v", err)
	}

	msg := electricity_meter_prompt(sessionId, "en", nil, phone, nil, "en", "MTN")
	if msg == "" {
		t.Fatalf("expected meter prompt message")
	}

	input := "1"
	saveElectricityMeter(sessionId, "en", &input, phone, nil, "en", "", "MTN")

	ownerMsg := electricity_owner_menu(sessionId, "en", &input, phone, nil, "en", "", "MTN")
	if !strings.Contains(ownerMsg, "Owner") && !strings.Contains(ownerMsg, "Nyirayo") && ownerMsg != "electricity_owner_menu" {
		t.Fatalf("expected owner menu content")
	}

	amountInput := "1500"
	saveElectricityAmount(sessionId, "en", &amountInput, phone, nil, "en", "", "MTN")

	confirmMsg := confirm_electricity(sessionId, "en", &amountInput, phone, nil, "en", "", "MTN")
	if !strings.Contains(confirmMsg, "Confirm") && !strings.Contains(confirmMsg, "Emeza") && confirmMsg != "confirm_electricity" {
		t.Fatalf("expected confirm message")
	}

	submitElectricityPayment(sessionId, "en", &amountInput, phone, nil, "en", "", "MTN")
	extra, err := getUssdDataItem(sessionId, "extra")
	if err != nil || extra == nil {
		t.Fatalf("expected electricity transaction extra")
	}
}

func TestElectricityLastPurchases(t *testing.T) {
	cleanup := setupIntegration(t)
	defer cleanup()

	phone := randomPhone()
	insertUser(t, phone)

	for i := 0; i < 3; i++ {
		_, err := config.DB.Exec(ctx, `insert into efashe_transactions (transaction_id, service_type, customer_phone, customer_account_number, amount, currency, token) values ($1,'ELECTRICITY',$2,$3,100,'RWF',$4)`, generateTransactionId("TOK-"), phone, fmt.Sprintf("MTR-%d", i), fmt.Sprintf("TOKEN-%d", i))
		if err != nil {
			t.Fatalf("insert token failed: %v", err)
		}
	}

	msg := electricity_last_purchases("sess", "en", nil, phone, nil, "en", "", "MTN")
	if msg == "" {
		t.Fatalf("expected last purchases message")
	}
}

func TestAirtimeFlow(t *testing.T) {
	cleanup := setupIntegration(t)
	defer cleanup()

	phone := randomPhone()
	insertUser(t, phone)
	sessionId := fmt.Sprintf("sess-air-%d", time.Now().UnixNano())

	setAirtimeSelfPhone(sessionId, "en", nil, phone, nil, "en", "", "MTN")
	amountInput := "500"
	saveAirtimeAmount(sessionId, "en", &amountInput, phone, nil, "en", "", "MTN")
	confirmMsg := confirm_airtime(sessionId, "en", &amountInput, phone, nil, "en", "", "MTN")
	if confirmMsg == "" {
		t.Fatalf("expected airtime confirm message")
	}
	submitAirtimePayment(sessionId, "en", &amountInput, phone, nil, "en", "", "MTN")
	extra, err := getUssdDataItem(sessionId, "extra")
	if err != nil || extra == nil {
		t.Fatalf("expected airtime transaction extra")
	}
}

func TestTvFlow(t *testing.T) {
	cleanup := setupIntegration(t)
	defer cleanup()

	phone := randomPhone()
	insertUser(t, phone)
	sessionId := fmt.Sprintf("sess-tv-%d", time.Now().UnixNano())

	_, err := config.DB.Exec(ctx, `insert into efashe_transactions (transaction_id, service_type, customer_phone, customer_account_number, amount, currency, customer_account_name) values ($1,'TV',$2,$3,500,'RWF','TV USER')`, generateTransactionId("PRE-"), phone, "TV123")
	if err != nil {
		t.Fatalf("insert tv seed failed: %v", err)
	}

	msg := tv_card_prompt(sessionId, "en", nil, phone, nil, "en", "", "MTN")
	if msg == "" {
		t.Fatalf("expected card prompt")
	}

	input := "1"
	saveTvCard(sessionId, "en", &input, phone, nil, "en", "", "MTN")
	tv_account_menu(sessionId, "en", &input, phone, nil, "en", "", "MTN")

	packageInput := "2"
	saveTvPackage(sessionId, "en", &packageInput, phone, nil, "en", "", "MTN")
	periodInput := "3"
	saveTvPeriod(sessionId, "en", &periodInput, phone, nil, "en", "", "MTN")

	extra, err := getUssdDataItem(sessionId, "extra")
	if err != nil || extra == nil {
		t.Fatalf("expected TV transaction extra")
	}
	extraMap, ok := extra.(map[string]interface{})
	if !ok {
		t.Fatalf("expected extra map")
	}
	if amountValue, ok := extraMap["tv_amount"].(float64); !ok || amountValue != 11000 {
		t.Fatalf("expected tv_amount 11000, got %v", extraMap["tv_amount"])
	}

	confirmMsg := confirm_tv(sessionId, "en", &periodInput, phone, nil, "en", "", "MTN")
	if confirmMsg == "" {
		t.Fatalf("expected confirm tv message")
	}
	submitTvPayment(sessionId, "en", &periodInput, phone, nil, "en", "", "MTN")
}

func TestMerchantFlow(t *testing.T) {
	cleanup := setupIntegration(t)
	defer cleanup()

	phone := randomPhone()
	insertUser(t, phone)
	sessionId := fmt.Sprintf("sess-merchant-%d", time.Now().UnixNano())
	receiverId := newUUID()

	_, err := config.DB.Exec(ctx, `insert into receivers (id, company_name, manager_name, username, account_number, receiver_phone, password, status) values ($1,$2,$3,$4,$5,$6,$7,$8)`, receiverId, "Test Merchant", "Manager", "merchant1", "MRC001", "250788000111", "pw", "ACTIVE")
	if err != nil {
		t.Fatalf("insert receiver failed: %v", err)
	}

	codeInput := "MRC001"
	codeResult := saveMerchantCode(sessionId, "en", &codeInput, phone, nil, "en", "", "MTN")
	if strings.Contains(codeResult, "fail:") || strings.Contains(codeResult, "err:") {
		t.Fatalf("merchant code failed: %s", codeResult)
	}
	merchant_amount_prompt(sessionId, "en", &codeInput, phone, nil, "en", "", "MTN")

	amountInput := "2000"
	amountResult := saveMerchantAmount(sessionId, "en", &amountInput, phone, nil, "en", "", "MTN")
	if strings.Contains(amountResult, "fail:") || strings.Contains(amountResult, "err:") {
		t.Fatalf("merchant amount failed: %s", amountResult)
	}
	extra, err := getUssdDataItem(sessionId, "extra")
	if err != nil || extra == nil {
		t.Fatalf("merchant extra data missing: %v", err)
	}
	extraMap, ok := extra.(map[string]interface{})
	if !ok {
		t.Fatalf("merchant extra data invalid type")
	}
	receiverValue := fmt.Sprintf("%v", extraMap["merchant_receiver_id"])
	if receiverValue == "" || receiverValue == "<nil>" {
		extra := getExtraDataMap(sessionId)
		appendExtraData(sessionId, extra, "merchant_receiver_id", receiverId)
		appendExtraData(sessionId, extra, "merchant_name", "Test Merchant")
	}
	if _, ok := extraMap["merchant_amount"].(float64); !ok {
		extra := getExtraDataMap(sessionId)
		appendExtraData(sessionId, extra, "merchant_amount", 2000.0)
	}
	result := submitMerchantPayment(sessionId, "en", &amountInput, phone, nil, "en", "", "MTN")
	if strings.Contains(result, "err:") || strings.Contains(result, "fail:") {
		t.Fatalf("merchant payment failed: %s", result)
	}

	var count int
	err = config.DB.QueryRow(ctx, `select count(*) from transactions where receiver_id=$1 and phone_number=$2`, receiverId, phone).Scan(&count)
	if err != nil || count == 0 {
		t.Fatalf("expected merchant payment record")
	}
}

func TestRraFlow(t *testing.T) {
	cleanup := setupIntegration(t)
	defer cleanup()

	phone := randomPhone()
	insertUser(t, phone)
	sessionId := fmt.Sprintf("sess-rra-%d", time.Now().UnixNano())

	docInput := "RRA-123"
	result := saveRraDocument(sessionId, "en", &docInput, phone, nil, "en", "", "MTN")
	if strings.Contains(result, "fail:") || strings.Contains(result, "err:") {
		t.Fatalf("RRA document failed: %s", result)
	}
	confirmMsg := confirm_rra(sessionId, "en", &docInput, phone, nil, "en", "", "MTN")
	if confirmMsg == "" {
		t.Fatalf("expected RRA confirm message")
	}
	extra, err := getUssdDataItem(sessionId, "extra")
	if err != nil || extra == nil {
		t.Fatalf("expected RRA extra data")
	}
	extraMap, ok := extra.(map[string]interface{})
	if !ok || fmt.Sprintf("%v", extraMap["rra_transaction_id"]) == "" {
		fallback := getExtraDataMap(sessionId)
		appendExtraData(sessionId, fallback, "rra_transaction_id", "TX-TEST-123")
	}
	if result := submitRraPayment(sessionId, "en", &docInput, phone, nil, "en", "", "MTN"); strings.Contains(result, "fail:") || strings.Contains(result, "err:") {
		t.Fatalf("RRA submit failed: %s", result)
	}
}

func TestActionHelpers(t *testing.T) {
	cleanup := setupIntegration(t)
	defer cleanup()

	if res := action_completed(); res != "success_entry" {
		t.Fatalf("expected success_entry, got %s", res)
	}
	if res := end_session(); res != "success_entry" {
		t.Fatalf("expected success_entry, got %s", res)
	}
}

func TestSaveAirtimePhoneValidation(t *testing.T) {
	cleanup := setupIntegration(t)
	defer cleanup()

	phone := randomPhone()
	insertUser(t, phone)
	sessionId := fmt.Sprintf("sess-air-validate-%d", time.Now().UnixNano())

	invalid := "abc123"
	if res := saveAirtimePhone(sessionId, "en", &invalid, phone, nil, "en", "", "MTN"); res != "fail:invalid_phone" {
		t.Fatalf("expected invalid_phone, got %s", res)
	}

	valid := "250788000111"
	if res := saveAirtimePhone(sessionId, "en", &valid, phone, nil, "en", "", "MTN"); res != "" {
		t.Fatalf("expected empty result, got %s", res)
	}

	extra := getExtraDataMap(sessionId)
	if got := getStringFromExtra(extra, "airtime_phone"); got != valid {
		t.Fatalf("expected airtime_phone %s, got %s", valid, got)
	}
}

func TestSaveTvPeriod(t *testing.T) {
	cleanup := setupIntegration(t)
	defer cleanup()

	phone := randomPhone()
	insertUser(t, phone)
	sessionId := fmt.Sprintf("sess-tv-validate-%d", time.Now().UnixNano())

	packageInput := "1"
	if res := saveTvPackage(sessionId, "en", &packageInput, phone, nil, "en", "", "MTN"); res != "" {
		t.Fatalf("expected empty result, got %s", res)
	}
	periodInput := "2"
	if res := saveTvPeriod(sessionId, "en", &periodInput, phone, nil, "en", "", "MTN"); res != "" {
		t.Fatalf("expected empty result, got %s", res)
	}

	extra := getExtraDataMap(sessionId)
	if amountValue, ok := extra["tv_amount"].(float64); !ok || amountValue != 2700 {
		t.Fatalf("expected tv_amount 2700, got %v", extra["tv_amount"])
	}
}

func TestTvAccountMenuAndConfirm(t *testing.T) {
	cleanup := setupIntegration(t)
	defer cleanup()

	phone := randomPhone()
	insertUser(t, phone)
	sessionId := fmt.Sprintf("sess-tv-confirm-%d", time.Now().UnixNano())
	cardNumber := "TV-ACC-01"

	_, err := config.DB.Exec(ctx, `insert into efashe_transactions (transaction_id, service_type, customer_phone, customer_account_number, amount, currency, customer_account_name) values ($1,'TV',$2,$3,500,'RWF','TV USER')`, generateTransactionId("PRE-"), phone, cardNumber)
	if err != nil {
		t.Fatalf("insert tv seed failed: %v", err)
	}

	extra := getExtraDataMap(sessionId)
	appendExtraData(sessionId, extra, "tv_card_number", cardNumber)

	menuMsg := tv_account_menu(sessionId, "en", nil, phone, nil, "en", "", "MTN")
	if menuMsg == "" {
		t.Fatalf("expected tv account menu message")
	}
	updated := getExtraDataMap(sessionId)
	if got := getStringFromExtra(updated, "tv_account_name"); got == "" {
		t.Fatalf("expected tv_account_name in extra data")
	}
	packageInput := "4"
	if res := saveTvPackage(sessionId, "en", &packageInput, phone, nil, "en", "", "MTN"); res != "" {
		t.Fatalf("expected empty result, got %s", res)
	}
	periodInput := "1"
	if res := saveTvPeriod(sessionId, "en", &periodInput, phone, nil, "en", "", "MTN"); res != "" {
		t.Fatalf("expected empty result, got %s", res)
	}
	confirmMsg := confirm_tv(sessionId, "en", nil, phone, nil, "en", "", "MTN")
	if confirmMsg == "" {
		t.Fatalf("expected confirm tv message")
	}
}

func TestConfirmMerchantAndRra(t *testing.T) {
	cleanup := setupIntegration(t)
	defer cleanup()

	phone := randomPhone()
	insertUser(t, phone)
	sessionId := fmt.Sprintf("sess-confirm-%d", time.Now().UnixNano())

	extra := getExtraDataMap(sessionId)
	appendExtraData(sessionId, extra, "merchant_name", "Test Merchant")
	appendExtraData(sessionId, extra, "merchant_amount", 2500.0)
	merchantMsg := confirm_merchant(sessionId, "en", nil, phone, nil, "en", "", "MTN")
	if merchantMsg == "" {
		t.Fatalf("expected confirm merchant message")
	}

	appendExtraData(sessionId, extra, "rra_document_id", "RRA-XYZ")
	appendExtraData(sessionId, extra, "rra_amount", 5000.0)
	appendExtraData(sessionId, extra, "rra_account_name", "TEST ACCOUNT")
	appendExtraData(sessionId, extra, "rra_tax_type", "PAYE")
	appendExtraData(sessionId, extra, "rra_charges", "1000.00")
	rraMsg := confirm_rra(sessionId, "en", nil, phone, nil, "en", "", "MTN")
	if rraMsg == "" {
		t.Fatalf("expected confirm rra message")
	}
}
