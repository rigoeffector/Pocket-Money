package controller

import (
	"context"
	"encoding/json"
	"errors"
	"fmt"
	"reflect"
	"shared-package/utils"
	"strconv"
	"strings"
	"time"
	"unicode"
	"unicode/utf8"
	"ussd-service/config"
	"ussd-service/model"

	"math/rand"

	"github.com/BurntSushi/toml"
	"github.com/go-playground/validator/v10"
	"github.com/gofiber/fiber/v2"
	"github.com/jackc/pgx/v5"
	"github.com/nicksnyder/go-i18n/v2/i18n"
	"github.com/spf13/viper"
	"golang.org/x/text/language"
)

var Validate = validator.New()
var ctx = context.Background()
var systemError = "System error. Please try again later."

// TODO: load this from hashicorp vault

const USSD_MAX_LENGTH = 160 // Example value, adjust as needed

type USSDFlow struct {
	// Define fields and methods for your model
}

type UssdUser struct {
	Id       string
	FullName string
	Locale   string
}

var bundle *i18n.Bundle

var lang = "en" // Default language
var localizer *i18n.Localizer

func init() {
	bundle = i18n.NewBundle(language.English)
	bundle.RegisterUnmarshalFunc("toml", toml.Unmarshal)
	_, err := bundle.LoadMessageFile("/app/locales/ussd.en.toml")
	if err != nil {
		fmt.Println("Error loading EN translations:", err)
	}
	_, err = bundle.LoadMessageFile("/app/locales/ussd.sw.toml")
	if err != nil {
		fmt.Println("Error loading translations:", err)
	}
}
func loadLocalizer(lang string) *i18n.Localizer {
	if lang == "rw" {
		return i18n.NewLocalizer(bundle, "sw")
	}
	return i18n.NewLocalizer(bundle, lang)
}
func (u *USSDFlow) SetNextData(sessionId string, nextData string) error {
	// Implement the logic to store nextData in the database
	return nil
}
func ServiceStatusCheck(c *fiber.Ctx) error {
	return c.JSON(fiber.Map{"status": 200, "message": "Welcome to the Lottery USSD API service. This service is running!"})
}

func USSDService(c *fiber.Ctx) error {
	type USSDData struct {
		Msisdn      string `form:"msisdn" validate:"required"`
		Input       string `form:"input" validate:"required"`
		SessionId   string `form:"sessionId" validate:"required"`
		NetworkCode string `form:"networkCode" validate:"required"`
		NewRequest  bool   `form:"newRequest"`
	}
	ussd_data := USSDData{
		Msisdn:      c.Query("msisdn"),
		Input:       c.Query("input"),
		SessionId:   c.Query("sessionId"),
		NetworkCode: c.Query("networkCode"),
		NewRequest:  c.Query("newRequest") == "1",
	}

	if err := Validate.Struct(ussd_data); err != nil {
		return utils.USSDResponse(c, ussd_data.NetworkCode, "FB", "Invalid request data, missing required fields")
	}
	message, err, isEndSession := processUSSD(&ussd_data.Input, ussd_data.Msisdn, ussd_data.SessionId, ussd_data.NetworkCode)
	if err != nil {
		if len(message) == 0 {
			message = systemError
		}
		utils.LogMessage("error", fmt.Sprintf("USSD error: %v", err), "ussd-service")
	}
	if isEndSession {
		return utils.USSDResponse(c, ussd_data.NetworkCode, "FB", message)
	}
	return utils.USSDResponse(c, ussd_data.NetworkCode, "FC", message)
}

var USSDdata *model.USSDData

