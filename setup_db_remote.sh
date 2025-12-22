#!/bin/bash
# Script to check and create the database on remote server

echo "=== Checking if pocketmoney_db exists ==="
sudo -u postgres psql -l | grep pocketmoney_db

if [ $? -ne 0 ]; then
    echo "Database pocketmoney_db does not exist. Creating it..."
    sudo -u postgres psql -c "CREATE DATABASE pocketmoney_db;"
    if [ $? -eq 0 ]; then
        echo "✓ Database pocketmoney_db created successfully"
    else
        echo "✗ Failed to create database"
        exit 1
    fi
else
    echo "✓ Database pocketmoney_db already exists"
fi

echo ""
echo "=== Setting postgres user password ==="
sudo -u postgres psql -c "ALTER USER postgres WITH PASSWORD 'amazimeza12QW!@';"

echo ""
echo "=== Testing connection to pocketmoney_db ==="
PGPASSWORD='amazimeza12QW!@' psql -h localhost -U postgres -d pocketmoney_db -c 'SELECT 1;'

echo ""
echo "=== Listing all databases ==="
sudo -u postgres psql -l

