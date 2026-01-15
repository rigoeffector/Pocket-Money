#!/bin/bash

# Script to add EFASHE category to REMOTE database
# Reads database credentials from application-prod.properties
# Uses SSH to connect to remote server and execute SQL
# Usage: ./add_efashe_category_remote.sh

set -e  # Exit on error

PROPERTIES_FILE="src/main/resources/application-prod.properties"
REMOTE_HOST="164.92.89.74"
REMOTE_USER="root"
SQL_FILE="add_efashe_category.sql"

if [ ! -f "$PROPERTIES_FILE" ]; then
    echo "Error: $PROPERTIES_FILE not found!"
    exit 1
fi

if [ ! -f "$SQL_FILE" ]; then
    echo "Error: $SQL_FILE not found in current directory."
    exit 1
fi

# Extract database credentials from properties file
DB_URL=$(grep "^spring.datasource.url=" "$PROPERTIES_FILE" | cut -d'=' -f2- | tr -d ' ')
DB_USER=$(grep "^spring.datasource.username=" "$PROPERTIES_FILE" | cut -d'=' -f2- | tr -d ' ')
DB_PASSWORD=$(grep "^spring.datasource.password=" "$PROPERTIES_FILE" | cut -d'=' -f2- | tr -d ' ')

# Parse JDBC URL: jdbc:postgresql://localhost:5432/database
if [[ $DB_URL =~ jdbc:postgresql://([^:/]+):?([0-9]*)/(.+) ]]; then
    DB_HOST="${BASH_REMATCH[1]}"
    DB_PORT="${BASH_REMATCH[2]:-5432}"
    DB_NAME="${BASH_REMATCH[3]}"
else
    echo "Error: Could not parse database URL from properties file"
    exit 1
fi

echo "=========================================="
echo "Add EFASHE Category to Payment Categories - REMOTE"
echo "=========================================="
echo "Remote Server: $REMOTE_HOST"
echo "Database: $DB_NAME (on $DB_HOST:$DB_PORT)"
echo "User: $DB_USER"
echo "=========================================="
echo ""

echo "Uploading SQL file to remote server..."
scp -o StrictHostKeyChecking=no -o UserKnownHostsFile=/dev/null \
    "$SQL_FILE" "$REMOTE_USER@$REMOTE_HOST:/tmp/$SQL_FILE"

if [ $? -ne 0 ]; then
    echo "ERROR: Failed to upload script to remote server"
    exit 1
fi

echo ""
echo "Running migration on remote server..."
ssh -o StrictHostKeyChecking=no -o UserKnownHostsFile=/dev/null $REMOTE_USER@$REMOTE_HOST << EOF
    echo "Connecting to database on remote server..."
    PGPASSWORD="$DB_PASSWORD" psql -h "$DB_HOST" -p "$DB_PORT" -U "$DB_USER" -d "$DB_NAME" -f /tmp/$SQL_FILE
    
    if [ \$? -eq 0 ]; then
        echo ""
        echo "=========================================="
        echo "✅ EFASHE category added successfully!"
        echo "=========================================="
        echo ""
        echo "Verifying EFASHE category exists..."
        PGPASSWORD="$DB_PASSWORD" psql -h "$DB_HOST" -p "$DB_PORT" -U "$DB_USER" -d "$DB_NAME" -c "SELECT name, description, is_active FROM payment_categories WHERE name = 'EFASHE';"
    else
        echo ""
        echo "=========================================="
        echo "ERROR: Migration failed!"
        echo "=========================================="
        exit 1
    fi
    
    # Clean up the uploaded file
    rm -f /tmp/$SQL_FILE
EOF

if [ $? -eq 0 ]; then
    echo ""
    echo "✅ EFASHE category migration completed on remote server!"
else
    echo ""
    echo "ERROR: Failed to run migration on remote server"
    exit 1
fi

