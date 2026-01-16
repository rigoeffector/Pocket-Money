#!/bin/bash
# Script to run ELECTRICITY constraint migration on local and remote databases
# Usage: ./run_electricity_migration.sh [local|remote|both]

set -e

# Configuration
REMOTE_HOST="164.92.89.74"
REMOTE_USER="root"
DB_NAME="pocketmoney_db"
DB_USER="postgres"
DB_PASSWORD="amazimeza12QW!@"

MIGRATION_FILE="add_electricity_to_efashe_constraint.sql"

# Colors for output
GREEN='\033[0;32m'
BLUE='\033[0;34m'
NC='\033[0m' # No Color

print_status() {
    echo -e "${BLUE}[INFO]${NC} $1"
}

print_success() {
    echo -e "${GREEN}[SUCCESS]${NC} $1"
}

# Run migration on local database
run_local() {
    print_status "Running migration on LOCAL database..."
    
    if [ ! -f "$MIGRATION_FILE" ]; then
        echo "Error: Migration file $MIGRATION_FILE not found!"
        exit 1
    fi
    
    # Try to detect local database connection
    if command -v psql >/dev/null 2>&1; then
        print_status "Applying migration to local database..."
        print_status "Note: You may be prompted for database password"
        # Try with PGPASSWORD first, fallback to interactive
        PGPASSWORD="${DB_PASSWORD}" psql -h localhost -U ${DB_USER} -d ${DB_NAME} -f $MIGRATION_FILE 2>/dev/null || \
        psql -U postgres -d $DB_NAME -f $MIGRATION_FILE || \
        sudo -u postgres psql -d $DB_NAME -f $MIGRATION_FILE
        print_success "Local migration completed!"
    else
        echo "psql not found. Please run the migration manually:"
        echo "psql -U postgres -d $DB_NAME -f $MIGRATION_FILE"
        echo "OR: sudo -u postgres psql -d $DB_NAME -f $MIGRATION_FILE"
    fi
}

# Run migration on remote database
run_remote() {
    print_status "Running migration on REMOTE database..."
    
    if [ ! -f "$MIGRATION_FILE" ]; then
        echo "Error: Migration file $MIGRATION_FILE not found!"
        exit 1
    fi
    
    # Copy migration file to remote server
    print_status "Copying migration file to remote server..."
    scp -o StrictHostKeyChecking=no -o UserKnownHostsFile=/dev/null \
        $MIGRATION_FILE ${REMOTE_USER}@${REMOTE_HOST}:/tmp/
    
    # Run migration on remote server
    print_status "Applying migration to remote database..."
    ssh -o StrictHostKeyChecking=no -o UserKnownHostsFile=/dev/null ${REMOTE_USER}@${REMOTE_HOST} << EOFSSH
        export PGPASSWORD='${DB_PASSWORD}'
        echo "Running $MIGRATION_FILE on remote database..."
        psql -h localhost -U ${DB_USER} -d ${DB_NAME} -f /tmp/$MIGRATION_FILE
        echo ""
        echo "Verifying constraint..."
        psql -h localhost -U ${DB_USER} -d ${DB_NAME} -c "SELECT conname, pg_get_constraintdef(oid) FROM pg_constraint WHERE conname = 'efashe_settings_service_type_check';"
EOFSSH
    
    print_success "Remote migration completed!"
}

# Main execution
case "${1:-both}" in
    "local")
        run_local
        ;;
    "remote")
        run_remote
        ;;
    "both")
        run_local
        echo ""
        run_remote
        ;;
    *)
        echo "Usage: $0 [local|remote|both]"
        echo "  local  - Run migration on local database only"
        echo "  remote - Run migration on remote database only"
        echo "  both   - Run migration on both (default)"
        exit 1
        ;;
esac

echo ""
print_success "Migration process completed!"

