#!/bin/bash

# Database Cleanup Script Runner
# This script connects to the production database and runs the cleanup SQL

# Database connection details from application-prod.properties
DB_HOST="localhost"
DB_PORT="5432"
DB_NAME="pocketmoney_db"
DB_USER="postgres"
DB_PASSWORD="amazimeza12QW!@"

echo "=========================================="
echo "DATABASE CLEANUP SCRIPT"
echo "=========================================="
echo "WARNING: This will delete ALL data except:"
echo "  - Users table (all users - balances reset to 0.0)"
echo "  - Receivers table (all receivers - balances reset to 0.0)"
echo "  - ADMIN users (in auth table)"
echo ""
echo "All user and receiver balances will be reset to 0.0"
echo "=========================================="
echo ""
read -p "Are you sure you want to proceed? (yes/no): " confirm

if [ "$confirm" != "yes" ]; then
    echo "Cleanup cancelled."
    exit 1
fi

echo ""
echo "Connecting to database..."
echo "Host: $DB_HOST"
echo "Database: $DB_NAME"
echo ""

# Run the cleanup SQL script
PGPASSWORD="$DB_PASSWORD" psql -h "$DB_HOST" -p "$DB_PORT" -U "$DB_USER" -d "$DB_NAME" -f cleanup_database.sql

if [ $? -eq 0 ]; then
    echo ""
    echo "=========================================="
    echo "Database cleanup completed successfully!"
    echo "=========================================="
else
    echo ""
    echo "=========================================="
    echo "ERROR: Database cleanup failed!"
    echo "=========================================="
    exit 1
fi

