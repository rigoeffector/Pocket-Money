#!/bin/bash
# Quick fix script to add is_flexible column on local database

DB_HOST="localhost"
DB_PORT="5432"
DB_NAME="pocketmoney_db"
DB_USER="postgres"
DB_PASSWORD="amazimeza12QW!@"

echo "=========================================="
echo "QUICK FIX: Adding is_flexible column"
echo "=========================================="
echo ""

PGPASSWORD="$DB_PASSWORD" psql -h "$DB_HOST" -p "$DB_PORT" -U "$DB_USER" -d "$DB_NAME" -f quick_fix_add_is_flexible.sql

if [ $? -eq 0 ]; then
    echo ""
    echo "✅ Quick fix applied!"
else
    echo ""
    echo "❌ ERROR: Failed to apply fix!"
    exit 1
fi