func processUSSD(input *string, phone string, sessionId string, networkOperator string) (string, error, bool) {
	USSDdata, _ = getUssdData(sessionId)
	// fmt.Println("USSDdata", USSDdata)
	if USSDdata != nil && USSDdata.StepId == "" {
		return "action_done", errors.New("no step id found, end session"), true
	}
	initialStep := "home"
	prefix := ""
	nextStep := ""
	resultMessage := ""
	isNewRequest := false
	user := &UssdUser{}
	if USSDdata == nil || USSDdata.CustomerId == nil {
		// Re-fetch user data
		err := config.DB.QueryRow(ctx, "select id, full_names from users where phone_number = $1", phone).
			Scan(&user.Id, &user.FullName)
		if err != nil {
			if errors.Is(err, pgx.ErrNoRows) {
				initialStep = "home"
				// USSDdata.LastInput = *input
				// USSDdata.Id = sessionId
			} else {
				return "", fmt.Errorf("fetch user failed, err: %v", err), true
			}
		} else {
			// USSDdata.Id = sessionId
			// USSDdata.CustomerId = &customer.Id
			// USSDdata.CustomerName = user.FullName
			// USSDdata.Language = lang
		}
		if locale := getUserLocale(phone); locale != "" {
			lang = locale
			user.Locale = locale
		}
	}
	if USSDdata == nil {
		isNewRequest = true
		//fetch initial step
		var customerId *int
		USSDdata = &model.USSDData{
			Id:           sessionId,
			MSISDN:       phone,
			CustomerId:   customerId,
			CustomerName: user.FullName,
			Language:     lang,
			LastInput:    *input,
			StepId:       initialStep,
		}
		setUssdData(*USSDdata)
	}
	// welcome step is no longer used in the current flow
	//load localizer
	localizer = loadLocalizer(lang)
	//get last step data
	lastStep := &model.USSDStep{}
	dataInputs := &[]model.USSDInput{}
	if USSDdata != nil && USSDdata.StepId != "" {
		stepData := viper.Get(fmt.Sprintf("steps.%s", USSDdata.StepId)).(map[string]interface{})
		inputs := stepData["inputs"].([]interface{})
		ussdInputs := make([]model.USSDInput, len(inputs))
		for i, input := range inputs {
			inpt := fmt.Sprintf("%v", input.(map[string]interface{})["input"])
			ac := fmt.Sprintf("%v", input.(map[string]interface{})["action"])
			nt := fmt.Sprintf("%v", input.(map[string]interface{})["next_step"])
			vl := fmt.Sprintf("%v", input.(map[string]interface{})["value"])
			vld := fmt.Sprintf("%v", input.(map[string]interface{})["validation"])
			ussdInputs[i] = model.USSDInput{
				Input:      inpt,
				Value:      vl,
				Action:     ac,
				NextStep:   nt,
				Validation: vld,
			}
		}
		vld2 := fmt.Sprintf("%v", stepData["validation"])
		lastStep = &model.USSDStep{
			Id:           stepData["id"].(string),
			Content:      stepData["content"].(string),
			AllowBack:    stepData["allow_back"].(bool),
			IsEndSession: stepData["is_end_session"].(bool),
			Validation:   &vld2,
			Inputs:       ussdInputs,
		}
		dataInputs = &lastStep.Inputs
	}
	if isNewRequest {
		setUssdData(*USSDdata)
		msg, err := prepareMessage(lastStep.Content, lang, input, phone, sessionId, user, networkOperator)
		if err != nil {
			return "", err, false
		}

		if len(prefix) != 0 {
			msg = prefix + msg
		}
		return msg, nil, false
	}
	if nextData := USSDdata.NextData; nextData != "" && *input == "n" {
		// Display next data
		USSDdata.NextData = ""
		setUssdData(*USSDdata)
		msg, err := ellipsisMsg(nextData, sessionId, lang)
		return msg, err, false
	}

	if len(*dataInputs) != 0 {
		resItem, err := validateInputs(*dataInputs, input)
		if err != nil {
			return "", err, false
		}
		nextStep = resItem.NextStep
		//update next step
		USSDdata.NextStepId = nextStep
		USSDdata.StepId = nextStep
		// fmt.Println("next step 0: ", nextStep)
		// setUssdData(*USSDdata)
		if validation := resItem.Validation; validation != "" {
			funcValue := reflect.ValueOf(map[string]interface{}{
				// Add your validation functions here
			}[validation])
			if funcValue.IsValid() {
				funcArgs := []reflect.Value{reflect.ValueOf(input), reflect.ValueOf(nextStep), reflect.ValueOf(false)}
				funcValue.Call(funcArgs)
			}
		}
		if action := resItem.Action; action != "" {
			if action == "end_session" {
				msg := utils.Localize(localizer, "thank_you", nil)
				return msg, nil, true
			}
			resultMessage, err = callUserFunc(action, sessionId, lang, input, phone, user, lang, USSDdata.LastInput, networkOperator)
			if err != nil {
				if len(resultMessage) == 0 {
					return resultMessage, err, true
				}
				return resultMessage, err, false
			}
		}
	}
	// Handle next step and end session conditions
	if nextStep == "" && !lastStep.IsEndSession {
		// Log system bug
		utils.LogMessage("critical", fmt.Sprintf("No next step & is not end USSD: %v", USSDdata), "ussd-service")
		return "", errors.New("USSD system error"), true
	} else if lastStep.IsEndSession {
		if resultMessage == "" {
			return "Request successful", nil, true
		}
		return resultMessage, nil, false
	}
	// fmt.Println("nextStepData 0: ", viper.Get(fmt.Sprintf("steps.%v", nextStep)), fmt.Sprintf("steps.%v", nextStep))
	nextStepData := viper.Get(fmt.Sprintf("steps.%v", nextStep)).(map[string]interface{})
	if USSDdata.StepId == "action_ack" {
		lang = USSDdata.Language
		localizer = loadLocalizer(lang)
	}
	//load localizer
	// fmt.Println("nextStepData: ", nextStepData)
	// if err != nil {
	// 	// Log system bug
	// 	utils.LogMessage("critical", fmt.Sprintf("Next step structure not found [%v]: %v", nextStep, USSDdata), "ussd-service")
	// 	return "", errors.New("USSD system error"), true
	// }

	msg, err := prepareMessage(nextStepData["content"].(string), lang, input, phone, sessionId, user, networkOperator)
	if err != nil {
		return "", err, false
	}

	if len(prefix) != 0 {
		msg = prefix + msg
	}

	//save updated USSD data
	USSDdata.LastInput = *input
	USSDdata.LastResponse = msg
	// USSDdata.NextMenu = nextStepData["next_menu"].(string)
	// USSDdata.NextStepId = nextStepData["next_step_id"].(string)
	setUssdData(*USSDdata)
	if lastStep.IsEndSession {
		return msg, nil, true
	}
	return msg, nil, false
}
func ellipsisMsg(msg string, sessionId string, lang string) (string, error) {
	if msg != "" && utf8.RuneCountInString(msg) > USSD_MAX_LENGTH {
		nextMessage := "n." + lang // Adjust as needed for localization
		begin := msg[:USSD_MAX_LENGTH+1]
		pos := strings.LastIndex(begin, "\n")
		if pos != -1 {
			begin = msg[:pos+1]
			end := msg[pos+1:]
			msg = begin + "\n" + nextMessage

			mdl := &USSDFlow{}
			if err := mdl.SetNextData(sessionId, end); err != nil {
				return "", err
			}
		}
	}
	return msg, nil
}

