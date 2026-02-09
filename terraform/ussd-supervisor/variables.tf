variable "ssh_host" {
  description = "SSH host or IP address."
  type        = string
}

variable "ssh_user" {
  description = "SSH username."
  type        = string
}

variable "ssh_port" {
  description = "SSH port."
  type        = number
  default     = 22
}

variable "ssh_private_key_path" {
  description = "Path to SSH private key file."
  type        = string
  default     = ""
}

variable "ssh_password" {
  description = "SSH password (use only if key is not provided)."
  type        = string
  default     = ""
  sensitive   = true
}

variable "run_as_user" {
  description = "User to run the service under. Defaults to ssh_user."
  type        = string
  default     = ""
}

variable "build_enabled" {
  description = "Whether to build the Go binary locally before upload."
  type        = bool
  default     = true
}

variable "ussd_binary_path" {
  description = "Path to a prebuilt ussd-service binary (used when build_enabled is false)."
  type        = string
  default     = ""

  validation {
    condition     = var.build_enabled || var.ussd_binary_path != ""
    error_message = "When build_enabled is false, ussd_binary_path must be set."
  }
}

variable "goos" {
  description = "Target GOOS for build."
  type        = string
  default     = "linux"
}

variable "goarch" {
  description = "Target GOARCH for build."
  type        = string
  default     = "amd64"
}

variable "mode" {
  description = "Service mode."
  type        = string
  default     = "development"
}

variable "port" {
  description = "Service port."
  type        = number
  default     = 9000
}

variable "logger_path" {
  description = "Logger path."
  type        = string
  default     = "web-log.log"
}

variable "encryption_key" {
  description = "Encryption key."
  type        = string
  default     = ""
  sensitive   = true
}

variable "web_url" {
  description = "Public web URL."
  type        = string
  default     = ""
}

variable "backend_url" {
  description = "Backend URL."
  type        = string
  default     = ""
}

variable "backend_auth_username" {
  description = "Backend basic auth username."
  type        = string
  default     = ""
}

variable "backend_auth_password" {
  description = "Backend basic auth password."
  type        = string
  default     = ""
  sensitive   = true
}

variable "redis_host" {
  description = "Redis host."
  type        = string
  default     = "redis"
}

variable "redis_port" {
  description = "Redis port."
  type        = number
  default     = 6379
}

variable "redis_password" {
  description = "Redis password."
  type        = string
  default     = ""
  sensitive   = true
}

variable "redis_database" {
  description = "Redis database."
  type        = number
  default     = 0
}

variable "postgres_host" {
  description = "Postgres host."
  type        = string
  default     = "postgres"
}

variable "postgres_port" {
  description = "Postgres port."
  type        = number
  default     = 5432
}

variable "postgres_db" {
  description = "Postgres database."
  type        = string
  default     = "lottery_db"
}

variable "postgres_user" {
  description = "Postgres user."
  type        = string
  default     = "postgres"
}

variable "postgres_password" {
  description = "Postgres password."
  type        = string
  default     = ""
  sensitive   = true
}
