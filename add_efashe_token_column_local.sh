#!/bin/bash

# Script to add token column to efashe_transactions table (LOCAL)
# Usage: ./add_efashe_token_column_local.sh

echo "========================================="
echo "Adding token column to efashe_transactions (LOCAL)"
echo "========================================="

# Database connection details (LOCAL)
DB_HOST="localhost"
DB_PORT="5432"
DB_NAME="pocketmoney_db"
DB_USER="postgres"

# Read password from user
echo "Enter PostgreSQL password for user '$DB_USER':"
read -s DB_PASSWORD

# Execute SQL migration
PGPASSWORD=$DB_PASSWORD psql -h $DB_HOST -p $DB_PORT -U $DB_USER -d $DB_NAME -f add_efashe_token_column.sql

if [ $? -eq 0 ]; then
    echo "✅ Migration completed successfully"
else
    echo "❌ Migration failed"
    exit 1
fi
