#!/bin/bash

# Script to create EFASHE settings table on local database
# Reads database credentials from application-dev.properties

PROPERTIES_FILE="src/main/resources/application-dev.properties"

if [ ! -f "$PROPERTIES_FILE" ]; then
    echo "Error: $PROPERTIES_FILE not found"
    exit 1
fi

# Extract database credentials from properties file
DB_URL=$(grep "^spring.datasource.url=" "$PROPERTIES_FILE" | cut -d'=' -f2- | sed 's/jdbc:postgresql:\/\///')
DB_USER=$(grep "^spring.datasource.username=" "$PROPERTIES_FILE" | cut -d'=' -f2-)
DB_PASSWORD=$(grep "^spring.datasource.password=" "$PROPERTIES_FILE" | cut -d'=' -f2-)

# Extract host and database name from URL
DB_HOST=$(echo "$DB_URL" | cut -d'/' -f1 | cut -d':' -f1)
DB_PORT=$(echo "$DB_URL" | cut -d'/' -f1 | cut -d':' -f2)
DB_NAME=$(echo "$DB_URL" | cut -d'/' -f2)

echo "Creating EFASHE settings table on local database..."
echo "Host: $DB_HOST"
echo "Port: ${DB_PORT:-5432}"
echo "Database: $DB_NAME"
echo "User: $DB_USER"

# Check if psql is available
if ! command -v psql &> /dev/null; then
    echo "Error: psql command not found. Please install PostgreSQL client."
    exit 1
fi

echo "Executing migration..."
echo ""

# Run the migration
PGPASSWORD="$DB_PASSWORD" psql -h "$DB_HOST" -p "$DB_PORT" -U "$DB_USER" -d "$DB_NAME" -f "$SQL_FILE"

echo ""
echo "=========================================="
echo "Migration completed successfully!"
echo "=========================================="

