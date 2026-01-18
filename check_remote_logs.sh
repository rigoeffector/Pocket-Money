#!/bin/bash

# Script to check remote server logs (Database and Spring Application logs)
# Usage: ./check_remote_logs.sh [options]

set -e

# Configuration (from deploy.sh)
SERVER_HOST="164.92.89.74"
SERVER_USER="root"
SERVER_APP_DIR="/opt/apps"
DB_USER="postgres"
DB_PASSWORD="amazimeza12QW!@"
DB_NAME="pocketmoney_db"

# Colors for output
RED='\033[0;31m'
GREEN='\033[0;32m'
YELLOW='\033[1;33m'
BLUE='\033[0;34m'
CYAN='\033[0;36m'
NC='\033[0m' # No Color

# Default values
LINES=100
LOG_TYPE="all"
SEARCH_TERM=""
FOLLOW=false

# Print colored output
print_header() {
    echo -e "${BLUE}========================================${NC}"
    echo -e "${BLUE}  $1${NC}"
    echo -e "${BLUE}========================================${NC}"
}

print_success() {
    echo -e "${GREEN}[✓]${NC} $1"
}

print_error() {
    echo -e "${RED}[✗]${NC} $1"
}

print_info() {
    echo -e "${CYAN}[i]${NC} $1"
}

print_warning() {
    echo -e "${YELLOW}[!]${NC} $1"
}

# Show usage
show_usage() {
    echo "Usage: $0 [OPTIONS]"
    echo ""
    echo "Options:"
    echo "  -t, --type TYPE          Log type: 'app' (Spring), 'db' (PostgreSQL), or 'all' (default)"
    echo "  -n, --lines N            Number of lines to show (default: 100)"
    echo "  -s, --search TERM        Search for specific term in logs"
    echo "  -f, --follow             Follow log output (like tail -f)"
    echo "  -h, --help               Show this help message"
    echo ""
    echo "Examples:"
    echo "  $0                       # Show last 100 lines of all logs"
    echo "  $0 -t app -n 200         # Show last 200 lines of Spring logs"
    echo "  $0 -t db -s ERROR        # Search for 'ERROR' in PostgreSQL logs"
    echo "  $0 -f -t app             # Follow Spring application logs"
    echo ""
}

# Parse command line arguments
while [[ $# -gt 0 ]]; do
    case $1 in
        -t|--type)
            LOG_TYPE="$2"
            shift 2
            ;;
        -n|--lines)
            LINES="$2"
            shift 2
            ;;
        -s|--search)
            SEARCH_TERM="$2"
            shift 2
            ;;
        -f|--follow)
            FOLLOW=true
            shift
            ;;
        -h|--help)
            show_usage
            exit 0
            ;;
        *)
            print_error "Unknown option: $1"
            show_usage
            exit 1
            ;;
    esac
done

# Validate log type
if [[ "$LOG_TYPE" != "app" && "$LOG_TYPE" != "db" && "$LOG_TYPE" != "all" ]]; then
    print_error "Invalid log type: $LOG_TYPE (must be 'app', 'db', or 'all')"
    exit 1
fi

print_header "Remote Server Log Checker"
print_info "Server: $SERVER_USER@$SERVER_HOST"
print_info "Log Type: $LOG_TYPE"
print_info "Lines: $LINES"
[[ -n "$SEARCH_TERM" ]] && print_info "Search Term: $SEARCH_TERM"
[[ "$FOLLOW" == true ]] && print_info "Follow Mode: Enabled"
echo ""

# Function to check Spring Application logs
check_app_logs() {
    print_header "Spring Application Logs"
    print_info "Log file: $SERVER_APP_DIR/output.log"
    echo ""
    
    if [[ "$FOLLOW" == true ]]; then
        print_info "Following log output (Press Ctrl+C to stop)..."
        ssh -o StrictHostKeyChecking=no -o UserKnownHostsFile=/dev/null $SERVER_USER@$SERVER_HOST "
            cd $SERVER_APP_DIR
            if [ -f output.log ]; then
                tail -f output.log
            else
                echo 'Log file not found: output.log'
            fi
        "
    else
        ssh -o StrictHostKeyChecking=no -o UserKnownHostsFile=/dev/null $SERVER_USER@$SERVER_HOST "
            cd $SERVER_APP_DIR
            if [ -f output.log ]; then
                if [ -n '$SEARCH_TERM' ]; then
                    grep -i '$SEARCH_TERM' output.log | tail -n $LINES
                else
                    tail -n $LINES output.log
                fi
            else
                echo 'Log file not found: output.log'
            fi
        "
    fi
    echo ""
}

