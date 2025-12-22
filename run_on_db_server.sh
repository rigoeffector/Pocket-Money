#!/bin/bash
# Copy and paste this entire script into your SSH session on 64.23.137.36
# Or save it and run: bash run_on_db_server.sh

APPLICATION_SERVER_IP="164.92.89.74"
DB_USER="postgres"
DB_PASSWORD="amazimeza12QW!@"
DB_NAME="pocketmoney_db"

echo "=========================================="
echo "PostgreSQL Remote Connection Configuration"
echo "=========================================="
echo ""

# Find PostgreSQL version
PG_VERSION=$(psql --version 2>/dev/null | grep -oP '\d+' | head -1)
if [ -z "$PG_VERSION" ]; then
    echo "❌ PostgreSQL is not installed"
    exit 1
fi

PG_HBA_FILE="/etc/postgresql/$PG_VERSION/main/pg_hba.conf"
PG_CONF_FILE="/etc/postgresql/$PG_VERSION/main/postgresql.conf"

echo "PostgreSQL version: $PG_VERSION"
echo ""

# Backup files
cp "$PG_HBA_FILE" "${PG_HBA_FILE}.backup.$(date +%Y%m%d_%H%M%S)"
[ -f "$PG_CONF_FILE" ] && cp "$PG_CONF_FILE" "${PG_CONF_FILE}.backup.$(date +%Y%m%d_%H%M%S)"
echo "✓ Backups created"
echo ""

# Configure listen_addresses
if [ -f "$PG_CONF_FILE" ]; then
    if grep -q "^listen_addresses" "$PG_CONF_FILE"; then
        sed -i "s/^listen_addresses.*/listen_addresses = '*'/" "$PG_CONF_FILE"
    else
        echo "listen_addresses = '*'" >> "$PG_CONF_FILE"
    fi
    echo "✓ postgresql.conf updated: listen_addresses = '*'"
fi
echo ""

# Add pg_hba.conf entries
if ! grep -q "$APPLICATION_SERVER_IP" "$PG_HBA_FILE"; then
    echo "" >> "$PG_HBA_FILE"
    echo "# Allow connection from application server" >> "$PG_HBA_FILE"
    echo "host    $DB_NAME    $DB_USER    $APPLICATION_SERVER_IP/32    md5" >> "$PG_HBA_FILE"
    echo "host    all         $DB_USER    $APPLICATION_SERVER_IP/32    md5" >> "$PG_HBA_FILE"
    echo "✓ Added pg_hba.conf entries for $APPLICATION_SERVER_IP"
else
    echo "✓ Entry for $APPLICATION_SERVER_IP already exists"
fi
echo ""

# Set password
sudo -u postgres psql -c "ALTER USER postgres WITH PASSWORD '$DB_PASSWORD';" 2>/dev/null
echo "✓ Password configured"
echo ""

# Create database
DB_EXISTS=$(sudo -u postgres psql -lqt 2>/dev/null | cut -d \| -f 1 | grep -w $DB_NAME | wc -l)
if [ "$DB_EXISTS" -eq "0" ]; then
    sudo -u postgres psql -c "CREATE DATABASE $DB_NAME;" 2>/dev/null
    echo "✓ Database '$DB_NAME' created"
else
    echo "✓ Database '$DB_NAME' already exists"
fi
echo ""

# Reload PostgreSQL
systemctl reload postgresql 2>/dev/null || systemctl restart postgresql
echo "✓ PostgreSQL reloaded"
echo ""

echo "=========================================="
echo "✓ Configuration Complete!"
echo "=========================================="
echo ""
echo "Test connection from application server with:"
echo "PGPASSWORD='$DB_PASSWORD' psql -h 64.23.137.36 -p 5432 -U postgres -d $DB_NAME -c 'SELECT 1;'"
echo ""

