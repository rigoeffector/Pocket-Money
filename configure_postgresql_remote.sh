#!/bin/bash

# PostgreSQL Remote Connection Configuration Script
# This script should be run on the database server (64.23.137.36)
# It configures PostgreSQL to allow remote connections from the application server

set -e

# Configuration
APPLICATION_SERVER_IP="164.92.89.74"
DB_USER="postgres"
DB_PASSWORD="amazimeza12QW!@"
DB_NAME="pocketmoney_db"

echo "=========================================="
echo "PostgreSQL Remote Connection Configuration"
echo "=========================================="
echo ""
echo "This script will configure PostgreSQL to allow connections from:"
echo "  - Application server: $APPLICATION_SERVER_IP"
echo ""

# Check if running as root
if [ "$EUID" -ne 0 ]; then 
    echo "Please run this script as root or with sudo"
    exit 1
fi

# Find PostgreSQL version and config directory
PG_VERSION=$(psql --version | grep -oP '\d+' | head -1)
PG_HBA_FILE="/etc/postgresql/$PG_VERSION/main/pg_hba.conf"
PG_CONF_FILE="/etc/postgresql/$PG_VERSION/main/postgresql.conf"

if [ ! -f "$PG_HBA_FILE" ]; then
    echo "Error: Could not find pg_hba.conf at $PG_HBA_FILE"
    echo "Please check PostgreSQL installation"
    exit 1
fi

echo "PostgreSQL version: $PG_VERSION"
echo "Config file: $PG_HBA_FILE"
echo ""

# Backup original files
echo "Backing up original configuration files..."
cp "$PG_HBA_FILE" "${PG_HBA_FILE}.backup.$(date +%Y%m%d_%H%M%S)"
if [ -f "$PG_CONF_FILE" ]; then
    cp "$PG_CONF_FILE" "${PG_CONF_FILE}.backup.$(date +%Y%m%d_%H%M%S)"
fi
echo "✓ Backups created"
echo ""

# Configure postgresql.conf to listen on all interfaces
echo "Configuring postgresql.conf to listen on all interfaces..."
if [ -f "$PG_CONF_FILE" ]; then
    # Check if listen_addresses is already configured
    if grep -q "^listen_addresses" "$PG_CONF_FILE"; then
        # Replace existing listen_addresses
        sed -i "s/^listen_addresses.*/listen_addresses = '*'/" "$PG_CONF_FILE"
    else
        # Add listen_addresses if not present
        echo "listen_addresses = '*'" >> "$PG_CONF_FILE"
    fi
    echo "✓ postgresql.conf updated"
else
    echo "⚠ Warning: postgresql.conf not found at $PG_CONF_FILE"
fi
echo ""

# Add pg_hba.conf entry for application server
echo "Adding pg_hba.conf entry for application server..."
# Check if entry already exists
if grep -q "$APPLICATION_SERVER_IP" "$PG_HBA_FILE"; then
    echo "⚠ Entry for $APPLICATION_SERVER_IP already exists in pg_hba.conf"
else
    # Add entry at the end (before any # comments)
    echo "# Allow connection from application server" >> "$PG_HBA_FILE"
    echo "host    $DB_NAME    $DB_USER    $APPLICATION_SERVER_IP/32    md5" >> "$PG_HBA_FILE"
    echo "host    all         $DB_USER    $APPLICATION_SERVER_IP/32    md5" >> "$PG_HBA_FILE"
    echo "✓ Added entries to pg_hba.conf"
fi
echo ""

# Set PostgreSQL password for postgres user
echo "Setting PostgreSQL password for postgres user..."
sudo -u postgres psql -c "ALTER USER postgres WITH PASSWORD '$DB_PASSWORD';" 2>/dev/null || true
echo "✓ Password configured"
echo ""

# Create database if it doesn't exist
echo "Checking if database '$DB_NAME' exists..."
DB_EXISTS=$(sudo -u postgres psql -lqt 2>/dev/null | cut -d \| -f 1 | grep -w $DB_NAME | wc -l)

if [ "$DB_EXISTS" -eq "0" ]; then
    echo "Creating database '$DB_NAME'..."
    sudo -u postgres psql -c "CREATE DATABASE $DB_NAME;" 2>/dev/null
    echo "✓ Database created"
else
    echo "✓ Database already exists"
fi
echo ""

# Reload PostgreSQL configuration
echo "Reloading PostgreSQL configuration..."
systemctl reload postgresql
if [ $? -eq 0 ]; then
    echo "✓ PostgreSQL configuration reloaded"
else
    echo "⚠ Warning: Failed to reload PostgreSQL. You may need to restart: systemctl restart postgresql"
fi
echo ""

echo "=========================================="
echo "Configuration Summary"
echo "=========================================="
echo "✓ pg_hba.conf configured for $APPLICATION_SERVER_IP"
echo "✓ postgresql.conf configured to listen on all interfaces"
echo "✓ PostgreSQL password set"
echo "✓ Database '$DB_NAME' ready"
echo ""
echo "Next steps:"
echo "1. Ensure firewall allows port 5432 from $APPLICATION_SERVER_IP"
echo "2. Test connection from application server:"
echo "   PGPASSWORD=$DB_PASSWORD psql -h 64.23.137.36 -p 5432 -U postgres -d $DB_NAME -c 'SELECT 1;'"
echo ""
echo "To allow connections from additional IPs, add entries to:"
echo "  $PG_HBA_FILE"
echo ""

