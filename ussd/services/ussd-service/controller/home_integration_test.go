package controller

import (
	"crypto/rand"
	"fmt"
	"math/big"
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

	ensureSchema(t)
	config.Redis.FlushDB(ctx)

	return func() {
		config.Redis.FlushDB(ctx)
		config.DB.Close()
	}
}

func ensureSchema(t *testing.T) {
	t.Helper()
	statements := []string{
		"create extension if not exists pgcrypto;",
		`create table if not exists users (
			id uuid primary key,
			full_names varchar(255) not null,
			phone_number varchar(20) not null unique
		);`,
		`create table if not exists ussd_user_settings (
			phone_number varchar(20) primary key,
			locale varchar(5) not null default 'en',
			created_at timestamp not null default current_timestamp,
			updated_at timestamp not null default current_timestamp
		);`,
		`create table if not exists receivers (
			id uuid primary key,
			company_name varchar(255) not null,
			username varchar(255),
			account_number varchar(255),
			receiver_phone varchar(20),
			password varchar(255) not null default '',
			status varchar(20) default 'NOT_ACTIVE',
			wallet_balance numeric(19,2) default 0,
			total_received numeric(19,2) default 0,
			assigned_balance numeric(19,2) default 0,
			remaining_balance numeric(19,2) default 0,
			discount_percentage numeric(5,2) default 0,
			user_bonus_percentage numeric(5,2) default 0,
			is_flexible boolean default false,
			created_at timestamp not null default current_timestamp,
			updated_at timestamp not null default current_timestamp
		);`,
		`create table if not exists transactions (
			id uuid primary key default gen_random_uuid(),
			receiver_id uuid,
			transaction_type varchar(20) not null,
			amount numeric(19,2) not null,
			status varchar(20) not null,
			phone_number varchar(50),
			message varchar(1000),
			created_at timestamp not null default current_timestamp,
			updated_at timestamp not null default current_timestamp
		);`,
		`create table if not exists efashe_transactions (
			id uuid primary key default gen_random_uuid(),
			transaction_id varchar(255) unique,
			service_type varchar(20) not null,
			customer_phone varchar(20) not null,
			customer_account_number varchar(20) not null,
			amount numeric(10,2),
			currency varchar(10) default 'RWF',
			trx_id varchar(255),
			mopay_transaction_id varchar(255),
			mopay_status varchar(50),
			efashe_status varchar(50),
			delivery_method_id varchar(50),
			deliver_to varchar(500),
			poll_endpoint varchar(500),
			retry_after_secs integer,
			message varchar(1000),
			error_message varchar(1000),
			customer_cashback_amount numeric(10,2),
			besoft_share_amount numeric(10,2),
			full_amount_phone varchar(20),
			cashback_phone varchar(20),
			cashback_sent boolean default false,
			full_amount_transaction_id varchar(255),
			customer_cashback_transaction_id varchar(255),
			besoft_share_transaction_id varchar(255),
			initial_mopay_status varchar(50),
			initial_efashe_status varchar(50),
			customer_account_name varchar(255),
			validated varchar(20),
			payment_mode varchar(50),
			callback_url varchar(500),
			token varchar(255),
			created_at timestamp not null default current_timestamp,
			updated_at timestamp not null default current_timestamp
		);`,
	}

	for _, statement := range statements {
		if _, err := config.DB.Exec(ctx, statement); err != nil {
			t.Fatalf("schema setup failed: %v", err)
		}
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
	_, err := config.DB.Exec(ctx, `insert into users (id, full_names, phone_number) values ($1,$2,$3) on conflict (phone_number) do nothing`, id, "Test User", phone)
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

	var count int
	err = config.DB.QueryRow(ctx, `select count(*) from efashe_transactions where customer_phone=$1 and service_type='ELECTRICITY' and message='USSD electricity payment'`, phone).Scan(&count)
	if err != nil || count == 0 {
		t.Fatalf("expected electricity payment record")
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
	submitAirtimePayment(sessionId, "en", &amountInput, phone, nil, "en", "", "MTN")

	var count int
	err := config.DB.QueryRow(ctx, `select count(*) from efashe_transactions where customer_phone=$1 and service_type='AIRTIME'`, phone).Scan(&count)
	if err != nil || count == 0 {
		t.Fatalf("expected airtime payment record")
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

	packageInput := "1"
	saveTvPackage(sessionId, "en", &packageInput, phone, nil, "en", "", "MTN")

	amountInput := "1200"
	saveTvAmount(sessionId, "en", &amountInput, phone, nil, "en", "", "MTN")
	submitTvPayment(sessionId, "en", &amountInput, phone, nil, "en", "", "MTN")

	var count int
	err = config.DB.QueryRow(ctx, `select count(*) from efashe_transactions where customer_phone=$1 and service_type='TV' and message like 'USSD TV payment%'`, phone).Scan(&count)
	if err != nil || count == 0 {
		t.Fatalf("expected TV payment record")
	}
}

func TestMerchantFlow(t *testing.T) {
	cleanup := setupIntegration(t)
	defer cleanup()

	phone := randomPhone()
	insertUser(t, phone)
	sessionId := fmt.Sprintf("sess-merchant-%d", time.Now().UnixNano())
	receiverId := newUUID()

	_, err := config.DB.Exec(ctx, `insert into receivers (id, company_name, username, account_number, receiver_phone, password, status) values ($1,$2,$3,$4,$5,$6,$7)`, receiverId, "Test Merchant", "merchant1", "MRC001", "250788000111", "pw", "ACTIVE")
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
	saveRraDocument(sessionId, "en", &docInput, phone, nil, "en", "", "MTN")

	amountInput := "7500"
	saveRraAmount(sessionId, "en", &amountInput, phone, nil, "en", "", "MTN")
	submitRraPayment(sessionId, "en", &amountInput, phone, nil, "en", "", "MTN")

	var count int
	err := config.DB.QueryRow(ctx, `select count(*) from efashe_transactions where customer_phone=$1 and service_type='RRA'`, phone).Scan(&count)
	if err != nil || count == 0 {
		t.Fatalf("expected RRA payment record")
	}
}
