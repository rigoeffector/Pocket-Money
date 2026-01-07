#!/bin/bash

# Script to reset submerchant balances on REMOTE database
# Reads database configuration from application-prod.properties
# Usage: ./reset_submerchant_balances_remote.sh

set -e  # Exit on error

echo "=========================================="
echo "Reset Submerchant Balances - REMOTE"
echo "=========================================="
echo ""

# Properties file path
PROPERTIES_FILE="src/main/resources/application-prod.properties"

if [ ! -f "$PROPERTIES_FILE" ]; then
    echo "Error: $PROPERTIES_FILE not found!"
    exit 1
fi

# Extract database configuration from properties file
DB_URL=$(grep "^spring.datasource.url=" "$PROPERTIES_FILE" | cut -d'=' -f2- | tr -d ' ')
DB_USER=$(grep "^spring.datasource.username=" "$PROPERTIES_FILE" | cut -d'=' -f2- | tr -d ' ')
DB_PASSWORD=$(grep "^spring.datasource.password=" "$PROPERTIES_FILE" | cut -d'=' -f2- | tr -d ' ')

# Parse JDBC URL: jdbc:postgresql://host:port/database
if [[ $DB_URL =~ jdbc:postgresql://([^:/]+):?([0-9]*)/(.+) ]]; then
    DB_HOST="${BASH_REMATCH[1]}"
    DB_PORT="${BASH_REMATCH[2]:-5432}"
    DB_NAME="${BASH_REMATCH[3]}"
else
    echo "Error: Could not parse database URL from properties file"
    exit 1
fi

echo "Database Configuration (from $PROPERTIES_FILE):"
echo "  Host: $DB_HOST"
echo "  Port: $DB_PORT"
echo "  Database: $DB_NAME"
echo "  User: $DB_USER"
echo ""

# Check if psql is available
if ! command -v psql &> /dev/null; then
    echo "Error: psql command not found. Please install PostgreSQL client."
    exit 1
fi

# Check if SQL file exists
if [ ! -f "reset_submerchant_balances.sql" ]; then
    echo "Error: reset_submerchant_balances.sql file not found in current directory."
    exit 1
fi

echo "WARNING: You are about to modify the REMOTE/PRODUCTION database!"
echo "Press Ctrl+C to cancel, or Enter to continue..."
read -r

echo ""
echo "Executing migration..."
echo ""

# Run the migration
PGPASSWORD="$DB_PASSWORD" psql -h "$DB_HOST" -p "$DB_PORT" -U "$DB_USER" -d "$DB_NAME" -f reset_submerchant_balances.sql

echo ""
echo "=========================================="
echo "Migration completed successfully!"
echo "=========================================="

