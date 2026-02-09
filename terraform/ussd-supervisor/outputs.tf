output "config_path" {
  description = "Generated config.yml path."
  value       = abspath("${path.module}/config.generated.yml")
}

output "supervisor_conf_path" {
  description = "Generated supervisor config path."
  value       = abspath("${path.module}/ussd-service.supervisor.conf")
}
