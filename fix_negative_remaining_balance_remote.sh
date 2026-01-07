#!/bin/bash

# Script to fix negative remaining balance on REMOTE database
# This script connects to the remote server via SSH and runs the migration
# Usage: ./fix_negative_remaining_balance_remote.sh

set -e  # Exit on error

# Remote server details
REMOTE_HOST="164.92.89.74"
REMOTE_USER="root"

# Database connection details from application-prod.properties
DB_HOST="localhost"  # Database is on the remote server, so localhost from remote server perspective
DB_PORT="5432"
DB_NAME="pocketmoney_db"
DB_USER="postgres"
DB_PASSWORD="amazimeza12QW!@"

echo "=========================================="
echo "Fix Negative Remaining Balance - REMOTE"
echo "=========================================="
echo "Remote Server: $REMOTE_HOST"
echo "Database: $DB_NAME"
echo "WARNING: This will fix all negative remaining balance values"
echo "=========================================="
echo ""
read -p "Are you sure you want to proceed? (yes/no): " confirm

if [ "$confirm" != "yes" ]; then
    echo "Migration cancelled."
    exit 1
fi

# Check if SQL file exists
if [ ! -f "fix_negative_remaining_balance.sql" ]; then
    echo "Error: fix_negative_remaining_balance.sql file not found in current directory."
    exit 1
fi

echo ""
echo "Uploading migration script to remote server..."
scp fix_negative_remaining_balance.sql $REMOTE_USER@$REMOTE_HOST:/tmp/fix_negative_remaining_balance.sql

if [ $? -ne 0 ]; then
    echo "ERROR: Failed to upload script to remote server"
    exit 1
fi

echo ""
echo "Running migration on remote server..."
ssh $REMOTE_USER@$REMOTE_HOST << EOF
    echo "Connecting to database on remote server..."
    PGPASSWORD="$DB_PASSWORD" psql -h "$DB_HOST" -p "$DB_PORT" -U "$DB_USER" -d "$DB_NAME" -f /tmp/fix_negative_remaining_balance.sql
    
    if [ \$? -eq 0 ]; then
        echo ""
        echo "=========================================="
        echo "Migration completed successfully!"
        echo "=========================================="
    else
        echo ""
        echo "=========================================="
        echo "ERROR: Migration failed!"
        echo "=========================================="
        exit 1
    fi
    
    # Clean up the uploaded file
    rm -f /tmp/fix_negative_remaining_balance.sql
EOF

if [ $? -eq 0 ]; then
    echo ""
    echo "Migration completed on remote server!"
else
    echo ""
    echo "ERROR: Failed to run migration on remote server"
    exit 1
fi

