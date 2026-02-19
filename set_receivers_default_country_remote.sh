#!/bin/bash

# Script to set default country and country code for all receivers on REMOTE database
# Reads database credentials from application-prod.properties
# Uses SSH to connect to remote server and execute SQL
# Usage: ./set_receivers_default_country_remote.sh

set -e  # Exit on error

PROPERTIES_FILE="src/main/resources/application-prod.properties"
REMOTE_HOST="164.92.89.74"
REMOTE_USER="root"
SQL_FILE="set_receivers_default_country.sql"

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
echo "Set Default Country for All Receivers - REMOTE"
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
        echo "✅ Default country set successfully for all receivers!"
        echo "=========================================="
        echo ""
        echo "Verifying receivers have default country..."
        PGPASSWORD="$DB_PASSWORD" psql -h "$DB_HOST" -p "$DB_PORT" -U "$DB_USER" -d "$DB_NAME" -c "SELECT COUNT(*) as total, COUNT(CASE WHEN country = 'Rwanda' THEN 1 END) as rwanda_count, COUNT(CASE WHEN country_code = '+250' THEN 1 END) as country_code_count FROM receivers;"
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
    echo "✅ Default country migration completed on remote server!"
else
    echo ""
    echo "ERROR: Failed to run migration on remote server"
    exit 1
fi
