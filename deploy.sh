#!/bin/bash

# Pocket Money App Deployment Script
# This script builds the application and deploys it to the production server

set -e  # Exit on any error

# Configuration
SERVER_HOST="164.92.89.74"
SERVER_USER="root"
SERVER_APP_DIR="/opt/apps"
APP_NAME="pocketmoney-app"
JAR_NAME="pocketmoney-0.0.1-SNAPSHOT.jar"
LOCAL_BUILD_DIR="build/libs"

# Database Configuration (database is on the same server as application)
DB_HOST="localhost"
DB_PORT="5432"
DB_USER="postgres"
DB_PASSWORD="amazimeza12QW!@"
DB_NAME="pocketmoney_db"


# Colors for output
RED='\033[0;31m'
GREEN='\033[0;32m'
YELLOW='\033[1;33m'
BLUE='\033[0;34m'
NC='\033[0m' # No Color

# Function to print colored output
print_status() {
    echo -e "${BLUE}[INFO]${NC} $1"
}

print_success() {
    echo -e "${GREEN}[SUCCESS]${NC} $1"
}

print_warning() {
    echo -e "${YELLOW}[WARNING]${NC} $1"
}

print_error() {
    echo -e "${RED}[ERROR]${NC} $1"
}

# Function to check if command exists
command_exists() {
    command -v "$1" >/dev/null 2>&1
}

# Check prerequisites
check_prerequisites() {
    print_status "Checking prerequisites..."
    
    if ! command_exists ./gradlew && ! command_exists gradle; then
        print_error "Gradle is not installed or not in PATH"
        exit 1
    fi
    
    if ! command_exists ssh; then
        print_error "SSH is not installed or not in PATH"
        exit 1
    fi
    
    if ! command_exists scp; then
        print_error "SCP is not installed or not in PATH"
        exit 1
    fi
    
    
    print_success "All prerequisites are available"
}

