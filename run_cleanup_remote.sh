#!/bin/bash

# Database Cleanup Script Runner for Remote Server
# This script connects to the production database on the remote server and runs the cleanup SQL

# Remote server details
REMOTE_HOST="164.92.89.74"
REMOTE_USER="root"

# Database connection details from application-prod.properties
DB_HOST="localhost"
DB_PORT="5432"
DB_NAME="pocketmoney_db"
DB_USER="postgres"
DB_PASSWORD="amazimeza12QW!@"

echo "=========================================="
echo "REMOTE DATABASE CLEANUP SCRIPT"
echo "=========================================="
echo "Remote Server: $REMOTE_HOST"
echo "WARNING: This will delete ALL data except:"
echo "  - PaymentCategories"
echo "  - ADMIN users (in auth table)"
echo "=========================================="
echo ""
read -p "Are you sure you want to proceed? (yes/no): " confirm

if [ "$confirm" != "yes" ]; then
    echo "Cleanup cancelled."
    exit 1
fi

echo ""
echo "Uploading cleanup script to remote server..."
scp cleanup_database.sql $REMOTE_USER@$REMOTE_HOST:/tmp/cleanup_database.sql

if [ $? -ne 0 ]; then
    echo "ERROR: Failed to upload script to remote server"
    exit 1
fi

echo ""
echo "Running cleanup script on remote server..."
ssh $REMOTE_USER@$REMOTE_HOST << EOF
    echo "Connecting to database on remote server..."
    PGPASSWORD="$DB_PASSWORD" psql -h "$DB_HOST" -p "$DB_PORT" -U "$DB_USER" -d "$DB_NAME" -f /tmp/cleanup_database.sql
    
    if [ \$? -eq 0 ]; then
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
    
    # Clean up the uploaded file
    rm -f /tmp/cleanup_database.sql
EOF

if [ $? -eq 0 ]; then
    echo ""
    echo "Cleanup completed on remote server!"
else
    echo ""
    echo "ERROR: Failed to run cleanup on remote server"
    exit 1
fi