// args: sessionId, lang, input, phone, customer, lang, USSDdata.LastInput, networkOperator
func callUserFunc(functionName string, args ...interface{}) (string, error) {
	funcValue := reflect.ValueOf(map[string]interface{}{
		// Add your functions here
		"savePreferredLang":          savePreferredLang,
		"action_completed":           action_completed,
		"end_session":                end_session,
		"electricity_meter_prompt":   electricity_meter_prompt,
		"saveElectricityMeter":       saveElectricityMeter,
		"electricity_owner_menu":     electricity_owner_menu,
		"electricity_last_purchases": electricity_last_purchases,
		"saveElectricityAmount":      saveElectricityAmount,
		"confirm_electricity":        confirm_electricity,
		"submitElectricityPayment":   submitElectricityPayment,
		"setAirtimeSelfPhone":        setAirtimeSelfPhone,
		"saveAirtimePhone":           saveAirtimePhone,
		"saveAirtimeAmount":          saveAirtimeAmount,
		"confirm_airtime":            confirm_airtime,
		"submitAirtimePayment":       submitAirtimePayment,
		"tv_card_prompt":             tv_card_prompt,
		"saveTvCard":                 saveTvCard,
		"tv_account_menu":            tv_account_menu,
		"saveTvPackage":              saveTvPackage,
		"saveTvAmount":               saveTvAmount,
		"confirm_tv":                 confirm_tv,
		"submitTvPayment":            submitTvPayment,
		"saveMerchantCode":           saveMerchantCode,
		"merchant_amount_prompt":     merchant_amount_prompt,
		"saveMerchantAmount":         saveMerchantAmount,
		"confirm_merchant":           confirm_merchant,
		"submitMerchantPayment":      submitMerchantPayment,
		"saveRraDocument":            saveRraDocument,
		"saveRraAmount":              saveRraAmount,
		"confirm_rra":                confirm_rra,
		"submitRraPayment":           submitRraPayment,
	}[functionName])
	if !funcValue.IsValid() {
		return "", fmt.Errorf("invalid function call: %s, arg: %v", functionName, args)
	}
	funcArgs := make([]reflect.Value, len(args))
	for i, arg := range args {
		funcArgs[i] = reflect.ValueOf(arg)
	}
	result := funcValue.Call(funcArgs)
	if len(result) == 0 {
		return "", nil
	}
	msg, ok := result[0].Interface().(string)
	if !ok {
		return "", errors.New("function did not return a string")
	}
	if strings.Contains(msg, "err:") {
		return "", errors.New(strings.Split(msg, "err:")[1])
	} else if strings.Contains(msg, "fail:") {
		failMsg := utils.Localize(localizer, strings.Split(msg, "fail:")[1], nil)
		return failMsg, errors.New(failMsg)
	}
	if len(msg) != 0 {
		msg = utils.Localize(localizer, msg, nil)
	}
	return ellipsisMsg(msg, args[0].(string), args[1].(string))
}
func validateInputs(data []model.USSDInput, input *string) (*model.USSDInput, error) {
	optional := false
	itemRow := model.USSDInput{}
	for _, item := range data {
		// fmt.Println("input item: ", item.Input, item.Value)
		if item.Input != "" && input != nil && item.Input == *input {
			return &item, nil
		}
		if item.Input == "" {
			itemRow = item
			optional = true
		}
	}
	if optional {
		return &itemRow, nil
	}
	return nil, fmt.Errorf("invalid input : %s", *input)
}

