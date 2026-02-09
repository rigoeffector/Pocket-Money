terraform {
  required_version = ">= 1.3"

  required_providers {
    null = {
      source  = "hashicorp/null"
      version = ">= 3.2.1"
    }
    local = {
      source  = "hashicorp/local"
      version = ">= 2.4.0"
    }
    archive = {
      source  = "hashicorp/archive"
      version = ">= 2.5.0"
    }
  }
}

locals {
  ussd_root   = abspath("${path.module}/../../ussd/services/ussd-service")
  binary_path = var.build_enabled ? abspath("${path.module}/ussd-service") : abspath(var.ussd_binary_path)
  run_as_user = var.run_as_user != "" ? var.run_as_user : var.ssh_user
}

resource "null_resource" "build" {
  count = var.build_enabled ? 1 : 0

  triggers = {
    src_hash = data.archive_file.assets.output_base64sha256
  }

  provisioner "local-exec" {
    working_dir = local.ussd_root
    command     = "go build -o ${local.binary_path}"

    environment = {
      CGO_ENABLED = "0"
      GOOS        = var.goos
      GOARCH      = var.goarch
    }
  }
}

data "archive_file" "assets" {
  type        = "zip"
  output_path = abspath("${path.module}/ussd-assets.zip")
  source_dir  = local.ussd_root
}

resource "local_file" "config" {
  filename = abspath("${path.module}/config.generated.yml")
  content = templatefile("${path.module}/templates/config.yml.tftpl", {
    mode                    = var.mode
    port                    = var.port
    logger_path             = var.logger_path
    encryption_key          = var.encryption_key
    web_url                 = var.web_url
    backend_url             = var.backend_url
    backend_auth_username   = var.backend_auth_username
    backend_auth_password   = var.backend_auth_password
    redis_host              = var.redis_host
    redis_port              = var.redis_port
    redis_password          = var.redis_password
    redis_database          = var.redis_database
    postgres_host           = var.postgres_host
    postgres_port           = var.postgres_port
    postgres_db             = var.postgres_db
    postgres_user           = var.postgres_user
    postgres_password       = var.postgres_password
  })
}

resource "local_file" "supervisor" {
  filename = abspath("${path.module}/ussd-service.supervisor.conf")
  content = templatefile("${path.module}/templates/ussd-service.conf.tftpl", {
    run_as_user = local.run_as_user
  })
}

resource "null_resource" "deploy" {
  triggers = {
    src_hash = data.archive_file.assets.output_base64sha256
  }

  lifecycle {
    replace_triggered_by = [
      null_resource.build,
      local_file.config,
      local_file.supervisor,
    ]
  }

  connection {
    type        = "ssh"
    host        = var.ssh_host
    user        = var.ssh_user
    port        = var.ssh_port
    private_key = var.ssh_private_key_path != "" ? file(var.ssh_private_key_path) : null
    password    = var.ssh_password != "" ? var.ssh_password : null
    timeout     = "5m"
  }

  provisioner "remote-exec" {
    inline = [
      "mkdir -p /tmp/ussd",
    ]
  }

  provisioner "file" {
    source      = local.binary_path
    destination = "/tmp/ussd/ussd-service"
  }

  provisioner "file" {
    source      = local_file.config.filename
    destination = "/tmp/ussd/config.generated.yml"
  }

  provisioner "file" {
    source      = local_file.supervisor.filename
    destination = "/tmp/ussd/ussd-service.conf"
  }

  provisioner "file" {
    source      = data.archive_file.assets.output_path
    destination = "/tmp/ussd/ussd-assets.zip"
  }

  provisioner "file" {
    source      = abspath("${path.module}/scripts/remote_setup.sh")
    destination = "/tmp/ussd/remote_setup.sh"
  }

  provisioner "remote-exec" {
    inline = [
      "RUN_AS_USER='${local.run_as_user}' PORT='${var.port}' bash /tmp/ussd/remote_setup.sh",
    ]
  }

  depends_on = [
    null_resource.build,
    local_file.config,
    local_file.supervisor,
  ]
}
