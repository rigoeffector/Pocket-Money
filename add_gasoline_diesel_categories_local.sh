#!/bin/bash

# Script to add GASOLINE and DIESEL categories to payment_categories table (local database)
# Uses credentials from application-dev.properties

echo "=========================================="
echo "Adding GASOLINE and DIESEL Categories"
echo "Database: Local (localhost:5432/pocketmoney_db)"
echo "=========================================="

# Database credentials from application-dev.properties
DB_HOST="localhost"
DB_PORT="5432"
DB_NAME="pocketmoney_db"
DB_USER="postgres"
DB_PASSWORD="amazimeza12QW!@"

# Export password for psql
export PGPASSWORD="$DB_PASSWORD"

# Run the SQL script
psql -h "$DB_HOST" -p "$DB_PORT" -U "$DB_USER" -d "$DB_NAME" -f add_gasoline_diesel_categories.sql

# Check exit status
if [ $? -eq 0 ]; then
    echo ""
    echo "✅ GASOLINE and DIESEL categories added successfully"
else
    echo ""
    echo "❌ Error adding categories"
    exit 1
fi

# Unset password
unset PGPASSWORD

echo "=========================================="
echo "Update Complete"
echo "=========================================="