func prepareMessage(data string, lang string, input *string, phone string, sessionId string, customer interface{}, operator interface{}) (string, error) {
	if strings.Contains(data, ":fn") {
		action := strings.Split(data, ":fn")[0]
		// fmt.Println("prepareMessage action: ", action, input)
		return callUserFunc(action, sessionId, lang, input, phone, customer, lang, operator)
	} else {
		var arg map[string]interface{} = nil
		if data == "home_ussd" {
			if usr, ok := customer.(*UssdUser); ok {
				arg = map[string]interface{}{"Name": usr.FullName}
			}
		}
		msg := utils.Localize(localizer, data, arg)
		return ellipsisMsg(msg, sessionId, lang)
	}
}

func getUssdData(sessionId string) (*model.USSDData, error) {
	// get json ussd data from redis
	redisData, err := config.Redis.Get(ctx, "ussd:"+sessionId).Result()
	if err != nil {
		return nil, err
	}
	ussdData := model.USSDData{}
	err = json.Unmarshal([]byte(redisData), &ussdData)
	ussdData.Id = sessionId
	return &ussdData, err
}
func getUssdDataItem(sessionId string, itemKey string) (interface{}, error) {
	// get json ussd data from redis
	redisData, err := config.Redis.Get(ctx, "ussd:"+sessionId+"-"+itemKey).Result()
	if err != nil {
		return nil, err
	}
	if itemKey == "data" {
		ussdData := []map[string]interface{}{}
		err = json.Unmarshal([]byte(redisData), &ussdData)
		return ussdData, err
	} else {
		ussdData := make(map[string]interface{})
		err = json.Unmarshal([]byte(redisData), &ussdData)
		return ussdData, err
	}
}
func setUssdData(ussdData model.USSDData) error {
	// set json ussd data to redis
	jsonData, err := json.Marshal(ussdData)
	if err != nil {
		return err
	}
	// fmt.Println("setUssdDataItem 1: ", string(jsonData), ussdData.Id)
	return config.Redis.Set(ctx, "ussd:"+ussdData.Id, jsonData, 120*time.Second).Err()
}
func setUssdDataItem(sessionId string, itemKey string, value interface{}) error {

	jsonData, err := json.Marshal(value)
	if err != nil {
		return err
	}
	return config.Redis.Set(ctx, "ussd:"+sessionId+"-"+itemKey, jsonData, 120*time.Second).Err()
}

func getUserLocale(phone string) string {
	var locale string
	err := config.DB.QueryRow(ctx, "select locale from ussd_user_settings where phone_number = $1", phone).Scan(&locale)
	if err != nil {
		return ""
	}
	return locale
}
func savePreferredLang(args ...interface{}) string {
	input := args[2].(*string)
	phone := args[3].(string)
	lang := "en"
	if *input == "2" {
		lang = "rw"
	}
	_, err := config.DB.Exec(ctx, `insert into ussd_user_settings (phone_number, locale) values ($1,$2)
		on conflict (phone_number) do update set locale = excluded.locale, updated_at = CURRENT_TIMESTAMP`, phone, lang)
	if err != nil {
		utils.LogMessage("error", "savePreferredLang: upsert user settings failed: err:"+err.Error(), "ussd-service")
		return "err:system_error"
	}

	//update USSD data
	USSDdata.Language = lang
	return ""
}

// args: sessionId, lang, *input, phone, customer, lang, *USSDdata.LastInput, networkOperator
func preSavePreferredLang(args ...interface{}) string {
	input := args[2].(*string)
	sessionId := args[0].(string)
	extra, _ := getUssdDataItem(sessionId, "extra")
	lang := "en"
	if *input == "2" {
		lang = "rw"
	}
	if extra == nil || reflect.ValueOf(extra).IsNil() {
		extra = make(map[string]interface{})
	}
	extraData := extra.(map[string]interface{})
	appendExtraData(sessionId, extraData, "preferred_lang", lang)
	return ""
}
func appendExtraData(sessionId string, extra map[string]interface{}, key string, value interface{}) error {
	if len(extra) == 0 {
		extra = make(map[string]interface{})
	}
	extra[key] = value
	return setUssdDataItem(sessionId, "extra", extra)
}
func action_completed(args ...interface{}) string {
	return "success_entry"
}
func end_session(args ...interface{}) string {
	return "success_entry"
}

func getExtraDataMap(sessionId string) map[string]interface{} {
	extra, err := getUssdDataItem(sessionId, "extra")
	if err != nil || extra == nil {
		return map[string]interface{}{}
	}
	if data, ok := extra.(map[string]interface{}); ok && data != nil {
		return data
	}
	return map[string]interface{}{}
}

func getStringSlice(value interface{}) []string {
	result := []string{}
	if value == nil {
		return result
	}
	switch v := value.(type) {
	case []string:
		return v
	case []interface{}:
		for _, item := range v {
			result = append(result, fmt.Sprintf("%v", item))
		}
	}
	return result
}

func getStringFromExtra(extra map[string]interface{}, key string) string {
	if extra == nil {
		return ""
	}
	if value, ok := extra[key]; ok {
		return fmt.Sprintf("%v", value)
	}
	return ""
}

