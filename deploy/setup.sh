#!/usr/bin/env bash
# Sets up a VCraft relay on a fresh Ubuntu/Debian server. Run as root from the folder that holds
# vcraft-relay-<version>.jar and vcraft-relay.service:
#   bash setup.sh [relay.yourdomain.com]
# Without a domain it uses <server-ip>.sslip.io, a free hostname that points at the server.
set -euo pipefail

DOMAIN="${1:-}"
if [ -z "$DOMAIN" ]; then
    IP=$(curl -4 -fsS https://api.ipify.org)
    DOMAIN="vcraft.${IP//./-}.sslip.io"
fi
echo "==> Setting up the VCraft relay at wss://$DOMAIN"

export DEBIAN_FRONTEND=noninteractive
apt-get update -q
apt-get install -y -q caddy ufw curl
# Any Java 21+ works; newer Ubuntu releases may only ship 25.
for jdk in openjdk-25-jre-headless openjdk-21-jre-headless; do
    if apt-cache show "$jdk" >/dev/null 2>&1; then apt-get install -y -q "$jdk"; break; fi
done

ufw allow OpenSSH >/dev/null
ufw allow 80/tcp >/dev/null
ufw allow 443/tcp >/dev/null
ufw --force enable >/dev/null

id vcraft >/dev/null 2>&1 || useradd --system --home /opt/vcraft --shell /usr/sbin/nologin vcraft
mkdir -p /opt/vcraft/data /opt/vcraft/www/vcraft
cp "$(ls -t vcraft-relay-*.jar | head -1)" /opt/vcraft/vcraft-relay.jar
cp vcraft-relay.service /etc/systemd/system/vcraft-relay.service
chown -R vcraft:vcraft /opt/vcraft

cat > /etc/caddy/Caddyfile <<EOF
$DOMAIN {
	# Signed mod updates
	handle /vcraft/* {
		root * /opt/vcraft/www
		file_server
	}
	handle {
		reverse_proxy 127.0.0.1:24470
	}
}
EOF

systemctl daemon-reload
systemctl enable --now vcraft-relay
systemctl restart vcraft-relay
systemctl reload caddy || systemctl restart caddy

sleep 3
systemctl is-active --quiet vcraft-relay && echo "==> Relay is running"
echo "==> Players connect with: wss://$DOMAIN"
