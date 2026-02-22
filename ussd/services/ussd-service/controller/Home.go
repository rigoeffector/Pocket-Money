package controller

import (
	"bytes"
	"context"
	"encoding/json"
	"errors"
	"fmt"
	"io"
	"net/http"
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

type apiResponse struct {
	Success bool            `json:"success"`
	Message string          `json:"message"`
	Data    json.RawMessage `json:"data"`
}

type efasheInitiateResponse struct {
	TransactionId          string                 `json:"transactionId"`
	Amount                 float64                `json:"amount"`
	BesoftShareAmount      float64                `json:"besoftShareAmount"`
	CustomerAccountName    string                 `json:"customerAccountName"`
	EfasheValidateResponse map[string]interface{} `json:"efasheValidateResponse"`
}

type authLoginData struct {
	Token     string `json:"token"`
	TokenType string `json:"tokenType"`
}

type backendError struct {
	status  int
	message string
}

func (e *backendError) Error() string {
	return e.message
}

var bundle *i18n.Bundle

var lang = "en" // Default language
var localizer *i18n.Localizer
var backendAuthToken string
var backendAuthTokenType string
var backendAuthTokenExpiresAt time.Time

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
		if msg != "" {
			return msg, err, false
		}
		return "", err, false
	}

	nextIsEndSession := false
	if rawEnd, ok := nextStepData["is_end_session"].(bool); ok {
		nextIsEndSession = rawEnd
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
	if nextIsEndSession || lastStep.IsEndSession {
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
		"tv_period_prompt":           tv_period_prompt,
		"saveTvPeriod":               saveTvPeriod,
		"tv_amount_prompt":           tv_amount_prompt,
		"saveTvAmount":               saveTvAmount,
		"confirm_tv":                 confirm_tv,
		"submitTvPayment":            submitTvPayment,
		"saveMerchantCode":           saveMerchantCode,
		"merchant_amount_prompt":     merchant_amount_prompt,
		"saveMerchantAmount":         saveMerchantAmount,
		"confirm_merchant":           confirm_merchant,
		"submitMerchantPayment":      submitMerchantPayment,
		"saveRraDocument":            saveRraDocument,
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
		failKey := strings.Split(msg, "fail:")[1]
		failMsg := failKey
		if isLocalizationKey(failKey) {
			failMsg = utils.Localize(localizer, failKey, nil)
		}
		return failMsg, errors.New(failMsg)
	}
	if len(msg) != 0 {
		if isLocalizationKey(msg) {
			msg = utils.Localize(localizer, msg, nil)
		}
	}
	return ellipsisMsg(msg, args[0].(string), args[1].(string))
}