func parseAmount(value string) (float64, error) {
	clean := strings.ReplaceAll(strings.TrimSpace(value), ",", "")
	if clean == "" {
		return 0, fmt.Errorf("empty")
	}
	amount, err := strconv.ParseFloat(clean, 64)
	if err != nil || amount <= 0 {
		return 0, fmt.Errorf("invalid")
	}
	return amount, nil
}

func formatAmount(amount float64) string {
	return fmt.Sprintf("%.2f", amount)
}

func generateTransactionId(prefix string) string {
	return fmt.Sprintf("%s%d%d", prefix, time.Now().Unix(), rand.Intn(900)+100)
}

func isDigits(value string) bool {
	if value == "" {
		return false
	}
	for _, r := range value {
		if !unicode.IsDigit(r) {
			return false
		}
	}
	return true
}

func fetchRecentAccountNumbers(phone string, serviceType string) ([]string, error) {
	rows, err := config.DB.Query(ctx, `select customer_account_number from efashe_transactions where customer_phone = $1 and service_type = $2 order by created_at desc limit 10`, phone, serviceType)
	if err != nil {
		return []string{}, err
	}
	defer rows.Close()
	unique := map[string]bool{}
	result := []string{}
	for rows.Next() {
		var number string
		if err := rows.Scan(&number); err != nil {
			return []string{}, err
		}
		if number == "" {
			continue
		}
		if !unique[number] {
			unique[number] = true
			result = append(result, number)
		}
		if len(result) >= 3 {
			break
		}
	}
	return result, nil
}

func lookupAccountName(serviceType string, accountNumber string) string {
	if accountNumber == "" {
		return ""
	}
	var accountName string
	err := config.DB.QueryRow(ctx, `select customer_account_name from efashe_transactions where service_type = $1 and customer_account_number = $2 and customer_account_name is not null order by created_at desc limit 1`, serviceType, accountNumber).
		Scan(&accountName)
	if err != nil {
		return ""
	}
	return accountName
}

func electricity_meter_prompt(args ...interface{}) string {
	sessionId := args[0].(string)
	phone := args[3].(string)
	recent, err := fetchRecentAccountNumbers(phone, "ELECTRICITY")
	if err != nil {
		utils.LogMessage("error", "electricity_meter_prompt: fetch recent failed: err:"+err.Error(), "ussd-service")
	}
	extra := getExtraDataMap(sessionId)
	appendExtraData(sessionId, extra, "electricity_recent_numbers", recent)
	if len(recent) == 0 {
		return utils.Localize(localizer, "electricity_meter_prompt_no_recent", nil)
	}
	list := ""
	for i, num := range recent {
		list += fmt.Sprintf("%d) %s\n", i+1, num)
	}
	return utils.Localize(localizer, "electricity_meter_prompt", map[string]interface{}{"Numbers": list})
}

func saveElectricityMeter(args ...interface{}) string {
	input := strings.TrimSpace(*args[2].(*string))
	sessionId := args[0].(string)
	extra := getExtraDataMap(sessionId)
	list := getStringSlice(extra["electricity_recent_numbers"])
	meterNumber := input
	if idx, err := strconv.Atoi(input); err == nil && idx >= 1 && idx <= len(list) {
		meterNumber = list[idx-1]
	}
	if meterNumber == "" {
		return "fail:invalid_input"
	}
	appendExtraData(sessionId, extra, "electricity_meter_number", meterNumber)
	return ""
}

func electricity_owner_menu(args ...interface{}) string {
	sessionId := args[0].(string)
	extra := getExtraDataMap(sessionId)
	meterNumber := getStringFromExtra(extra, "electricity_meter_number")
	ownerName := lookupAccountName("ELECTRICITY", meterNumber)
	if ownerName == "" {
		ownerName = utils.Localize(localizer, "account_name_unknown", nil)
	}
	appendExtraData(sessionId, extra, "electricity_owner_name", ownerName)
	return utils.Localize(localizer, "electricity_owner_menu", map[string]interface{}{"MeterNumber": meterNumber, "OwnerName": ownerName})
}

func electricity_last_purchases(args ...interface{}) string {
	phone := args[3].(string)
	rows, err := config.DB.Query(ctx, `select token, amount, created_at from efashe_transactions where customer_phone = $1 and service_type = 'ELECTRICITY' and token is not null order by created_at desc limit 3`, phone)
	if err != nil {
		utils.LogMessage("error", "electricity_last_purchases: fetch failed: err:"+err.Error(), "ussd-service")
		return "err:system_error"
	}
	defer rows.Close()
	list := ""
	index := 1
	for rows.Next() {
		var token string
		var amount float64
		var createdAt time.Time
		if err := rows.Scan(&token, &amount, &createdAt); err != nil {
			utils.LogMessage("error", "electricity_last_purchases: scan failed: err:"+err.Error(), "ussd-service")
			return "err:system_error"
		}
		if token == "" {
			continue
		}
		list += fmt.Sprintf("%d) %s - %s RWF\n", index, token, formatAmount(amount))
		index++
	}
	if list == "" {
		return utils.Localize(localizer, "electricity_last_purchases_empty", nil)
	}
	return utils.Localize(localizer, "electricity_last_purchases", map[string]interface{}{"Purchases": list})
}

