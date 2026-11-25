#!/bin/bash
# Sets up one Ubuntu 24.04 server for the YIPPEE CLUB backend: swap, Java 17, MySQL 8, the
# database with its two users, the service's environment file and its systemd unit.
#
# Run as root, once, on a new server: as EC2 user data, or `sudo bash setup-server.sh`.
# The numbers below fit a 1 GB server (t3.micro). The app is not started here: copy the jar
# to /opt/yippee/app.jar, then `systemctl start yippee`.
#
# Secrets are made on the server and are never printed. Keep command tracing (set -x) out of
# this file: user data output is kept in the cloud-init log.
set -euo pipefail

SWAP_GB=2
APP_HEAP_MB=384
MYSQL_BUFFER_MB=128
DB_POOL=8
SERVER_THREADS=50

if [ -f /etc/yippee/env ]; then
    echo "yippee: already set up, nothing done"
    exit 0
fi

# Room for bursts: the JVM and MySQL together sit close to 1 GB.
if [ ! -f /swapfile ]; then
    fallocate -l "${SWAP_GB}G" /swapfile
    chmod 600 /swapfile
    mkswap /swapfile > /dev/null
    swapon /swapfile
    echo '/swapfile none swap sw 0 0' >> /etc/fstab
fi
echo 'vm.swappiness=10' > /etc/sysctl.d/60-yippee.conf
sysctl -q --system

export DEBIAN_FRONTEND=noninteractive
apt-get -o DPkg::Lock::Timeout=600 update -q
# The JDK, not only the runtime: the test suite is run on the server against this MySQL.
apt-get -o DPkg::Lock::Timeout=600 install -y -q openjdk-17-jdk-headless mysql-server

# MySQL listens on this machine only, keeps times in UTC as the app does, and stays small.
cat > /etc/mysql/mysql.conf.d/yippee.cnf <<EOF
[mysqld]
bind-address = 127.0.0.1
mysqlx = OFF
character-set-server = utf8mb4
collation-server = utf8mb4_0900_ai_ci
default-time-zone = '+00:00'
innodb_buffer_pool_size = ${MYSQL_BUFFER_MB}M
performance_schema = OFF
max_connections = 60
EOF
systemctl restart mysql

# Two databases and two users: the tests empty every table, so they never share the app's.
DB_PASSWORD=$(openssl rand -hex 24)
TEST_PASSWORD=$(openssl rand -hex 24)
mysql <<EOF
CREATE DATABASE IF NOT EXISTS yippee_club_db;
CREATE DATABASE IF NOT EXISTS yippee_club_test;
CREATE USER IF NOT EXISTS 'yippee'@'localhost' IDENTIFIED BY '${DB_PASSWORD}';
CREATE USER IF NOT EXISTS 'yippee'@'127.0.0.1' IDENTIFIED BY '${DB_PASSWORD}';
CREATE USER IF NOT EXISTS 'yippee_test'@'localhost' IDENTIFIED BY '${TEST_PASSWORD}';
CREATE USER IF NOT EXISTS 'yippee_test'@'127.0.0.1' IDENTIFIED BY '${TEST_PASSWORD}';
GRANT ALL PRIVILEGES ON yippee_club_db.* TO 'yippee'@'localhost', 'yippee'@'127.0.0.1';
GRANT ALL PRIVILEGES ON yippee_club_test.* TO 'yippee_test'@'localhost', 'yippee_test'@'127.0.0.1';
EOF

id yippee > /dev/null 2>&1 || useradd --system --home-dir /opt/yippee --shell /usr/sbin/nologin yippee
# /data/yippee-files holds the PDFs and covers. It is outside the app's folder so a new jar never touches it.
install -d -o yippee -g yippee -m 750 /opt/yippee /data/yippee-files
install -d -m 700 /etc/yippee

umask 077
cat > /etc/yippee/env <<EOF
SECRET_KEY=$(openssl rand -hex 48)
MYSQL_HOST=127.0.0.1
MYSQL_PORT=3306
MYSQL_DATABASE=yippee_club_db
MYSQL_USER=yippee
MYSQL_PASSWORD=${DB_PASSWORD}
DB_POOL_SIZE=${DB_POOL}
SERVER_THREADS=${SERVER_THREADS}
ACTIVITY_PRIVATE_ROOT=/data/yippee-files
FORWARD_HEADERS_STRATEGY=none
CORS_ALLOWED_ORIGINS=http://localhost:3000,http://127.0.0.1:3000
# The Swagger page at /swagger-ui/index.html: true on a development server only.
SWAGGER_ENABLED=false
# The empty values below are filled in by a person, then: systemctl restart yippee
# PASSWORD_RESET_FRONTEND_URL is the https address of the frontend's reset page; without it the reset mail answers 500.
PASSWORD_RESET_FRONTEND_URL=
TOSS_SECRET_KEY=
ADMIN_EMAILS=
REQUIRED_AGREEMENTS=
# Mail through Gmail, as the Django server did: the Gmail address goes in EMAIL_HOST_USER and
# DEFAULT_FROM_EMAIL, a Google app password (not the account password) in EMAIL_HOST_PASSWORD.
EMAIL_HOST=smtp.gmail.com
EMAIL_PORT=587
EMAIL_HOST_USER=
EMAIL_HOST_PASSWORD=
DEFAULT_FROM_EMAIL=
EOF
cat > /etc/yippee/test.env <<EOF
TEST_DATABASE_URL=jdbc:mysql://127.0.0.1:3306/yippee_club_test?characterEncoding=UTF-8&connectionTimeZone=UTC
TEST_DATABASE_USER=yippee_test
TEST_DATABASE_PASSWORD=${TEST_PASSWORD}
EOF
umask 022

cat > /etc/systemd/system/yippee.service <<EOF
[Unit]
Description=YIPPEE CLUB backend
After=network-online.target mysql.service
Wants=network-online.target

[Service]
User=yippee
Group=yippee
WorkingDirectory=/opt/yippee
EnvironmentFile=/etc/yippee/env
ExecStart=/usr/bin/java -Xms128m -Xmx${APP_HEAP_MB}m -XX:+UseSerialGC -XX:+ExitOnOutOfMemoryError -Duser.timezone=UTC -jar /opt/yippee/app.jar
Restart=on-failure
RestartSec=5
# The JVM answers a stop signal with 143 after finishing the requests in flight.
SuccessExitStatus=143
TimeoutStopSec=30
NoNewPrivileges=true
ProtectSystem=full
PrivateTmp=true

[Install]
WantedBy=multi-user.target
EOF
systemctl daemon-reload
systemctl enable yippee > /dev/null 2>&1

echo "yippee: setup done"