# Function to check PostgreSQL Database logs
check_db_logs() {
    print_header "PostgreSQL Database Logs"
    print_info "Database: $DB_NAME"
    echo ""
    
    ssh -o StrictHostKeyChecking=no -o UserKnownHostsFile=/dev/null $SERVER_USER@$SERVER_HOST "
        # Find PostgreSQL log directory
        PG_VERSION=\$(psql --version 2>/dev/null | grep -oP '\\d+' | head -1 || echo '')
        PG_LOG_DIR=\"/var/log/postgresql\"
        
        # Try different log locations
        if [ -d /var/log/postgresql ]; then
            PG_LOG_DIR=\"/var/log/postgresql\"
        elif [ -d /var/lib/pgsql/\$PG_VERSION/data/log ]; then
            PG_LOG_DIR=\"/var/lib/pgsql/\$PG_VERSION/data/log\"
        elif [ -d /usr/local/var/log ]; then
            PG_LOG_DIR=\"/usr/local/var/log\"
        else
            echo 'PostgreSQL log directory not found in common locations'
            echo 'Trying to find log files...'
            find /var/log -name '*postgresql*' -type f 2>/dev/null | head -5
            exit 1
        fi
        
        echo \"PostgreSQL log directory: \$PG_LOG_DIR\"
        
        # Find the most recent log file
        if [ -d \$PG_LOG_DIR ]; then
            LATEST_LOG=\$(ls -t \$PG_LOG_DIR/*.log 2>/dev/null | head -1)
            if [ -z \"\$LATEST_LOG\" ]; then
                LATEST_LOG=\$(find \$PG_LOG_DIR -name '*.log' -type f 2>/dev/null | sort -r | head -1)
            fi
            
            if [ -n \"\$LATEST_LOG\" ] && [ -f \"\$LATEST_LOG\" ]; then
                echo \"Latest log file: \$LATEST_LOG\"
                echo \"\"
                if [ -n '$SEARCH_TERM' ]; then
                    grep -i '$SEARCH_TERM' \"\$LATEST_LOG\" | tail -n $LINES
                else
                    tail -n $LINES \"\$LATEST_LOG\"
                fi
            else
                echo 'No PostgreSQL log files found in: \$PG_LOG_DIR'
                echo ''
                echo 'Listing all files in log directory:'
                ls -lah \$PG_LOG_DIR/ 2>/dev/null || echo 'Cannot list log directory'
            fi
        else
            echo 'PostgreSQL log directory not found: \$PG_LOG_DIR'
        fi
    " || {
        print_warning "Could not read PostgreSQL logs directly"
        print_info "Trying alternative method via PostgreSQL queries..."
        
        # Alternative: Check PostgreSQL activity via psql
        ssh -o StrictHostKeyChecking=no -o UserKnownHostsFile=/dev/null $SERVER_USER@$SERVER_HOST "
            export PGPASSWORD='$DB_PASSWORD'
            echo 'Recent database connections and queries:'
            psql -h localhost -U $DB_USER -d $DB_NAME -c \"
                SELECT 
                    pid,
                    usename,
                    application_name,
                    client_addr,
                    state,
                    query_start,
                    LEFT(query, 100) as query_preview
                FROM pg_stat_activity
                WHERE datname = '$DB_NAME'
                ORDER BY query_start DESC
                LIMIT 10;
            \" 2>/dev/null || echo 'Could not query database activity'
        "
    }
    echo ""
}

# Function to check database connection status
check_db_status() {
    print_header "Database Connection Status"
    ssh -o StrictHostKeyChecking=no -o UserKnownHostsFile=/dev/null $SERVER_USER@$SERVER_HOST "
        export PGPASSWORD='$DB_PASSWORD'
        echo 'Database: $DB_NAME'
        echo 'Status:'
        psql -h localhost -U $DB_USER -d $DB_NAME -c 'SELECT version();' 2>/dev/null && echo '✓ Connected' || echo '✗ Connection failed'
        echo ''
        echo 'Recent database activity:'
        psql -h localhost -U $DB_USER -d $DB_NAME -c \"
            SELECT 
                schemaname,
                relname as tablename,
                n_live_tup as rows,
                last_vacuum,
                last_autovacuum,
                last_analyze,
                last_autoanalyze
            FROM pg_stat_user_tables
            ORDER BY n_live_tup DESC
            LIMIT 10;
        \" 2>/dev/null || echo 'Could not query table statistics'
    "
    echo ""
}

# Main execution
main() {
    case "$LOG_TYPE" in
        "app")
            check_app_logs
            ;;
        "db")
            check_db_logs
            ;;
        "all")
            check_app_logs
            check_db_status
            check_db_logs
            ;;
    esac
    
    print_header "Log Check Complete"
    print_success "Logs retrieved successfully"
}

# Run main function
main
