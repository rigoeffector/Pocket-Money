#!/usr/bin/env bash
set -euo pipefail

SUDO=""
if [[ $EUID -ne 0 ]]; then
  if sudo -n true 2>/dev/null; then
    SUDO="sudo -n"
  else
    echo "Passwordless sudo is required for this deployment (or run as root)." >&2
    exit 1
  fi
fi

command_exists() {
  command -v "$1" >/dev/null 2>&1
}

ensure_unzip() {
  if command_exists unzip; then
    return 0
  fi

  echo "unzip not found. Installing..."
  if command_exists apt-get; then
    $SUDO apt-get update -y
    $SUDO apt-get install -y unzip
  elif command_exists dnf; then
    $SUDO dnf install -y unzip
  elif command_exists yum; then
    $SUDO yum install -y unzip
  elif command_exists apk; then
    $SUDO apk add --no-cache unzip
  elif command_exists zypper; then
    $SUDO zypper install -y unzip
  else
    echo "No supported package manager found to install unzip." >&2
    exit 1
  fi
}

ensure_supervisor() {
  if command_exists supervisorctl; then
    return 0
  fi

  echo "Supervisor not found. Installing..."
  if command_exists apt-get; then
    $SUDO apt-get update -y
    $SUDO apt-get install -y supervisor
  elif command_exists dnf; then
    $SUDO dnf install -y supervisor
  elif command_exists yum; then
    $SUDO yum install -y supervisor
  elif command_exists apk; then
    $SUDO apk add --no-cache supervisor
  elif command_exists zypper; then
    $SUDO zypper install -y supervisor
  else
    echo "No supported package manager found. Install supervisor manually." >&2
    exit 1
  fi
}

start_supervisor() {
  if command_exists systemctl; then
    if ! systemctl is-active --quiet supervisor; then
      $SUDO systemctl enable --now supervisor
    fi
  else
    if ! pgrep -x supervisord >/dev/null 2>&1; then
      $SUDO supervisord
    fi
  fi
}

if [[ -z "${RUN_AS_USER:-}" ]]; then
  echo "RUN_AS_USER is required" >&2
  exit 1
fi

if [[ -z "${PORT:-}" ]]; then
  echo "PORT is required" >&2
  exit 1
fi

ensure_supervisor
start_supervisor
ensure_unzip

$SUDO install -m 0755 /tmp/ussd/ussd-service /usr/local/bin/ussd-service

$SUDO mkdir -p /app
$SUDO rm -rf /app/templates /app/locales
$SUDO unzip -o /tmp/ussd/ussd-assets.zip -d /tmp/ussd >/dev/null
$SUDO cp -R /tmp/ussd/templates /app/templates
$SUDO cp -R /tmp/ussd/locales /app/locales
$SUDO cp -f /tmp/ussd/ussd_config.json /app/ussd_config.json
$SUDO install -m 0644 /tmp/ussd/config.yml /app/config.yml

$SUDO chown -R "$RUN_AS_USER":"$RUN_AS_USER" /app

SUPERVISOR_CONF_DIR="/etc/supervisor/conf.d"
if [[ ! -d "$SUPERVISOR_CONF_DIR" ]]; then
  SUPERVISOR_CONF_DIR="/etc/supervisord.d"
fi

$SUDO install -m 0644 /tmp/ussd/ussd-service.conf "$SUPERVISOR_CONF_DIR/ussd-service.conf"

$SUDO supervisorctl reread
$SUDO supervisorctl update
$SUDO supervisorctl restart ussd-service
$SUDO supervisorctl status ussd-service

if ! $SUDO supervisorctl status ussd-service | grep -q RUNNING; then
  echo "ussd-service failed to start. Supervisor status:" >&2
  $SUDO supervisorctl status ussd-service || true
  echo "Recent stderr log:" >&2
  $SUDO tail -n 200 /var/log/ussd-service.err.log || true
  echo "Recent stdout log:" >&2
  $SUDO tail -n 200 /var/log/ussd-service.out.log || true
  exit 1
fi

HEALTH_URL="http://127.0.0.1:${PORT}/ussd/api/v1/service-status"
if command_exists curl; then
  echo "Checking health endpoint: $HEALTH_URL"
  curl -fsS "$HEALTH_URL" >/dev/null && echo "Health check OK"
elif command_exists wget; then
  echo "Checking health endpoint: $HEALTH_URL"
  wget -qO- "$HEALTH_URL" >/dev/null && echo "Health check OK"
else
  echo "curl or wget not found; skip health check. Endpoint: $HEALTH_URL"
fi

$SUDO rm -rf /tmp/ussd
