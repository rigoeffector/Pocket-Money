package main

import (
	"fmt"
	"strings"
	"shared-package/utils"
	"ussd-service/config"
	"ussd-service/routes"

	"github.com/spf13/viper"
)

func main() {
	fmt.Println("Hello - ussd-service: 9000")
	utils.InitializeViper("config", "yml")
	
	// Validate backend URL configuration
	backendURL := viper.GetString("backend_url")
	if backendURL == "" {
		panic("backend_url is not configured in config.yml. Please set backend_url (e.g., http://164.92.89.74:8383)")
	}
	
	// Check for common malformed IP addresses
	if strings.Contains(backendURL, "164.928974") {
		panic("ERROR: Malformed backend URL detected: '164.928974' should be '164.92.89.74'. Please fix backend_url in config.yml")
	}
	
	// Validate URL format
	if !strings.HasPrefix(backendURL, "http://") && !strings.HasPrefix(backendURL, "https://") {
		panic(fmt.Sprintf("ERROR: Invalid backend_url format: '%s'. Must start with http:// or https://", backendURL))
	}
	
	fmt.Printf("Backend URL configured: %s\n", backendURL)
	
	config.InitializeConfig()
	//load ussd config
	utils.InitializeViper("ussd_config", "json")

	// Convert steps into Viper keys with id as the key
	steps := viper.Get("steps").([]interface{})
	for _, step := range steps {
		step := step.(map[string]interface{}) // Cast to map
		viper.Set(fmt.Sprintf("steps.%s", step["id"]), step)
	}
	// fmt.Println(viper.Get("steps"))
	// fmt.Println(viper.Get("steps.action_ack.inputs"))
	config.ConnectDb()
	defer config.DB.Close()
	server := routes.InitRoutes()
	port := viper.GetInt("port")
	if port == 0 {
		port = 9000
	}
	if err := server.Listen(fmt.Sprintf("0.0.0.0:%d", port)); err != nil {
		panic(fmt.Sprintf("server listen failed: %v", err))
	}
}