# Build the application
build_application() {
    print_status "Building the application..."
    
    # Clean and build with production profile (use bootJar to create executable JAR)
    if [ -f "./gradlew" ]; then
        ./gradlew clean bootJar -x test
    else
        gradle clean bootJar -x test
    fi
    
    # Find the JAR file (it might be named with or without -plain)
    JAR_FILE=$(find $LOCAL_BUILD_DIR -name "*.jar" ! -name "*-plain.jar" | head -n 1)
    
    if [ -z "$JAR_FILE" ]; then
        print_error "JAR file not found in $LOCAL_BUILD_DIR"
        print_status "Looking for JAR files in build directory..."
        ls -la $LOCAL_BUILD_DIR/*.jar 2>/dev/null || print_error "No JAR files found in build directory"
        exit 1
    fi
    
    print_success "Application built successfully"
    print_status "JAR file: $JAR_FILE"
    print_status "JAR file size: $(du -h $JAR_FILE | cut -f1)"
}

# Test server connection
test_connection() {
    print_status "Testing connection to server $SERVER_USER@$SERVER_HOST..."
    
    if ssh -o ConnectTimeout=10 -o BatchMode=yes $SERVER_USER@$SERVER_HOST "echo 'Connection successful'" 2>/dev/null; then
        print_success "SSH connection successful"
    else
        print_warning "SSH connection failed with key authentication, will try with password"
    fi
}

# Install and setup PostgreSQL on server
setup_database() {
    print_status "Setting up PostgreSQL database on server..."
    
    ssh -o StrictHostKeyChecking=no -o UserKnownHostsFile=/dev/null $SERVER_USER@$SERVER_HOST "
        # Check if PostgreSQL is installed
        if command -v psql >/dev/null 2>&1; then
            echo 'PostgreSQL client is already installed'
        else
            echo 'PostgreSQL is not installed. Installing PostgreSQL...'
            export DEBIAN_FRONTEND=noninteractive
            apt-get update -qq
            apt-get install -y -qq postgresql postgresql-contrib >/dev/null 2>&1
            echo 'PostgreSQL installed successfully'
        fi
        
        # Start PostgreSQL service if not running
        if systemctl is-active --quiet postgresql; then
            echo 'PostgreSQL service is already running'
        else
            echo 'Starting PostgreSQL service...'
            systemctl start postgresql
            systemctl enable postgresql
            sleep 3
        fi
        
        # Check if PostgreSQL is running
        if systemctl is-active --quiet postgresql; then
            echo 'PostgreSQL service is running'
        else
            echo 'Failed to start PostgreSQL service'
            exit 1
        fi
        
        # Set PostgreSQL password for postgres user
        echo 'Setting PostgreSQL password...'
        sudo -u postgres psql -c \"ALTER USER postgres WITH PASSWORD '$DB_PASSWORD';\" 2>/dev/null || true
        
        # Configure PostgreSQL to allow local connections
        echo 'Configuring PostgreSQL for local connections...'
        PG_VERSION=\$(psql --version | grep -oP '\\d+' | head -1)
        PG_HBA_FILE=\"/etc/postgresql/\$PG_VERSION/main/pg_hba.conf\"
        
        # Update pg_hba.conf to allow password authentication for local connections
        if [ -f \"\$PG_HBA_FILE\" ]; then
            # Backup original file
            cp \"\$PG_HBA_FILE\" \"\$PG_HBA_FILE.backup\"
            # Ensure local connections use md5 authentication
            sed -i 's/local   all             all                                     peer/local   all             all                                     md5/g' \"\$PG_HBA_FILE\"
            sed -i 's/local   all             all                                     ident/local   all             all                                     md5/g' \"\$PG_HBA_FILE\"
            # Reload PostgreSQL configuration
            systemctl reload postgresql
            echo 'PostgreSQL configuration updated'
        fi
        
        # Check if database exists
        DB_EXISTS=\$(sudo -u postgres psql -lqt 2>/dev/null | cut -d \\| -f 1 | grep -w $DB_NAME | wc -l)
        
        if [ \"\$DB_EXISTS\" -eq \"0\" ]; then
            echo 'Database $DB_NAME does not exist. Creating it...'
            sudo -u postgres psql -c \"CREATE DATABASE $DB_NAME;\" 2>/dev/null
            if [ \$? -eq 0 ]; then
                echo 'Database $DB_NAME created successfully'
            else
                echo 'Failed to create database $DB_NAME'
                exit 1
            fi
        else
            echo 'Database $DB_NAME already exists'
        fi
        
        # Test connection
        PGPASSWORD=$DB_PASSWORD psql -h localhost -U postgres -d $DB_NAME -c 'SELECT 1;' >/dev/null 2>&1
        if [ \$? -eq 0 ]; then
            echo 'Database connection test successful'
        else
            echo 'Database connection test failed'
            exit 1
        fi
    " || {
        print_error "Failed to setup PostgreSQL database on server"
        print_warning "Please check the error messages above"
        exit 1
    }
    
    print_success "PostgreSQL database setup completed successfully"
}

# Apply database migrations
# NOTE: This function runs on EVERY deployment to ensure all migrations are applied
# The consolidated migration file uses IF NOT EXISTS clauses to make it safe to run multiple times
apply_migrations() {
    print_status "Applying database migrations to remote server..."
    print_status "NOTE: All migrations are applied on every deployment (idempotent - safe to run multiple times)"
    
    # Check if consolidated migration file exists
    if [ ! -f "all_migrations_consolidated.sql" ]; then
        print_error "Migration file 'all_migrations_consolidated.sql' not found in current directory"
        exit 1
    fi
    
    # Copy consolidated migration script to remote server
    print_status "Copying consolidated migration script to remote server..."
    scp -o StrictHostKeyChecking=no -o UserKnownHostsFile=/dev/null \
        all_migrations_consolidated.sql ${SERVER_USER}@${SERVER_HOST}:/tmp/all_migrations_consolidated.sql
    
    if [ $? -ne 0 ]; then
        print_error "Failed to copy migration script to remote server"
        exit 1
    fi
    
    # Run migration script on remote server
    print_status "Running migration script on remote database..."
    ssh -o StrictHostKeyChecking=no -o UserKnownHostsFile=/dev/null ${SERVER_USER}@${SERVER_HOST} "
        export PGPASSWORD='${DB_PASSWORD}'
        echo 'Applying all migrations to database ${DB_NAME}...'
        psql -h ${DB_HOST} -U ${DB_USER} -d ${DB_NAME} -f /tmp/all_migrations_consolidated.sql
        
        if [ \$? -eq 0 ]; then
            echo '✅ Migrations applied successfully!'
            
            # Verify the changes
            echo ''
            echo 'Verifying changes...'
            echo 'Checking receivers table columns:'
            psql -h ${DB_HOST} -U ${DB_USER} -d ${DB_NAME} -c \"\\d receivers\" | grep -E '(assigned_balance|remaining_balance|discount_percentage|user_bonus_percentage|parent_receiver_id|momo_account_phone|is_flexible)' || echo 'Columns verification completed'
            
            echo ''
            echo 'Checking transactions table columns:'
            psql -h ${DB_HOST} -U ${DB_USER} -d ${DB_NAME} -c \"\\d transactions\" | grep -E '(admin_income_amount|discount_amount|user_bonus_amount|receiver_balance_before|receiver_balance_after|top_up_type|mopay_transaction_id)' || echo 'Columns verification completed'
            
            echo ''
            echo 'Checking balance_assignment_history table:'
            psql -h ${DB_HOST} -U ${DB_USER} -d ${DB_NAME} -c \"\\d balance_assignment_history\" > /dev/null 2>&1 && echo '✅ balance_assignment_history table exists' || echo '⚠️  balance_assignment_history table may not exist'
            
            echo ''
            echo 'Checking merchant_user_balances table:'
            psql -h ${DB_HOST} -U ${DB_USER} -d ${DB_NAME} -c \"\\d merchant_user_balances\" > /dev/null 2>&1 && echo '✅ merchant_user_balances table exists' || echo '⚠️  merchant_user_balances table may not exist'
            
            echo ''
            echo 'Checking loans table:'
            psql -h ${DB_HOST} -U ${DB_USER} -d ${DB_NAME} -c \"\\d loans\" > /dev/null 2>&1 && echo '✅ loans table exists' || echo '⚠️  loans table may not exist'
            
            echo ''
            echo 'Checking efashe_settings table:'
            psql -h ${DB_HOST} -U ${DB_USER} -d ${DB_NAME} -c \"\\d efashe_settings\" > /dev/null 2>&1 && echo '✅ efashe_settings table exists' || echo '⚠️  efashe_settings table may not exist'
            
            echo ''
            echo 'Checking efashe_transactions table:'
            psql -h ${DB_HOST} -U ${DB_USER} -d ${DB_NAME} -c \"\\d efashe_transactions\" > /dev/null 2>&1 && echo '✅ efashe_transactions table exists' || echo '⚠️  efashe_transactions table may not exist'
            
            echo ''
            echo 'Checking efashe_transactions table columns:'
            psql -h ${DB_HOST} -U ${DB_USER} -d ${DB_NAME} -c \"\\d efashe_transactions\" | grep -E '(full_amount_transaction_id|customer_cashback_transaction_id|besoft_share_transaction_id|initial_mopay_status|initial_efashe_status|customer_account_name|validated|payment_mode|callback_url)' || echo 'Columns verification completed'
            
            echo ''
            echo 'Checking payment_categories for EFASHE:'
            psql -h ${DB_HOST} -U ${DB_USER} -d ${DB_NAME} -c \"SELECT name FROM payment_categories WHERE name = 'EFASHE';\" 2>/dev/null | grep -q EFASHE && echo '✅ EFASHE category exists' || echo '⚠️  EFASHE category may not exist'
            
            echo ''
            echo 'Checking payment_categories for GASOLINE, DIESEL, QR Code, and PAY_CUSTOMER:'
            psql -h ${DB_HOST} -U ${DB_USER} -d ${DB_NAME} -c \"SELECT name FROM payment_categories WHERE name IN ('GASOLINE', 'DIESEL', 'QR Code', 'PAY_CUSTOMER');\" 2>/dev/null | grep -E '(GASOLINE|DIESEL|QR Code|PAY_CUSTOMER)' && echo '✅ GASOLINE, DIESEL, QR Code, and PAY_CUSTOMER categories exist' || echo '⚠️  Some categories may not exist'
            
            # Clean up migration file
            rm -f /tmp/all_migrations_consolidated.sql
            echo ''
            echo 'Migration verification completed'
        else
            echo '❌ Migration failed'
            exit 1
        fi
    " || {
        print_error "Failed to apply migrations to remote database"
        print_warning "Please check the error messages above"
        print_warning "You can manually run migrations using: ./apply_migrations_remote.sh"
        exit 1
    }
    
    print_success "Database migrations applied successfully"
}

# Check and install Java on server
setup_java() {
    print_status "Checking Java installation on server..."
    
    ssh -o StrictHostKeyChecking=no -o UserKnownHostsFile=/dev/null $SERVER_USER@$SERVER_HOST "
        if command -v java >/dev/null 2>&1; then
            JAVA_VERSION=\$(java -version 2>&1 | head -n 1)
            echo 'Java is already installed: '\$JAVA_VERSION
        else
            echo 'Java is not installed. Installing OpenJDK 17...'
            export DEBIAN_FRONTEND=noninteractive
            apt-get update -qq
            apt-get install -y -qq openjdk-17-jre-headless >/dev/null 2>&1
            if command -v java >/dev/null 2>&1; then
                echo 'Java installed successfully'
                java -version 2>&1 | head -n 1
            else
                echo 'Failed to install Java'
                exit 1
            fi
        fi
    " || {
        print_error "Failed to check/install Java on server"
        print_warning "Please install Java manually: apt install openjdk-17-jre-headless"
        exit 1
    }
    
    print_success "Java is ready on server"
}

# Create app directory on server
setup_server_directory() {
    print_status "Setting up application directory on server..."
    
    ssh -o StrictHostKeyChecking=no -o UserKnownHostsFile=/dev/null $SERVER_USER@$SERVER_HOST "mkdir -p $SERVER_APP_DIR" || {
        print_error "Failed to create application directory"
        exit 1
    }
    
    print_success "Application directory ready on server"
}

# Stop existing application
stop_existing_app() {
    print_status "Stopping existing application..."
    
    ssh -o StrictHostKeyChecking=no -o UserKnownHostsFile=/dev/null $SERVER_USER@$SERVER_HOST "
        cd $SERVER_APP_DIR
        if [ -f $JAR_NAME ] || [ -f app.pid ]; then
            # Find and kill existing Java process
            if [ -f app.pid ]; then
                PID=\$(cat app.pid)
                if ps -p \$PID > /dev/null 2>&1; then
                    echo 'Stopping existing application (PID: '\$PID')'
                    kill \$PID || true
                    sleep 5
                    # Force kill if still running
                    if ps -p \$PID > /dev/null 2>&1; then
                        echo 'Force stopping application'
                        kill -9 \$PID || true
                    fi
                fi
            fi
            
            # Also check for any running Java processes with the JAR name
            PID=\$(ps aux | grep java | grep $JAR_NAME | grep -v grep | awk '{print \$2}' | head -n 1)
            if [ ! -z \"\$PID\" ]; then
                echo 'Stopping existing application process (PID: '\$PID')'
                kill \$PID || true
                sleep 5
                if ps -p \$PID > /dev/null 2>&1; then
                    kill -9 \$PID || true
                fi
            fi
        fi
    " || print_warning "No existing application found or failed to stop (this is OK if app is not running)"
    
    print_success "Existing application stopped"
}

# Deploy the application
deploy_application() {
    print_status "Deploying application to server..."
    
    # Get the actual JAR file name
    JAR_FILE=$(find $LOCAL_BUILD_DIR -name "*.jar" ! -name "*-plain.jar" | head -n 1)
    
    if [ -z "$JAR_FILE" ]; then
        print_error "JAR file not found in $LOCAL_BUILD_DIR"
        exit 1
    fi
    
    # Copy JAR file to server with the expected name
    scp "$JAR_FILE" $SERVER_USER@$SERVER_HOST:$SERVER_APP_DIR/$JAR_NAME
    
    if [ $? -eq 0 ]; then
        print_success "JAR file copied to server successfully"
    else
        print_error "Failed to copy JAR file to server"
        exit 1
    fi
    
    # Set proper permissions
    ssh $SERVER_USER@$SERVER_HOST "chmod +x $SERVER_APP_DIR/$JAR_NAME"
    
    print_success "Application deployed successfully"
}

# Start the application
start_application() {
    print_status "Starting application on server with increased memory..."
    
    ssh $SERVER_USER@$SERVER_HOST "
        cd $SERVER_APP_DIR
        nohup java -Xmx6g -Xms2g -jar -Dspring.profiles.active=prod $JAR_NAME > output.log 2>&1 &
        echo \$! > app.pid
        echo 'Application started with PID: '\$(cat app.pid)
        echo 'Memory settings: -Xmx6g -Xms2g (6GB max, 2GB initial)'
        echo 'Profile: prod'
        echo 'Log file: output.log'
    "
    
    # Wait a moment and check if application started
    sleep 10
    
    ssh $SERVER_USER@$SERVER_HOST "
        cd $SERVER_APP_DIR
        if [ -f app.pid ]; then
            PID=\$(cat app.pid)
            if ps -p \$PID > /dev/null 2>&1; then
                echo 'Application is running with PID: '\$PID
            else
                echo 'Application failed to start'
                echo 'Last 20 lines of log:'
                tail -20 output.log
                exit 1
            fi
        else
            echo 'PID file not found'
            exit 1
        fi
    "
    
    if [ $? -eq 0 ]; then
        print_success "Application started successfully"
    else
        print_error "Application failed to start"
        exit 1
    fi
}

# Test application health
test_application() {
    print_status "Testing application health..."
    
    # Wait for application to fully start
    sleep 15
    
    # Test if application responds
    if curl -s -f "http://$SERVER_HOST:8383/actuator/health" > /dev/null 2>&1; then
        print_success "Application is responding to health checks"
    else
        print_warning "Application health check failed, but it might still be starting up"
    fi
}

# Restart application with memory settings
restart_with_memory() {
    print_status "Restarting application with increased memory..."
    
    ssh $SERVER_USER@$SERVER_HOST "
        cd $SERVER_APP_DIR
        if [ -f app.pid ]; then
            PID=\$(cat app.pid)
            if ps -p \$PID > /dev/null; then
                echo 'Stopping existing application (PID: '\$PID')'
                kill \$PID
                sleep 5
                if ps -p \$PID > /dev/null; then
                    echo 'Force stopping application'
                    kill -9 \$PID
                fi
            fi
        fi
        
        echo 'Starting application with 6GB memory...'
        nohup java -Xmx6g -Xms2g -jar -Dspring.profiles.active=prod $JAR_NAME > output.log 2>&1 &
        echo \$! > app.pid
        echo 'Application restarted with PID: '\$(cat app.pid)
        echo 'Memory settings: -Xmx6g -Xms2g (6GB max, 2GB initial)'
    "
    
    print_success "Application restarted with increased memory"
}

# Show deployment summary
show_summary() {
    print_success "Deployment completed successfully!"
    echo ""
    echo "=========================================="
    echo "  DEPLOYMENT SUMMARY"
    echo "=========================================="
    echo "Server: $SERVER_USER@$SERVER_HOST"
    echo "Application Directory: $SERVER_APP_DIR"
    echo "JAR File: $JAR_NAME"
    echo "Memory Settings: -Xmx6g -Xms2g (6GB max, 2GB initial)"
    echo "Profile: prod"
    echo "Application URL: http://$SERVER_HOST:8383"
    echo "Log File: $SERVER_APP_DIR/output.log"
    echo "PID File: $SERVER_APP_DIR/app.pid"
    echo ""
    echo "Useful commands:"
    echo "  View logs: ssh $SERVER_USER@$SERVER_HOST 'tail -f $SERVER_APP_DIR/output.log'"
    echo "  Check status: ssh $SERVER_USER@$SERVER_HOST 'ps aux | grep $JAR_NAME'"
    echo "  Stop app: ssh $SERVER_USER@$SERVER_HOST 'kill \$(cat $SERVER_APP_DIR/app.pid)'"
    echo "  Restart with memory: ssh $SERVER_USER@$SERVER_HOST 'cd $SERVER_APP_DIR && kill \$(cat app.pid) && nohup java -Xmx6g -Xms2g -jar -Dspring.profiles.active=prod $JAR_NAME > output.log 2>&1 & && echo \$! > app.pid'"
    echo "=========================================="
}

# Main deployment process
main() {
    echo "=========================================="
    echo "  Pocket Money App Deployment Script"
    echo "=========================================="
    echo ""
    
    # Check for command line arguments
    case "${1:-}" in
        "restart")
            print_status "Restarting application with increased memory..."
            restart_with_memory
            return
            ;;
        "help"|"-h"|"--help")
            echo "Usage: $0 [command]"
            echo ""
            echo "Commands:"
            echo "  (no command)  - Full deployment (build + deploy + start)"
            echo "  restart       - Restart existing application with increased memory"
            echo "  help          - Show this help message"
            echo ""
            echo "Memory settings: -Xmx6g -Xms2g (6GB max, 2GB initial)"
            return
            ;;
    esac
    
    check_prerequisites
    build_application
    test_connection
    setup_database
    apply_migrations
    setup_java
    setup_server_directory
    stop_existing_app
    deploy_application
    start_application
    test_application
    show_summary
}

# Handle script interruption
trap 'print_error "Deployment interrupted"; exit 1' INT TERM

# Run main function
main "$@"