func isLocalizationKey(value string) bool {
	if value == "" {
		return false
	}
	for _, r := range value {
		if r == '_' || (r >= '0' && r <= '9') || (r >= 'a' && r <= 'z') || (r >= 'A' && r <= 'Z') {
			continue
		}
		return false
	}
	return true
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
	if extra == nil {
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

func backendUrl() string {
	url := strings.TrimRight(viper.GetString("backend_url"), "/")
	
	// Validate URL format to catch common configuration errors
	if url != "" {
		// Check for malformed IP addresses (missing dots)
		if strings.Contains(url, "://") {
			parts := strings.Split(url, "://")
			if len(parts) == 2 {
				hostPart := strings.Split(parts[1], "/")[0]
				hostPart = strings.Split(hostPart, ":")[0] // Remove port
				
				// Check if it looks like an IP but is malformed
				if strings.Count(hostPart, ".") < 3 && len(hostPart) > 6 {
					// Might be a malformed IP like "164.928974" instead of "164.92.89.74"
					utils.LogMessage("error", fmt.Sprintf("⚠️ WARNING: Backend URL host '%s' looks malformed. Expected format: '164.92.89.74' not '164.928974'. Please check backend_url in config.yml", hostPart), "ussd-service")
				}
			}
		}
	}
	
	return url
}

func shouldUseBackendAuth() bool {
	return viper.GetString("backend_auth.username") != "" && viper.GetString("backend_auth.password") != ""
}

func clearBackendAuthToken() {
	backendAuthToken = ""
	backendAuthTokenType = ""
	backendAuthTokenExpiresAt = time.Time{}
}

func loginBackend() error {
	if !shouldUseBackendAuth() {
		return nil
	}
	payload := map[string]string{
		"username": viper.GetString("backend_auth.username"),
		"password": viper.GetString("backend_auth.password"),
	}
	apiResp, status, err := doBackendPost("/api/auth/login", payload, "")
	if err != nil {
		return err
	}
	if status < http.StatusOK || status >= http.StatusMultipleChoices {
		if apiResp.Message != "" {
			return fmt.Errorf(apiResp.Message)
		}
		return fmt.Errorf("backend auth failed with status %d", status)
	}
	if !apiResp.Success {
		return fmt.Errorf(apiResp.Message)
	}
	loginData := &authLoginData{}
	if err := json.Unmarshal(apiResp.Data, loginData); err != nil {
		return err
	}
	if loginData.Token == "" || loginData.TokenType == "" {
		return fmt.Errorf("backend auth token missing")
	}
	backendAuthToken = loginData.Token
	backendAuthTokenType = loginData.TokenType
	backendAuthTokenExpiresAt = time.Now().Add(30 * time.Minute)
	return nil
}

func getBackendAuthHeader() (string, error) {
	if !shouldUseBackendAuth() {
		return "", nil
	}
	if backendAuthToken == "" || time.Now().After(backendAuthTokenExpiresAt) {
		if err := loginBackend(); err != nil {
			return "", err
		}
	}
	return strings.TrimSpace(backendAuthToken), nil
}

func doBackendPost(path string, payload interface{}, authHeader string) (apiResponse, int, error) {
	url := backendUrl() + path
	
	// Validate backend URL format
	if url == "" || (!strings.HasPrefix(url, "http://") && !strings.HasPrefix(url, "https://")) {
		return apiResponse{}, 0, fmt.Errorf("invalid backend URL configuration: %s. Please check backend_url in config.yml", url)
	}
	
	body, err := json.Marshal(payload)
	if err != nil {
		return apiResponse{}, 0, fmt.Errorf("failed to marshal request body: %w", err)
	}
	request, err := http.NewRequest(http.MethodPost, url, bytes.NewReader(body))
	if err != nil {
		return apiResponse{}, 0, fmt.Errorf("failed to create HTTP request to %s: %w", url, err)
	}
	request.Header.Set("Content-Type", "application/json")
	if authHeader != "" {
		request.Header.Set("Authorization", authHeader)
	}
	client := &http.Client{Timeout: 15 * time.Second}
	resp, err := client.Do(request)
	if err != nil {
		// Provide more helpful error message for connection errors
		if strings.Contains(err.Error(), "connection refused") {
			return apiResponse{}, 0, fmt.Errorf("connection refused to backend URL: %s. Please check: 1) Backend service is running, 2) Backend URL is correct in config.yml, 3) Network/firewall allows connection. Error: %w", url, err)
		}
		if strings.Contains(err.Error(), "no such host") || strings.Contains(err.Error(), "cannot resolve") {
			return apiResponse{}, 0, fmt.Errorf("cannot resolve backend host: %s. Please check backend_url in config.yml is correct. Error: %w", url, err)
		}
		if strings.Contains(err.Error(), "timeout") {
			return apiResponse{}, 0, fmt.Errorf("timeout connecting to backend URL: %s. Backend may be slow or unreachable. Error: %w", url, err)
		}
		return apiResponse{}, 0, fmt.Errorf("failed to connect to backend URL %s: %w", url, err)
	}
	defer resp.Body.Close()
	data, err := io.ReadAll(resp.Body)
	if err != nil {
		return apiResponse{}, resp.StatusCode, fmt.Errorf("failed to read response body from %s: %w", url, err)
	}
	apiResp := apiResponse{}
	if len(data) > 0 {
		if err := json.Unmarshal(data, &apiResp); err != nil {
			apiResp.Message = strings.TrimSpace(string(data))
		}
	}
	return apiResp, resp.StatusCode, nil
}

func callBackendPost(path string, payload interface{}) (apiResponse, error) {
	for attempt := 0; attempt < 2; attempt++ {
		authHeader, err := getBackendAuthHeader()
		if err != nil {
			return apiResponse{}, err
		}
		apiResp, status, err := doBackendPost(path, payload, authHeader)
		if err != nil {
			return apiResponse{}, err
		}
		if status == http.StatusUnauthorized || status == http.StatusForbidden {
			if attempt == 0 && shouldUseBackendAuth() {
				clearBackendAuthToken()
				continue
			}
		}
		if status < http.StatusOK || status >= http.StatusMultipleChoices {
			message := strings.TrimSpace(apiResp.Message)
			if message == "" {
				message = fmt.Sprintf("backend request failed with status %d", status)
			}
			return apiResp, &backendError{status: status, message: message}
		}
		if !apiResp.Success {
			message := strings.TrimSpace(apiResp.Message)
			if message == "" {
				message = "backend request failed"
			}
			return apiResp, &backendError{status: status, message: message}
		}
		return apiResp, nil
	}
	return apiResponse{}, fmt.Errorf("backend request failed")
}

func backendErrorToUserMessage(err error) (string, bool) {
	var be *backendError
	if errors.As(err, &be) {
		if be.status == http.StatusUnauthorized || be.status == http.StatusForbidden {
			return "", true
		}
		message := strings.TrimSpace(be.message)
		if parsed := extractBackendMessage(message); parsed != "" {
			message = parsed
		}
		if message == "" {
			message = fmt.Sprintf("backend request failed with status %d", be.status)
		}
		return message, false
	}
	message := strings.TrimSpace(err.Error())
	if parsed := extractBackendMessage(message); parsed != "" {
		message = parsed
	}
	if message == "" {
		return "", false
	}
	return message, false
}

func extractBackendMessage(message string) string {
	start := strings.Index(message, "{")
	if start == -1 {
		return ""
	}
	var payload map[string]interface{}
	if err := json.Unmarshal([]byte(message[start:]), &payload); err != nil {
		return ""
	}
	if msg, ok := payload["msg"]; ok {
		return strings.TrimSpace(fmt.Sprintf("%v", msg))
	}
	if msg, ok := payload["message"]; ok {
		return strings.TrimSpace(fmt.Sprintf("%v", msg))
	}
	return ""
}

func callEfasheInitiate(payload interface{}) (*efasheInitiateResponse, error) {
	apiResp, err := callBackendPost("/api/efashe/initiate", payload)
	if err != nil {
		return nil, err
	}
	response := &efasheInitiateResponse{}
	if err := json.Unmarshal(apiResp.Data, response); err != nil {
		return nil, err
	}
	return response, nil
}

func callEfasheInitiateForOther(payload interface{}) (*efasheInitiateResponse, error) {
	apiResp, err := callBackendPost("/api/efashe/initiate-for-other", payload)
	if err != nil {
		return nil, err
	}
	response := &efasheInitiateResponse{}
	if err := json.Unmarshal(apiResp.Data, response); err != nil {
		return nil, err
	}
	return response, nil
}

func callEfasheProcess(transactionId string) error {
	_, err := callBackendPost("/api/efashe/process/"+transactionId, map[string]interface{}{})
	return err
}

func extractCharges(extra interface{}) string {
	if extra == nil {
		return "0"
	}
	if data, ok := extra.(map[string]interface{}); ok {
		for _, key := range []string{"charges", "charge", "totalCharge", "fee"} {
			if value, exists := data[key]; exists {
				return fmt.Sprintf("%v", value)
			}
		}
	}
	return "0"
}

func parseFloatValue(value interface{}) (float64, bool) {
	if value == nil {
		return 0, false
	}
	switch v := value.(type) {
	case float64:
		return v, true
	case float32:
		return float64(v), true
	case int:
		return float64(v), true
	case int64:
		return float64(v), true
	case json.Number:
		f, err := v.Float64()
		if err == nil {
			return f, true
		}
	case string:
		f, err := strconv.ParseFloat(v, 64)
		if err == nil {
			return f, true
		}
	default:
		f, err := strconv.ParseFloat(fmt.Sprintf("%v", value), 64)
		if err == nil {
			return f, true
		}
	}
	return 0, false
}

func getValidateString(validate map[string]interface{}, key string) string {
	if validate == nil {
		return ""
	}
	if value, ok := validate[key]; ok {
		return fmt.Sprintf("%v", value)
	}
	return ""
}

func getValidateFloat(validate map[string]interface{}, key string) float64 {
	if validate == nil {
		return 0
	}
	if value, ok := validate[key]; ok {
		if f, ok := parseFloatValue(value); ok {
			return f
		}
	}
	return 0
}

func getValidateExtraInfoString(validate map[string]interface{}, key string) string {
	if validate == nil {
		return ""
	}
	extraInfo, ok := validate["extraInfo"].(map[string]interface{})
	if !ok || extraInfo == nil {
		return ""
	}
	if value, ok := extraInfo[key]; ok {
		return fmt.Sprintf("%v", value)
	}
	return ""
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
		ownerName := lookupAccountName("ELECTRICITY", num)
		if ownerName != "" {
			list += fmt.Sprintf("%d) %s - %s\n", i+1, num, ownerName)
		} else {
			list += fmt.Sprintf("%d) %s\n", i+1, num)
		}
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
		appendExtraData(sessionId, extra, "electricity_owner_name", "")
		return utils.Localize(localizer, "electricity_owner_menu_no_name", map[string]interface{}{"MeterNumber": meterNumber})
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
	phone := args[3].(string)
	extra := getExtraDataMap(sessionId)
	meterNumber := getStringFromExtra(extra, "electricity_meter_number")
	ownerName := getStringFromExtra(extra, "electricity_owner_name")
	amountValue, ok := extra["electricity_amount"].(float64)
	if meterNumber == "" || !ok {
		return "fail:invalid_input"
	}
	initiatePayload := map[string]interface{}{
		"amount":                amountValue,
		"currency":              "RWF",
		"phone":                 phone,
		"customerAccountNumber": meterNumber,
		"serviceType":           "ELECTRICITY",
		"payment_mode":          "MOBILE",
		"message":               "USSD electricity payment",
	}
	response, err := callEfasheInitiate(initiatePayload)
	if err != nil {
		utils.LogMessage("error", "confirm_electricity: initiate failed: err:"+err.Error(), "ussd-service")
		msg, isAuth := backendErrorToUserMessage(err)
		if isAuth {
			return "err:system_error"
		}
		if msg == "" {
			msg = err.Error()
		}
		return "fail:" + msg
	}
	appendExtraData(sessionId, extra, "electricity_transaction_id", response.TransactionId)
	if response.CustomerAccountName != "" {
		ownerName = response.CustomerAccountName
		appendExtraData(sessionId, extra, "electricity_owner_name", response.CustomerAccountName)
	}
	providerName := getValidateString(response.EfasheValidateResponse, "svcProviderName")
	vendMin := getValidateFloat(response.EfasheValidateResponse, "vendMin")
	vendMax := getValidateFloat(response.EfasheValidateResponse, "vendMax")
	if providerName != "" {
		appendExtraData(sessionId, extra, "electricity_provider_name", providerName)
	}
	if vendMin > 0 {
		appendExtraData(sessionId, extra, "electricity_vend_min", vendMin)
	}
	if vendMax > 0 {
		appendExtraData(sessionId, extra, "electricity_vend_max", vendMax)
	}
	if (vendMin > 0 && amountValue < vendMin) || (vendMax > 0 && amountValue > vendMax) {
		return "fail:invalid_amount"
	}
	return utils.Localize(localizer, "confirm_electricity", map[string]interface{}{
		"MeterNumber":  meterNumber,
		"OwnerName":    ownerName,
		"ProviderName": providerName,
		"Amount":       formatAmount(amountValue),
	})
}

func submitElectricityPayment(args ...interface{}) string {
	sessionId := args[0].(string)
	extra := getExtraDataMap(sessionId)
	transactionId := getStringFromExtra(extra, "electricity_transaction_id")
	if transactionId == "" {
		return "fail:invalid_input"
	}
	if err := callEfasheProcess(transactionId); err != nil {
		utils.LogMessage("error", "submitElectricityPayment: process failed: err:"+err.Error(), "ussd-service")
		msg, isAuth := backendErrorToUserMessage(err)
		if isAuth {
			return "err:system_error"
		}
		if msg == "" {
			msg = err.Error()
		}
		return "fail:" + msg
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
	payerPhone := args[3].(string)
	extra := getExtraDataMap(sessionId)
	phone := getStringFromExtra(extra, "airtime_phone")
	amountValue, ok := extra["airtime_amount"].(float64)
	if phone == "" || !ok {
		return "fail:invalid_input"
	}
	var response *efasheInitiateResponse
	var err error
	if phone != payerPhone {
		initiatePayload := map[string]interface{}{
			"amount":             amountValue,
			"currency":           "RWF",
			"phone":              payerPhone,
			"anotherPhoneNumber": phone,
			"serviceType":        "AIRTIME",
			"payment_mode":       "MOBILE",
			"message":            "USSD airtime payment",
		}
		response, err = callEfasheInitiateForOther(initiatePayload)
	} else {
		initiatePayload := map[string]interface{}{
			"amount":       amountValue,
			"currency":     "RWF",
			"phone":        payerPhone,
			"serviceType":  "AIRTIME",
			"payment_mode": "MOBILE",
			"message":      "USSD airtime payment",
		}
		response, err = callEfasheInitiate(initiatePayload)
	}
	if err != nil {
		utils.LogMessage("error", "confirm_airtime: initiate failed: err:"+err.Error(), "ussd-service")
		msg, isAuth := backendErrorToUserMessage(err)
		if isAuth {
			return "err:system_error"
		}
		if msg == "" {
			msg = err.Error()
		}
		return "fail:" + msg
	}
	appendExtraData(sessionId, extra, "airtime_transaction_id", response.TransactionId)
	providerName := getValidateString(response.EfasheValidateResponse, "svcProviderName")
	vendMin := getValidateFloat(response.EfasheValidateResponse, "vendMin")
	vendMax := getValidateFloat(response.EfasheValidateResponse, "vendMax")
	if providerName != "" {
		appendExtraData(sessionId, extra, "airtime_provider_name", providerName)
	}
	if vendMin > 0 {
		appendExtraData(sessionId, extra, "airtime_vend_min", vendMin)
	}
	if vendMax > 0 {
		appendExtraData(sessionId, extra, "airtime_vend_max", vendMax)
	}
	if (vendMin > 0 && amountValue < vendMin) || (vendMax > 0 && amountValue > vendMax) {
		return "fail:invalid_amount"
	}
	return utils.Localize(localizer, "confirm_airtime", map[string]interface{}{
		"Phone":        phone,
		"ProviderName": providerName,
		"Amount":       formatAmount(amountValue),
	})
}

func submitAirtimePayment(args ...interface{}) string {
	sessionId := args[0].(string)
	extra := getExtraDataMap(sessionId)
	transactionId := getStringFromExtra(extra, "airtime_transaction_id")
	if transactionId == "" {
		return "fail:invalid_input"
	}
	if err := callEfasheProcess(transactionId); err != nil {
		utils.LogMessage("error", "submitAirtimePayment: process failed: err:"+err.Error(), "ussd-service")
		msg, isAuth := backendErrorToUserMessage(err)
		if isAuth {
			return "err:system_error"
		}
		if msg == "" {
			msg = err.Error()
		}
		return "fail:" + msg
	}
	return ""
}

type tvPeriodOption struct {
	Input    string
	Code     string
	LabelKey string
	Amount   float64
}

type tvPackageInfo struct {
	Code     string
	LabelKey string
	Periods  []tvPeriodOption
	IsAddon  bool
}

var tvPackageSelections = map[string]string{
	"1": "BASIC",
	"2": "CLASSIC",
	"3": "FRENCH",
	"4": "UNIQUE",
	"5": "SUPER",
	"6": "CHINESE_ADDON",
}

var tvPackageCatalog = map[string]tvPackageInfo{
	"BASIC": {
		Code:     "BASIC",
		LabelKey: "tv_package_basic",
		Periods: []tvPeriodOption{
			{Input: "1", Code: "DAILY", LabelKey: "tv_period_daily", Amount: 800},
			{Input: "2", Code: "WEEKLY", LabelKey: "tv_period_weekly", Amount: 2700},
			{Input: "3", Code: "MONTHLY", LabelKey: "tv_period_monthly", Amount: 8000},
		},
	},
	"CLASSIC": {
		Code:     "CLASSIC",
		LabelKey: "tv_package_classic",
		Periods: []tvPeriodOption{
			{Input: "1", Code: "DAILY", LabelKey: "tv_period_daily", Amount: 1200},
			{Input: "2", Code: "WEEKLY", LabelKey: "tv_period_weekly", Amount: 4200},
			{Input: "3", Code: "MONTHLY", LabelKey: "tv_period_monthly", Amount: 11000},
		},
	},
	"FRENCH": {
		Code:     "FRENCH",
		LabelKey: "tv_package_french",
		Periods: []tvPeriodOption{
			{Input: "1", Code: "DAILY", LabelKey: "tv_period_daily", Amount: 1500},
			{Input: "2", Code: "WEEKLY", LabelKey: "tv_period_weekly", Amount: 4700},
			{Input: "3", Code: "MONTHLY", LabelKey: "tv_period_monthly", Amount: 14000},
		},
	},
	"UNIQUE": {
		Code:     "UNIQUE",
		LabelKey: "tv_package_unique",
		Periods: []tvPeriodOption{
			{Input: "1", Code: "DAILY", LabelKey: "tv_period_daily", Amount: 1500},
			{Input: "2", Code: "WEEKLY", LabelKey: "tv_period_weekly", Amount: 4700},
			{Input: "3", Code: "MONTHLY", LabelKey: "tv_period_monthly", Amount: 14000},
		},
	},
	"SUPER": {
		Code:     "SUPER",
		LabelKey: "tv_package_super",
		Periods: []tvPeriodOption{
			{Input: "1", Code: "DAILY", LabelKey: "tv_period_daily", Amount: 2100},
			{Input: "2", Code: "WEEKLY", LabelKey: "tv_period_weekly", Amount: 7700},
			{Input: "3", Code: "MONTHLY", LabelKey: "tv_period_monthly", Amount: 20000},
		},
	},
	"CHINESE_ADDON": {
		Code:     "CHINESE_ADDON",
		LabelKey: "tv_package_chinese_addon",
		IsAddon:  true,
		Periods: []tvPeriodOption{
			{Input: "1", Code: "MONTHLY", LabelKey: "tv_period_monthly", Amount: 15000},
		},
	},
}

func getTvPackageInfo(code string) (tvPackageInfo, bool) {
	info, ok := tvPackageCatalog[code]
	return info, ok
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

func tv_amount_prompt(args ...interface{}) string {
	sessionId := args[0].(string)
	extra := getExtraDataMap(sessionId)
	packageName := getStringFromExtra(extra, "tv_package")
	if packageName == "" {
		packageName = utils.Localize(localizer, "tv_package_unknown", nil)
	}
	return utils.Localize(localizer, "tv_amount_prompt", map[string]interface{}{"Package": packageName})
}

func saveTvPackage(args ...interface{}) string {
	sessionId := args[0].(string)
	input := strings.TrimSpace(*args[2].(*string))
	packageCode, ok := tvPackageSelections[input]
	if !ok {
		return "fail:invalid_input"
	}
	packageInfo, ok := getTvPackageInfo(packageCode)
	if !ok {
		return "fail:invalid_input"
	}
	packageName := utils.Localize(localizer, packageInfo.LabelKey, nil)
	extra := getExtraDataMap(sessionId)
	appendExtraData(sessionId, extra, "tv_package_code", packageCode)
	appendExtraData(sessionId, extra, "tv_package", packageName)
	return ""
}

func tv_period_prompt(args ...interface{}) string {
	sessionId := args[0].(string)
	extra := getExtraDataMap(sessionId)
	packageCode := getStringFromExtra(extra, "tv_package_code")
	packageInfo, ok := getTvPackageInfo(packageCode)
	if !ok {
		return "fail:invalid_input"
	}
	packageName := getStringFromExtra(extra, "tv_package")
	if packageName == "" {
		packageName = utils.Localize(localizer, packageInfo.LabelKey, nil)
	}
	options := ""
	for _, period := range packageInfo.Periods {
		label := utils.Localize(localizer, period.LabelKey, nil)
		options += fmt.Sprintf("%s) %s - %s RWF\n", period.Input, label, formatAmount(period.Amount))
	}
	note := ""
	if packageInfo.IsAddon {
		note = utils.Localize(localizer, "tv_addon_requires_base", nil)
	}
	return utils.Localize(localizer, "tv_period_select", map[string]interface{}{
		"Package": packageName,
		"Options": options,
		"Note":    note,
	})
}

func saveTvPeriod(args ...interface{}) string {
	sessionId := args[0].(string)
	input := strings.TrimSpace(*args[2].(*string))
	extra := getExtraDataMap(sessionId)
	packageCode := getStringFromExtra(extra, "tv_package_code")
	packageInfo, ok := getTvPackageInfo(packageCode)
	if !ok {
		return "fail:invalid_input"
	}
	var selected *tvPeriodOption
	for _, period := range packageInfo.Periods {
		if period.Input == input {
			selected = &period
			break
		}
	}
	if selected == nil {
		return "fail:invalid_input"
	}
	appendExtraData(sessionId, extra, "tv_period", selected.Code)
	appendExtraData(sessionId, extra, "tv_amount", selected.Amount)
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
	phone := args[3].(string)
	extra := getExtraDataMap(sessionId)
	cardNumber := getStringFromExtra(extra, "tv_card_number")
	accountName := getStringFromExtra(extra, "tv_account_name")
	packageName := getStringFromExtra(extra, "tv_package")
	amountValue, ok := extra["tv_amount"].(float64)
	if cardNumber == "" || !ok || packageName == "" {
		return "fail:invalid_input"
	}
	message := "USSD TV payment"
	if packageName != "" {
		message = fmt.Sprintf("USSD TV payment - %s", packageName)
	}
	initiatePayload := map[string]interface{}{
		"amount":                amountValue,
		"currency":              "RWF",
		"phone":                 phone,
		"customerAccountNumber": cardNumber,
		"serviceType":           "TV",
		"payment_mode":          "MOBILE",
		"message":               message,
	}
	response, err := callEfasheInitiate(initiatePayload)
	if err != nil {
		utils.LogMessage("error", "confirm_tv: initiate failed: err:"+err.Error(), "ussd-service")
		msg, isAuth := backendErrorToUserMessage(err)
		if isAuth {
			return "err:system_error"
		}
		if msg == "" {
			msg = err.Error()
		}
		return "fail:" + msg
	}
	appendExtraData(sessionId, extra, "tv_transaction_id", response.TransactionId)
	if response.CustomerAccountName != "" {
		accountName = response.CustomerAccountName
		appendExtraData(sessionId, extra, "tv_account_name", response.CustomerAccountName)
	}
	providerName := getValidateString(response.EfasheValidateResponse, "svcProviderName")
	vendMin := getValidateFloat(response.EfasheValidateResponse, "vendMin")
	vendMax := getValidateFloat(response.EfasheValidateResponse, "vendMax")
	if providerName != "" {
		appendExtraData(sessionId, extra, "tv_provider_name", providerName)
	}
	if vendMin > 0 {
		appendExtraData(sessionId, extra, "tv_vend_min", vendMin)
	}
	if vendMax > 0 {
		appendExtraData(sessionId, extra, "tv_vend_max", vendMax)
	}
	if (vendMin > 0 && amountValue < vendMin) || (vendMax > 0 && amountValue > vendMax) {
		return "fail:invalid_amount"
	}
	return utils.Localize(localizer, "confirm_tv", map[string]interface{}{
		"CardNumber":   cardNumber,
		"AccountName":  accountName,
		"ProviderName": providerName,
		"Package":      packageName,
		"Amount":       formatAmount(amountValue),
	})
}

func submitTvPayment(args ...interface{}) string {
	sessionId := args[0].(string)
	extra := getExtraDataMap(sessionId)
	transactionId := getStringFromExtra(extra, "tv_transaction_id")
	if transactionId == "" {
		return "fail:invalid_input"
	}
	if err := callEfasheProcess(transactionId); err != nil {
		utils.LogMessage("error", "submitTvPayment: process failed: err:"+err.Error(), "ussd-service")
		msg, isAuth := backendErrorToUserMessage(err)
		if isAuth {
			return "err:system_error"
		}
		if msg == "" {
			msg = err.Error()
		}
		return "fail:" + msg
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
	phone := args[3].(string)
	documentId := strings.TrimSpace(*args[2].(*string))
	if documentId == "" {
		return "fail:invalid_input"
	}
	extra := getExtraDataMap(sessionId)
	appendExtraData(sessionId, extra, "rra_document_id", documentId)
	initiatePayload := map[string]interface{}{
		"phone":                 phone,
		"customerAccountNumber": documentId,
		"serviceType":           "RRA",
		"message":               "USSD RRA payment",
	}
	response, err := callEfasheInitiate(initiatePayload)
	if err != nil {
		utils.LogMessage("error", "saveRraDocument: initiate failed: err:"+err.Error(), "ussd-service")
		msg, isAuth := backendErrorToUserMessage(err)
		if isAuth {
			return "err:system_error"
		}
		if msg == "" {
			msg = err.Error()
		}
		return "fail:" + msg
	}
	if response.TransactionId != "" {
		appendExtraData(sessionId, extra, "rra_transaction_id", response.TransactionId)
	}
	amountValue := response.Amount
	if amountValue <= 0 {
		amountValue = getValidateFloat(response.EfasheValidateResponse, "vendMin")
	}
	if amountValue > 0 {
		appendExtraData(sessionId, extra, "rra_amount", amountValue)
	}
	accountName := response.CustomerAccountName
	if accountName == "" {
		accountName = getValidateString(response.EfasheValidateResponse, "customerAccountName")
	}
	accountName = strings.TrimSpace(accountName)
	if accountName != "" {
		appendExtraData(sessionId, extra, "rra_account_name", accountName)
	}
	charges := response.BesoftShareAmount
	if charges > 0 {
		appendExtraData(sessionId, extra, "rra_charges", formatAmount(charges))
	}
	taxType := getValidateExtraInfoString(response.EfasheValidateResponse, "tax_type")
	if taxType == "" {
		taxType = getValidateString(response.EfasheValidateResponse, "tax_type")
	}
	taxType = strings.TrimSpace(taxType)
	if taxType != "" {
		appendExtraData(sessionId, extra, "rra_tax_type", taxType)
	}
	if amountValue <= 0 || accountName == "" || taxType == "" {
		errMsg := getValidateString(response.EfasheValidateResponse, "trxResult")
		if errMsg == "" {
			errMsg = "rra_missing_info"
		}
		appendExtraData(sessionId, extra, "rra_error_message", errMsg)
		return "fail:" + errMsg
	}
	return ""
}

func confirm_rra(args ...interface{}) string {
	sessionId := args[0].(string)
	extra := getExtraDataMap(sessionId)
	documentId := getStringFromExtra(extra, "rra_document_id")
	amountValue, _ := parseFloatValue(extra["rra_amount"])
	charges := getStringFromExtra(extra, "rra_charges")
	accountName := strings.TrimSpace(getStringFromExtra(extra, "rra_account_name"))
	taxType := strings.TrimSpace(getStringFromExtra(extra, "rra_tax_type"))
	if documentId == "" || amountValue <= 0 || accountName == "" || taxType == "" {
		errMsg := getStringFromExtra(extra, "rra_error_message")
		if errMsg == "" {
			errMsg = "rra_missing_info"
		}
		return "fail:" + errMsg
	}
	if charges == "" {
		charges = "0"
	}
	return utils.Localize(localizer, "confirm_rra", map[string]interface{}{
		"DocumentId":  documentId,
		"AccountName": accountName,
		"TaxType":     taxType,
		"Amount":      formatAmount(amountValue),
		"Charges":     charges,
	})
}

func submitRraPayment(args ...interface{}) string {
	sessionId := args[0].(string)
	extra := getExtraDataMap(sessionId)
	transactionId := getStringFromExtra(extra, "rra_transaction_id")
	if transactionId == "" {
		return "fail:invalid_input"
	}
	if err := callEfasheProcess(transactionId); err != nil {
		utils.LogMessage("error", "submitRraPayment: process failed: err:"+err.Error(), "ussd-service")
		msg, isAuth := backendErrorToUserMessage(err)
		if isAuth {
			return "err:system_error"
		}
		if msg == "" {
			msg = err.Error()
		}
		return "fail:" + msg
	}
	return ""
}