func saveElectricityAmount(args ...interface{}) string {
	sessionId := args[0].(string)
	input := strings.TrimSpace(*args[2].(*string))
	amount, err := parseAmount(input)
	if err != nil {
		return "fail:invalid_amount"
	}
	extra := getExtraDataMap(sessionId)
	appendExtraData(sessionId, extra, "electricity_amount", amount)
	return ""
}

func confirm_electricity(args ...interface{}) string {
	sessionId := args[0].(string)
	extra := getExtraDataMap(sessionId)
	meterNumber := getStringFromExtra(extra, "electricity_meter_number")
	ownerName := getStringFromExtra(extra, "electricity_owner_name")
	amountValue, _ := extra["electricity_amount"].(float64)
	return utils.Localize(localizer, "confirm_electricity", map[string]interface{}{
		"MeterNumber": meterNumber,
		"OwnerName":   ownerName,
		"Amount":      formatAmount(amountValue),
	})
}

func submitElectricityPayment(args ...interface{}) string {
	sessionId := args[0].(string)
	phone := args[3].(string)
	extra := getExtraDataMap(sessionId)
	meterNumber := getStringFromExtra(extra, "electricity_meter_number")
	ownerName := getStringFromExtra(extra, "electricity_owner_name")
	amountValue, ok := extra["electricity_amount"].(float64)
	if meterNumber == "" || !ok {
		return "fail:invalid_input"
	}
	transactionId := generateTransactionId("USSD-ELEC-")
	_, err := config.DB.Exec(ctx, `insert into efashe_transactions (transaction_id, service_type, customer_phone, customer_account_number, amount, currency, efashe_status, mopay_status, message, customer_account_name, validated, payment_mode) values ($1, 'ELECTRICITY', $2, $3, $4, 'RWF', 'PENDING', 'PENDING', 'USSD electricity payment', $5, 'INITIAL', 'MOBILE')`,
		transactionId, phone, meterNumber, amountValue, ownerName)
	if err != nil {
		utils.LogMessage("error", "submitElectricityPayment: insert failed: err:"+err.Error(), "ussd-service")
		return "err:system_error"
	}
	return ""
}

func setAirtimeSelfPhone(args ...interface{}) string {
	sessionId := args[0].(string)
	phone := args[3].(string)
	extra := getExtraDataMap(sessionId)
	appendExtraData(sessionId, extra, "airtime_phone", phone)
	return ""
}

func saveAirtimePhone(args ...interface{}) string {
	sessionId := args[0].(string)
	input := strings.TrimSpace(*args[2].(*string))
	if !isDigits(input) {
		return "fail:invalid_phone"
	}
	extra := getExtraDataMap(sessionId)
	appendExtraData(sessionId, extra, "airtime_phone", input)
	return ""
}

func saveAirtimeAmount(args ...interface{}) string {
	sessionId := args[0].(string)
	input := strings.TrimSpace(*args[2].(*string))
	amount, err := parseAmount(input)
	if err != nil {
		return "fail:invalid_amount"
	}
	extra := getExtraDataMap(sessionId)
	appendExtraData(sessionId, extra, "airtime_amount", amount)
	return ""
}

func confirm_airtime(args ...interface{}) string {
	sessionId := args[0].(string)
	extra := getExtraDataMap(sessionId)
	phone := getStringFromExtra(extra, "airtime_phone")
	amountValue, _ := extra["airtime_amount"].(float64)
	return utils.Localize(localizer, "confirm_airtime", map[string]interface{}{
		"Phone":  phone,
		"Amount": formatAmount(amountValue),
	})
}

func submitAirtimePayment(args ...interface{}) string {
	sessionId := args[0].(string)
	payerPhone := args[3].(string)
	extra := getExtraDataMap(sessionId)
	targetPhone := getStringFromExtra(extra, "airtime_phone")
	amountValue, ok := extra["airtime_amount"].(float64)
	if targetPhone == "" || !ok {
		return "fail:invalid_input"
	}
	transactionId := generateTransactionId("USSD-AIR-")
	_, err := config.DB.Exec(ctx, `insert into efashe_transactions (transaction_id, service_type, customer_phone, customer_account_number, amount, currency, efashe_status, mopay_status, message, validated, payment_mode) values ($1, 'AIRTIME', $2, $3, $4, 'RWF', 'PENDING', 'PENDING', 'USSD airtime payment', 'INITIAL', 'MOBILE')`,
		transactionId, payerPhone, targetPhone, amountValue)
	if err != nil {
		utils.LogMessage("error", "submitAirtimePayment: insert failed: err:"+err.Error(), "ussd-service")
		return "err:system_error"
	}
	return ""
}

