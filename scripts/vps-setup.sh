#!/bin/bash
# =============================================================================
#  StreamNode — Ubuntu VPS Setup Script
#  Run as root or with sudo on a fresh Ubuntu 20.04/22.04/24.04 server
#  Usage: bash vps-setup.sh
# =============================================================================

set -e  # Exit on any error

# ── Config ────────────────────────────────────────────────────────────────────
APP_DIR="/opt/streamnode"
APP_USER="streamnode"
NODE_VERSION="20"
REPO_URL="https://github.com/AbdullahDev2023/streamnode.git"
SERVICE_NAME="streamnode"
PORT=4000

echo ""
echo "============================================="
echo "  StreamNode VPS Setup"
echo "============================================="
echo ""

# ── 1. System update ──────────────────────────────────────────────────────────
echo "[1/8] Updating system packages..."
apt-get update -y && apt-get upgrade -y
apt-get install -y curl git build-essential ffmpeg ufw


# ── 2. Install Node.js via NodeSource ─────────────────────────────────────────
echo "[2/8] Installing Node.js ${NODE_VERSION}..."
curl -fsSL https://deb.nodesource.com/setup_${NODE_VERSION}.x | bash -
apt-get install -y nodejs
echo "  Node: $(node -v)  |  npm: $(npm -v)"

# ── 3. Create dedicated system user ──────────────────────────────────────────
echo "[3/8] Creating system user '${APP_USER}'..."
if id "$APP_USER" &>/dev/null; then
    echo "  User '${APP_USER}' already exists — skipping."
else
    useradd --system --shell /bin/bash --create-home "$APP_USER"
    echo "  User '${APP_USER}' created."
fi

# ── 4. Clone repository ───────────────────────────────────────────────────────
echo "[4/8] Cloning repository to ${APP_DIR}..."
if [ -d "$APP_DIR/.git" ]; then
    echo "  Repo already cloned — pulling latest..."
    git -C "$APP_DIR" pull
else
    git clone "$REPO_URL" "$APP_DIR"
fi
chown -R "$APP_USER":"$APP_USER" "$APP_DIR"


# ── 5. Install server dependencies & build ────────────────────────────────────
echo "[5/8] Installing npm dependencies and building TypeScript..."
cd "$APP_DIR/server"
sudo -u "$APP_USER" npm ci
sudo -u "$APP_USER" npm run build

# ── 6. Configure environment ──────────────────────────────────────────────────
echo "[6/8] Setting up environment file..."
if [ ! -f "$APP_DIR/server/.env" ]; then
    cp "$APP_DIR/server/.env.example" "$APP_DIR/server/.env"
    echo ""
    echo "  *** ACTION REQUIRED ***"
    echo "  Edit ${APP_DIR}/server/.env and fill in:"
    echo "    - FIREBASE_DB_SECRET"
    echo "    - Any other required secrets"
    echo "  Then restart the service: systemctl restart ${SERVICE_NAME}"
    echo ""
else
    echo "  .env already exists — skipping."
fi

# Ensure recordings dir exists with correct ownership
mkdir -p "$APP_DIR/server/recordings"
chown "$APP_USER":"$APP_USER" "$APP_DIR/server/recordings"


# ── 7. Create systemd service ─────────────────────────────────────────────────
echo "[7/8] Creating systemd service '${SERVICE_NAME}'..."

cat > /etc/systemd/system/${SERVICE_NAME}.service << EOF
[Unit]
Description=StreamNode Relay Server
After=network.target

[Service]
Type=simple
User=${APP_USER}
WorkingDirectory=${APP_DIR}/server
ExecStart=/usr/bin/node dist/server.js
Restart=always
RestartSec=5
StandardOutput=journal
StandardError=journal
SyslogIdentifier=${SERVICE_NAME}
EnvironmentFile=${APP_DIR}/server/.env

[Install]
WantedBy=multi-user.target
EOF

systemctl daemon-reload
systemctl enable "$SERVICE_NAME"
systemctl start "$SERVICE_NAME"
echo "  Service '${SERVICE_NAME}' enabled and started."

# ── 8. Configure UFW firewall ─────────────────────────────────────────────────
echo "[8/8] Configuring firewall..."
ufw allow OpenSSH
ufw allow "$PORT"/tcp
ufw --force enable
echo "  Firewall: SSH and port ${PORT} open."


# ── Summary ───────────────────────────────────────────────────────────────────
echo ""
echo "============================================="
echo "  Setup Complete!"
echo "============================================="
echo ""
echo "  App directory : ${APP_DIR}/server"
echo "  Running as    : ${APP_USER}"
echo "  Port          : ${PORT}"
echo "  Service       : ${SERVICE_NAME}"
echo ""
echo "  Useful commands:"
echo "    systemctl status ${SERVICE_NAME}    # Check status"
echo "    systemctl restart ${SERVICE_NAME}   # Restart server"
echo "    journalctl -u ${SERVICE_NAME} -f    # Live logs"
echo ""
echo "  NEXT STEPS:"
echo "  1. Edit secrets : nano ${APP_DIR}/server/.env"
echo "  2. Restart      : systemctl restart ${SERVICE_NAME}"
echo "  3. Test         : curl http://localhost:${PORT}/health"
echo ""
