# USSD Supervisor Deployment (Terraform)

This Terraform module builds (optional) and deploys the USSD service to a bare‑metal server over SSH, installs Supervisor if missing, writes config files, and verifies the health endpoint.

## What it does
- Builds `ussd-service` locally (optional)
- Uploads the binary, configs, templates, locales, and USSD flow file
- Installs and configures Supervisor on the remote host
- Restarts the service and checks `/ussd/api/v1/service-status`

## Usage
From this folder, create a `terraform.tfvars` and run `terraform init` then `terraform apply`.

### Required variables
- `ssh_host`
- `ssh_user`

### Optional
- `ssh_private_key_path` or `ssh_password`
- `run_as_user`
- `build_enabled`, `ussd_binary_path`, `goos`, `goarch`
- All config values in [variables.tf](variables.tf)

## Notes
- The health endpoint already exists as `/ussd/api/v1/service-status`.
- If you set `build_enabled = false`, you must provide `ussd_binary_path`.
