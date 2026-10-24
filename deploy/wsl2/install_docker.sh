#!/usr/bin/env bash
# Install Docker Engine + compose plugin on WSL2 Ubuntu (official apt repo).
# If download.docker.com is slow, rerun with the Aliyun mirror:
#   DOCKER_APT_MIRROR=https://mirrors.aliyun.com/docker-ce/linux/ubuntu bash install_docker.sh
# If registry pulls are slow, set SET_REGISTRY_MIRROR=1 to configure a mirror.
set -euo pipefail

APT_MIRROR="${DOCKER_APT_MIRROR:-https://download.docker.com/linux/ubuntu}"

sudo apt-get update
sudo apt-get install -y ca-certificates curl
sudo install -m 0755 -d /etc/apt/keyrings
sudo curl -fsSL "$APT_MIRROR/gpg" -o /etc/apt/keyrings/docker.asc
sudo chmod a+r /etc/apt/keyrings/docker.asc
echo "deb [arch=$(dpkg --print-architecture) signed-by=/etc/apt/keyrings/docker.asc] $APT_MIRROR $(. /etc/os-release && echo "$VERSION_CODENAME") stable" \
  | sudo tee /etc/apt/sources.list.d/docker.list >/dev/null
sudo apt-get update
sudo apt-get install -y docker-ce docker-ce-cli containerd.io docker-buildx-plugin docker-compose-plugin

if [ "${SET_REGISTRY_MIRROR:-0}" = "1" ]; then
  echo '{"registry-mirrors": ["https://docker.m.daocloud.io"]}' | sudo tee /etc/docker/daemon.json >/dev/null
fi

sudo systemctl enable --now docker
# TARGET_USER: the WSL login user to grant docker access (default: current user).
sudo usermod -aG docker "${TARGET_USER:-$USER}"

docker --version
docker compose version
echo "Done. If 'docker ps' says permission denied, run 'newgrp docker' or reopen the WSL terminal."