func tv_card_prompt(args ...interface{}) string {
	sessionId := args[0].(string)
	phone := args[3].(string)
	recent, err := fetchRecentAccountNumbers(phone, "TV")
	if err != nil {
		utils.LogMessage("error", "tv_card_prompt: fetch recent failed: err:"+err.Error(), "ussd-service")
	}
	extra := getExtraDataMap(sessionId)
	appendExtraData(sessionId, extra, "tv_recent_numbers", recent)
	if len(recent) == 0 {
		return utils.Localize(localizer, "tv_card_prompt_no_recent", nil)
	}
	list := ""
	for i, num := range recent {
		list += fmt.Sprintf("%d) %s\n", i+1, num)
	}
	return utils.Localize(localizer, "tv_card_prompt", map[string]interface{}{"Numbers": list})
}

func saveTvCard(args ...interface{}) string {
	input := strings.TrimSpace(*args[2].(*string))
	sessionId := args[0].(string)
	extra := getExtraDataMap(sessionId)
	list := getStringSlice(extra["tv_recent_numbers"])
	cardNumber := input
	if idx, err := strconv.Atoi(input); err == nil && idx >= 1 && idx <= len(list) {
		cardNumber = list[idx-1]
	}
	if cardNumber == "" {
		return "fail:invalid_input"
	}
	appendExtraData(sessionId, extra, "tv_card_number", cardNumber)
	return ""
}

func tv_account_menu(args ...interface{}) string {
	sessionId := args[0].(string)
	extra := getExtraDataMap(sessionId)
	cardNumber := getStringFromExtra(extra, "tv_card_number")
	accountName := lookupAccountName("TV", cardNumber)
	if accountName == "" {
		accountName = utils.Localize(localizer, "account_name_unknown", nil)
	}
	appendExtraData(sessionId, extra, "tv_account_name", accountName)
	return utils.Localize(localizer, "tv_account_menu", map[string]interface{}{"CardNumber": cardNumber, "AccountName": accountName})
}

func saveTvPackage(args ...interface{}) string {
	sessionId := args[0].(string)
	input := strings.TrimSpace(*args[2].(*string))
	packageName := ""
	switch input {
	case "1":
		packageName = "DAILY"
	case "2":
		packageName = "WEEKLY"
	case "3":
		packageName = "MONTHLY"
	}
	if packageName == "" {
		return "fail:invalid_input"
	}
	extra := getExtraDataMap(sessionId)
	appendExtraData(sessionId, extra, "tv_package", packageName)
	return ""
}

func saveTvAmount(args ...interface{}) string {
	sessionId := args[0].(string)
	input := strings.TrimSpace(*args[2].(*string))
	amount, err := parseAmount(input)
	if err != nil {
		return "fail:invalid_amount"
	}
	extra := getExtraDataMap(sessionId)
	appendExtraData(sessionId, extra, "tv_amount", amount)
	return ""
}

func confirm_tv(args ...interface{}) string {
	sessionId := args[0].(string)
	extra := getExtraDataMap(sessionId)
	cardNumber := getStringFromExtra(extra, "tv_card_number")
	accountName := getStringFromExtra(extra, "tv_account_name")
	packageName := getStringFromExtra(extra, "tv_package")
	amountValue, _ := extra["tv_amount"].(float64)
	return utils.Localize(localizer, "confirm_tv", map[string]interface{}{
		"CardNumber":  cardNumber,
		"AccountName": accountName,
		"Package":     packageName,
		"Amount":      formatAmount(amountValue),
	})
}

func submitTvPayment(args ...interface{}) string {
	sessionId := args[0].(string)
	phone := args[3].(string)
	extra := getExtraDataMap(sessionId)
	cardNumber := getStringFromExtra(extra, "tv_card_number")
	accountName := getStringFromExtra(extra, "tv_account_name")
	packageName := getStringFromExtra(extra, "tv_package")
	amountValue, ok := extra["tv_amount"].(float64)
	if cardNumber == "" || !ok {
		return "fail:invalid_input"
	}
	transactionId := generateTransactionId("USSD-TV-")
	message := fmt.Sprintf("USSD TV payment (%s)", packageName)
	_, err := config.DB.Exec(ctx, `insert into efashe_transactions (transaction_id, service_type, customer_phone, customer_account_number, amount, currency, efashe_status, mopay_status, message, customer_account_name, validated, payment_mode) values ($1, 'TV', $2, $3, $4, 'RWF', 'PENDING', 'PENDING', $5, $6, 'INITIAL', 'MOBILE')`,
		transactionId, phone, cardNumber, amountValue, message, accountName)
	if err != nil {
		utils.LogMessage("error", "submitTvPayment: insert failed: err:"+err.Error(), "ussd-service")
		return "err:system_error"
	}
	return ""
}

