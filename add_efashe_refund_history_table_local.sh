#!/bin/bash

# Script to apply refund history table migration locally

DB_HOST="localhost"
DB_PORT="5432"
DB_NAME="pocketmoney_db"
DB_USER="postgres"

echo "Applying refund history table migration to local database..."

psql -h $DB_HOST -p $DB_PORT -U $DB_USER -d $DB_NAME -f add_efashe_refund_history_table.sql

if [ $? -eq 0 ]; then
    echo "✅ Refund history table migration applied successfully to local database"
else
    echo "❌ Error applying refund history table migration to local database"
    exit 1
fi
