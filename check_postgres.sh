#!/bin/bash
# PostgreSQL Diagnostic Script - Run this on the remote server

echo "=== Checking PostgreSQL Installation ==="
which psql || echo "PostgreSQL client not found"

echo ""
echo "=== Checking PostgreSQL Packages ==="
dpkg -l | grep postgresql | head -5

echo ""
echo "=== Checking PostgreSQL Service Status ==="
systemctl status postgresql 2>&1 | head -10

echo ""
echo "=== Checking if PostgreSQL is listening on port 5432 ==="
netstat -tlnp 2>/dev/null | grep 5432 || ss -tlnp 2>/dev/null | grep 5432 || echo "Port 5432 not found"

echo ""
echo "=== Checking PostgreSQL Databases ==="
sudo -u postgres psql -l 2>&1 | head -20

echo ""
echo "=== Testing Database Connection (as postgres user) ==="
sudo -u postgres psql -d postgres -c "SELECT 1;" 2>&1

echo ""
echo "=== Testing Connection with Password ==="
PGPASSWORD='amazimeza12QW!@' psql -h localhost -U postgres -d postgres -c "SELECT 1;" 2>&1

echo ""
echo "=== Checking if pocketmoney_db exists ==="
sudo -u postgres psql -l | grep pocketmoney_db || echo "Database pocketmoney_db not found"

echo ""
echo "=== PostgreSQL Configuration (pg_hba.conf) ==="
if [ -d /etc/postgresql ]; then
    cat /etc/postgresql/*/main/pg_hba.conf | grep -v '^#' | grep -v '^$' | head -10
else
    echo "PostgreSQL configuration directory not found"
fi

echo ""
echo "=== PostgreSQL listen_addresses ==="
if [ -d /etc/postgresql ]; then
    cat /etc/postgresql/*/main/postgresql.conf | grep listen_addresses | grep -v '^#' | head -5
else
    echo "PostgreSQL configuration directory not found"
fi