func saveMerchantCode(args ...interface{}) string {
	sessionId := args[0].(string)
	code := strings.TrimSpace(*args[2].(*string))
	if code == "" {
		return "fail:invalid_input"
	}
	var receiverId string
	var merchantName string
	err := config.DB.QueryRow(ctx, `select id, company_name from receivers where account_number = $1 or username = $1 limit 1`, code).
		Scan(&receiverId, &merchantName)
	if err != nil {
		if errors.Is(err, pgx.ErrNoRows) {
			return "fail:merchant_not_found"
		}
		utils.LogMessage("error", "saveMerchantCode: lookup failed: err:"+err.Error(), "ussd-service")
		return "err:system_error"
	}
	extra := getExtraDataMap(sessionId)
	appendExtraData(sessionId, extra, "merchant_receiver_id", receiverId)
	appendExtraData(sessionId, extra, "merchant_name", merchantName)
	return ""
}

func merchant_amount_prompt(args ...interface{}) string {
	sessionId := args[0].(string)
	extra := getExtraDataMap(sessionId)
	merchantName := getStringFromExtra(extra, "merchant_name")
	return utils.Localize(localizer, "merchant_amount_prompt", map[string]interface{}{"MerchantName": merchantName})
}

func saveMerchantAmount(args ...interface{}) string {
	sessionId := args[0].(string)
	input := strings.TrimSpace(*args[2].(*string))
	amount, err := parseAmount(input)
	if err != nil {
		return "fail:invalid_amount"
	}
	extra := getExtraDataMap(sessionId)
	appendExtraData(sessionId, extra, "merchant_amount", amount)
	return ""
}

func confirm_merchant(args ...interface{}) string {
	sessionId := args[0].(string)
	extra := getExtraDataMap(sessionId)
	merchantName := getStringFromExtra(extra, "merchant_name")
	amountValue, _ := extra["merchant_amount"].(float64)
	return utils.Localize(localizer, "confirm_merchant", map[string]interface{}{
		"MerchantName": merchantName,
		"Amount":       formatAmount(amountValue),
	})
}

func submitMerchantPayment(args ...interface{}) string {
	sessionId := args[0].(string)
	payerPhone := args[3].(string)
	extra := getExtraDataMap(sessionId)
	receiverId := getStringFromExtra(extra, "merchant_receiver_id")
	amountValue, ok := extra["merchant_amount"].(float64)
	if receiverId == "" || !ok {
		return "fail:invalid_input"
	}
	_, err := config.DB.Exec(ctx, `insert into transactions (receiver_id, transaction_type, amount, status, phone_number, message) values ($1::uuid, 'PAYMENT', $2, 'PENDING', $3, 'USSD merchant payment')`, receiverId, amountValue, payerPhone)
	if err != nil {
		utils.LogMessage("error", "submitMerchantPayment: insert failed: err:"+err.Error(), "ussd-service")
		return "err:system_error"
	}
	return ""
}

func saveRraDocument(args ...interface{}) string {
	sessionId := args[0].(string)
	documentId := strings.TrimSpace(*args[2].(*string))
	if documentId == "" {
		return "fail:invalid_input"
	}
	extra := getExtraDataMap(sessionId)
	appendExtraData(sessionId, extra, "rra_document_id", documentId)
	return ""
}

func saveRraAmount(args ...interface{}) string {
	sessionId := args[0].(string)
	input := strings.TrimSpace(*args[2].(*string))
	amount, err := parseAmount(input)
	if err != nil {
		return "fail:invalid_amount"
	}
	extra := getExtraDataMap(sessionId)
	appendExtraData(sessionId, extra, "rra_amount", amount)
	return ""
}

func confirm_rra(args ...interface{}) string {
	sessionId := args[0].(string)
	extra := getExtraDataMap(sessionId)
	documentId := getStringFromExtra(extra, "rra_document_id")
	amountValue, _ := extra["rra_amount"].(float64)
	return utils.Localize(localizer, "confirm_rra", map[string]interface{}{
		"DocumentId": documentId,
		"Amount":     formatAmount(amountValue),
	})
}

func submitRraPayment(args ...interface{}) string {
	sessionId := args[0].(string)
	phone := args[3].(string)
	extra := getExtraDataMap(sessionId)
	documentId := getStringFromExtra(extra, "rra_document_id")
	amountValue, ok := extra["rra_amount"].(float64)
	if documentId == "" || !ok {
		return "fail:invalid_input"
	}
	transactionId := generateTransactionId("USSD-RRA-")
	_, err := config.DB.Exec(ctx, `insert into efashe_transactions (transaction_id, service_type, customer_phone, customer_account_number, amount, currency, efashe_status, mopay_status, message, validated, payment_mode) values ($1, 'RRA', $2, $3, $4, 'RWF', 'PENDING', 'PENDING', 'USSD RRA payment', 'INITIAL', 'MOBILE')`,
		transactionId, phone, documentId, amountValue)
	if err != nil {
		utils.LogMessage("error", "submitRraPayment: insert failed: err:"+err.Error(), "ussd-service")
		return "err:system_error"
	}
	return ""
}
