#!/bin/bash
# Script to set all receivers to NON-FLEXIBLE mode on local database

# Database connection details
DB_HOST="localhost"
DB_PORT="5432"
DB_NAME="pocketmoney_db"
DB_USER="postgres"
DB_PASSWORD="amazimeza12QW!@"

echo "=========================================="
echo "SETTING ALL RECEIVERS TO NON-FLEXIBLE MODE"
echo "=========================================="
echo ""

# Run the SQL script
PGPASSWORD="$DB_PASSWORD" psql -h "$DB_HOST" -p "$DB_PORT" -U "$DB_USER" -d "$DB_NAME" -f set_all_receivers_non_flexible.sql

if [ $? -eq 0 ]; then
    echo ""
    echo "=========================================="
    echo "✅ All receivers set to NON-FLEXIBLE mode!"
    echo "=========================================="
else
    echo ""
    echo "=========================================="
    echo "❌ ERROR: Failed to update receivers!"
    echo "=========================================="
    exit 1
fi

